"""Rewrite Firestore snapshot listeners into one-shot D1 reads.

Firestore's network is disabled, so a listener can only ever replay this device's cache -
there is no push. Each listener therefore becomes a single read of the same collection and
filters, delivered once. Screens keep their rendering code; only the data source changes.

The rewrite is deliberately conservative: it only touches calls whose chain it can parse into
{collection, wheres, orderBy, limit}, and it refuses anything with a cursor or a metadata
callback rather than guessing. Anything it refuses is left untouched for a human - a wrong
rewrite here is a screen that silently shows the wrong rows.

Usage: python3 scripts/rewrite_listeners.py --dry-run
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/friendbook/social"

CALL = ".addSnapshotListener("


def find_matching(text: str, start: int, open_ch: str, close_ch: str) -> int:
    """Index of the character closing the opener at `start`, or -1."""
    depth = 0
    i = start
    in_str = None
    while i < len(text):
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == in_str:
                in_str = None
        elif ch in "\"'":
            in_str = ch
        elif ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def parse_chain(chain: str):
    """Parse `.collection("x").whereEqualTo("a","b").orderBy("c",Direction.DESC).limit(n)`."""
    m = re.match(r'\.collection\(\s*"([^"]+)"\s*\)', chain)
    if not m:
        return None
    collection = m.group(1)
    rest = chain[m.end():]
    wheres = []
    order = None
    limit = 50

    for part in re.finditer(r"\.(whereEqualTo|orderBy|limit|whereArrayContains)\((.*?)\)(?=\.|$)", rest, re.S):
        kind, args = part.group(1), part.group(2).strip()
        if kind == "whereEqualTo":
            a = re.match(r'^"([^"]+)"\s*,\s*(.+)$', args, re.S)
            if not a:
                return None
            field, value = a.group(1), a.group(2).strip()
            # Only literals: a variable is fine, but a nested call may itself be a read.
            if value.startswith('"') and value.endswith('"'):
                value = value[1:-1]
            elif not re.match(r"^[A-Za-z_][\w.()]*$", value):
                return None
            wheres.append((field, value))
        elif kind == "whereArrayContains":
            a = re.match(r'^"([^"]+)"\s*,\s*(.+)$', args, re.S)
            if not a:
                return None
            wheres.append((a.group(1), a.group(2).strip(), "array"))
        elif kind == "orderBy":
            a = re.match(r'^"([^"]+)"\s*,\s*Query\.Direction\.(ASCENDING|DESCENDING)$', args)
            if not a:
                return None
            order = (a.group(1), "ASC" if a.group(2) == "ASCENDING" else "DESC")
        elif kind == "limit":
            if not re.match(r"^\d+$", args):
                return None
            limit = int(args)

    leftover = rest
    for part in re.finditer(r"\.(whereEqualTo|orderBy|limit|whereArrayContains)\((.*?)\)(?=\.|$)", rest, re.S):
        leftover = leftover.replace(part.group(0), "", 1)
    if leftover.strip():
        # whereIn, limitToLast, startAfter, doc-less chains: not safe to rewrite mechanically.
        return None

    return collection, wheres, order, limit


def rewrite(text: str):
    out = []
    changed = 0
    refused = []
    i = 0
    while True:
        idx = text.find(CALL, i)
        if idx < 0:
            out.append(text[i:])
            break

        # Walk back to the start of the statement's expression: the last Fb.db() before the call.
        start = text.rfind("Fb.db()", 0, idx)
        if start < 0 or text.count("\n", start, idx) > 6:
            refused.append(("unparseable chain start", text[idx:idx + 80]))
            out.append(text[i:idx + len(CALL)])
            i = idx + len(CALL)
            continue

        chain_start = start + len("Fb.db()")
        chain = text[chain_start:idx].strip()
        parsed = parse_chain(chain)
        if not parsed:
            refused.append((chain.strip()[:70], text[idx:idx + 60]))
            out.append(text[i:idx + len(CALL)])
            i = idx + len(CALL)
            continue

        # Find the lambda body and its end.
        body_start = text.find("{", idx)
        if body_start < 0:
            refused.append(("no lambda body", chain.strip()[:70]))
            out.append(text[i:idx + len(CALL)])
            i = idx + len(CALL)
            continue
        params = text[idx + len(CALL):body_start].strip()
        if not params.startswith("(snap") and not params.startswith("(d,") and not params.startswith("(doc"):
            refused.append(("unknown lambda params: " + params[:40], chain.strip()[:60]))
            out.append(text[i:idx + len(CALL)])
            i = idx + len(CALL)
            continue

        body_end = find_matching(text, body_start, "{", "}")
        if body_end < 0:
            refused.append(("unbalanced body", chain.strip()[:70]))
            out.append(text[i:idx + len(CALL)])
            i = idx + len(CALL)
            continue

        # Call ends at the closing paren after the brace, expect `);`
        close = body_end + 1
        while close < len(text) and text[close] in " \t\r\n":
            close += 1
        if close >= len(text) or text[close] != ")":
            refused.append(("call does not end at lambda", chain.strip()[:70]))
            out.append(text[i:idx + len(CALL)])
            i = idx + len(CALL)
            continue
        close += 1
        while close < len(text) and text[close] in " \t\r\n":
            close += 1
        if close >= len(text) or text[close] != ";":
            refused.append(("statement does not end with ;", chain.strip()[:70]))
            out.append(text[i:idx + len(CALL)])
            i = idx + len(CALL)
            continue
        close += 1

        body = text[body_start + 1:body_end]
        if re.search(r"\berr\b", body):
            # Bodies that branch on the error need a decision the codemod cannot make.
            refused.append(("body reads err", chain.strip()[:70]))
            out.append(text[i:idx + len(CALL)])
            i = idx + len(CALL)
            continue

        collection, wheres, order, limit = parsed
        q = f'com.friendbook.social.core.DocQuery.of("{collection}")'
        for w in wheres:
            if len(w) == 3:
                q += f'.whereArrayContains("{w[0]}",{w[1]})'
            else:
                value = f'"{w[1]}"' if not re.match(r"^[A-Za-z_][\w.()]*$", w[1]) else w[1]
                q += f'.whereEqualTo("{w[0]}",{value})'
        if order:
            q += f'.orderBy("{order[0]}",com.friendbook.social.core.DocQuery.{order[1]})'
        q += f'.limit({limit}).build()'

        out.append(text[i:start])
        out.append(f'com.friendbook.social.core.D1Snap.read({q},snap->{{{body}}});')
        changed += 1
        i = close

    return "".join(out), changed, refused


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    total = 0
    refusals = []
    for path in sorted(JAVA.rglob("*.java")):
        text = path.read_text()
        if CALL not in text:
            continue
        new, changed, refused = rewrite(text)
        for r in refused:
            refusals.append((path.relative_to(JAVA), r[0]))
        if changed:
            total += changed
            print(f"{path.relative_to(JAVA)}: {changed}")
            if not args.dry_run:
                path.write_text(new)

    print(f"\n{total} listeners rewritten")
    if refusals:
        print(f"\n{len(refusals)} left for hand editing:")
        for path, why in refusals:
            print(f"  {path}: {why}")


if __name__ == "__main__":
    main()
