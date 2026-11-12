#!/usr/bin/env python3
"""Rewrites the app's Firestore write statements into the single D1 write path.

The migration rule is that a client on this build writes to Cloudflare D1 and never back into
Firestore. There are ~150 write statements across the app and hand-editing each one is how a release
loses a screen, so this script does the rewrite mechanically and refuses anything it does not fully
understand: every site it cannot prove is printed for a human to finish, and that file is left alone.

For each standalone write statement

    Fb.db().collection("posts").document(id).update("likes", FieldValue.increment(1));

it emits

    D1Write.write("posts", id, Fb.map("likesDelta", 1), null, null,
            () -> Fb.db().collection("posts").document(id).update("likes", FieldValue.increment(1)));

The last argument is the pre-migration write: `D1Write` runs it only when Cloudflare is switched off
for a build (verification/debug), so a release build has no path into Firestore.

Usage:
    python3 scripts/d1_convert.py --report     # dry run: every site and the decision taken
    python3 scripts/d1_convert.py --apply      # rewrite the files in place
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import tree_sitter_java
from tree_sitter import Language, Parser

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"

PARSER = Parser(Language(tree_sitter_java.language()))

WRITE_METHODS = {"update", "set", "delete", "add"}
INCREMENT_FIELDS = {"likes", "comments", "shares", "views", "friendsCount", "followersCount",
                    "likesCount", "viewers", "stars"}


def txt(src: bytes, node) -> str:
    return src[node.start_byte:node.end_byte].decode("utf-8")


def method_name(node) -> str:
    if node.type != "method_invocation":
        return ""
    name = node.child_by_field_name("name")
    return name.text.decode() if name is not None and name.text else ""


def receiver(node):
    return node.child_by_field_name("object") if node.type == "method_invocation" else None


def arg_texts(src: bytes, node):
    a = node.child_by_field_name("arguments")
    out = []
    if a is None:
        return out
    for child in a.children:
        if child.type in ("(", ")", ","):
            continue
        out.append(txt(src, child))
    return out


def chain_starts_at_db(src: bytes, write) -> bool:
    """True only when the chain under this call really begins with `Fb.db()`."""
    cur = receiver(write)
    while cur is not None and cur.type == "method_invocation":
        rec = receiver(cur)
        if rec is None:
            return False
        if rec.type != "method_invocation":
            return txt(src, rec) == "Fb.db()"
        cur = rec
    return False


def write_base(statement):
    """The `X.update(...)` node under any listener chain, or None."""
    node = statement
    if node.type == "expression_statement":
        # The statement node is the wrapper: its first child is the expression.
        node = next((c for c in node.children if c.type != ";"), None)
    while node is not None and node.type == "method_invocation" \
            and method_name(node) not in WRITE_METHODS:
        node = receiver(node)
    if node is None or node.type != "method_invocation":
        return None
    if method_name(node) not in WRITE_METHODS:
        return None
    return node


def collection_of(src: bytes, write):
    cur = receiver(write)
    while cur is not None and cur.type == "method_invocation":
        if method_name(cur) == "collection":
            a = arg_texts(src, cur)
            return a[0] if a else None
        cur = receiver(cur)
    return None


def document_id_of(src: bytes, write):
    cur = receiver(write)
    while cur is not None and cur.type == "method_invocation":
        name = method_name(cur)
        if name == "document":
            a = arg_texts(src, cur)
            return a[0] if a else None
        if name == "collection":
            return None
        cur = receiver(cur)
    return None


def listeners(src: bytes, statement, name):
    found = []

    def walk(n):
        if n.type == "method_invocation":
            if method_name(n) == name:
                a = arg_texts(src, n)
                if a:
                    found.append(a[-1])
            rec = receiver(n)
            if rec is not None:
                walk(rec)

    walk(statement)
    return found


def lambda_parts(lam: str):
    m = re.match(r"^(\(?[^){}]*?\)?)\s*->\s*(.*)$", lam.strip(), re.S)
    if not m:
        return None, None
    params, body = m.group(1).strip(), m.group(2).strip()
    if params.startswith("(") and params.endswith(")"):
        params = params[1:-1].strip()
    if body.startswith("{") and body.endswith("}"):
        body = body[1:-1]
    return [p.strip() for p in params.split(",") if p.strip()], body


def mentions(body: str, name: str) -> bool:
    return re.search(r"\b%s\b" % re.escape(name), body) is not None


def one_line(body: str) -> str:
    return re.sub(r"\s+", " ", body).strip()


def split_top(inner: str):
    """Split a call's argument text on commas that are at depth 0 and outside strings."""
    parts, depth, cur, in_str, i = [], 0, "", False, 0
    while i < len(inner):
        ch = inner[i]
        if in_str:
            cur += ch
            if ch == "\\":
                cur += inner[i + 1]
                i += 2
                continue
            if ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
            cur += ch
        elif ch in "([{":
            depth += 1
            cur += ch
        elif ch in ")]}":
            depth -= 1
            cur += ch
        elif ch == "," and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
        i += 1
    if cur.strip():
        parts.append(cur.strip())
    return parts


def pairs_of(inner: str):
    parts = split_top(inner)
    if len(parts) % 2:
        return None
    return [(parts[i], parts[i + 1]) for i in range(0, len(parts), 2)]


PUT = re.compile(r"\b([A-Za-z_$][\w$]*)\.put\((.*?)\)\s*;", re.S)
DECL = re.compile(r"(?:Map<[^>]*>|HashMap<[^>]*>|java\.util\.Map<[^>]*>)\s+([A-Za-z_$][\w$]*)\s*=\s*"
                  r"(?:new\s+(?:java\.util\.)?HashMap[^;]*?;)")
ARRAY_OP = re.compile(r"^FieldValue\.array(Union|Remove)\((.*)\)$", re.S)


def map_literal(src: bytes, expr: str, before_byte: int):
    """Read the map a write stores: Fb.map(...), Map.of(...), a double-brace HashMap, or a variable
    built with `x.put(...)` just above the write. Returns (pairs, why)."""
    expr = expr.strip()
    m = re.match(r"^(?:Fb\.map|(?:java\.util\.)?Map\.of)\((.*)\)$", expr, re.S)
    if m:
        pairs = pairs_of(m.group(1))
        return (pairs, "") if pairs is not None else (None, "map arguments are not key/value pairs")

    m = re.match(r"^new\s+(?:java\.util\.)?HashMap[^\{]*\{\{(.*)\}\}\s*$", expr, re.S)
    if m:
        pairs = [(a, b) for a, b in (split_top(x) for x in PUT.findall(m.group(1)))
                 if len((a, b)) == 2]
        if pairs:
            return pairs, ""
        return None, "double-brace map without put() pairs"

    m = re.match(r"^(?:java\.util\.)?Collections\.singletonMap\((.*)\)$", expr, re.S)
    if m:
        kv = split_top(m.group(1))
        return ([(kv[0], kv[1])], "") if len(kv) == 2 else (None, "singletonMap shape")

    if re.fullmatch(r"[A-Za-z_$][\w$]*", expr):
        # A map variable built a few lines above the write: `Map<String,Object> upd = new HashMap<>();`
        # followed by `upd.put("k", v);` - the pairs are read from that region only.
        head = src[:before_byte].decode("utf-8", "replace")
        decl = None
        for dm in DECL.finditer(head):
            if dm.group(1) == expr:
                decl = dm
        direct = re.search(r"(?:Map<[^>]*>|HashMap<[^>]*>)\s+%s\s*=\s*([^;]+);" % re.escape(expr), head)
        if direct is not None:
            nested, _why = map_literal(src, direct.group(1), before_byte)
            if nested:
                return nested, ""
        if decl is not None:
            region = head[decl.end():]
            pairs = []
            for pm in PUT.finditer(region):
                if pm.group(1) != expr:
                    continue
                kv = split_top(pm.group(2))
                if len(kv) == 2:
                    pairs.append((kv[0], kv[1]))
            for pm in re.finditer(r"\b%s\.putAll\((.*?)\)\s*;" % re.escape(expr), region, re.S):
                nested, _why = map_literal(src, pm.group(1), before_byte)
                if nested:
                    pairs.extend(nested)
            if pairs:
                return pairs, ""
        return None, "map variable is not built with put() pairs"

    return None, "map expression not understood"


def delta_map(src: bytes, expr: str, before_byte: int):
    """Turn increments into `*Delta` fields and array unions into `*Add`/`*Remove` fields."""
    pairs, why = map_literal(src, expr, before_byte)
    if pairs is None:
        return None, why
    out = []
    for key, value in pairs:
        value = value.strip()
        if re.fullmatch(r'"[^"]+\."\s*\+.*', key) or "." in key.strip('"'):
            return None, "a dotted field path needs a nested write"
        inc = re.match(r"^FieldValue\.increment\((.*)\)$", value, re.S)
        arr = ARRAY_OP.match(value)
        if inc:
            literal = re.fullmatch(r'"([A-Za-z0-9_]+)"', key.strip())
            if not literal or literal.group(1) not in INCREMENT_FIELDS:
                return None, "increment on %s has no delta field" % key.strip()
            out.append(('"%sDelta"' % literal.group(1), inc.group(1).strip()))
        elif arr:
            literal = re.fullmatch(r'"([A-Za-z0-9_]+)"', key.strip())
            if not literal:
                return None, "array op on a non-literal field"
            verb = "Add" if arr.group(1) == "Union" else "Remove"
            items = split_top(arr.group(2))
            value_expr = items[0] if len(items) == 1 else "java.util.Arrays.asList(%s)" % ", ".join(items)
            out.append(('"%s%s"' % (literal.group(1), verb), value_expr))
        elif "FieldValue." in value:
            if value.strip().endswith("FieldValue.serverTimestamp()"):
                continue  # the Worker stamps the time of the write itself
            if ".replace(" in value or value.strip().endswith("FieldValue.delete()"):
                return None, "a field delete is not expressible on a merge store"
            return None, "other FieldValue sentinel"
        else:
            out.append((key, value))
    if not out:
        return None, "the write only carried a server timestamp"
    return "Fb.map(%s)" % ", ".join("%s, %s" % kv for kv in out), ""


SENTINEL_FIELD = {
    "FieldValue.serverTimestamp()": None,  # dropped: the Worker stamps its own time
}


def drop_sentinels(pairs):
    """The Worker stamps createdAt/updatedAt itself, so a server-timestamp value is not carried."""
    out = []
    for key, value in pairs:
        if value.strip() == "FieldValue.serverTimestamp()":
            continue
        if "FieldValue." in value:
            return None
        out.append((key, value))
    return out


class Rewrite:
    __slots__ = ("start", "end", "code", "kind")

    def __init__(self, start, end, code, kind):
        self.start, self.end, self.code, self.kind = start, end, code, kind


def plan(src: bytes, statement, report):
    write = write_base(statement)
    if write is None:
        return None
    text_all = txt(src, statement)
    if "Fb.db().collection(" not in text_all or "document(" not in text_all and ".add(" not in text_all:
        return None
    if text_all.count(".collection(") > 1:
        # A nested path (`chats/<id>/messages/<id>`): D1 stores one flat path per document, so these
        # are converted by hand where the id is composed (`chatId + "_" + messageId`).
        note(report, "nested", statement, "sub-collection path is converted by hand", "")
        return None
    collection = collection_of(src, write)
    if collection is None or not collection.startswith('"'):
        note(report, "skip", statement, "collection is not a literal", "")
        return None
    method = method_name(write)
    doc_id = document_id_of(src, write)
    call_args = arg_texts(src, write)

    fields = None
    if method in ("update", "set"):
        if len(call_args) == 1:
            fields = call_args[0]
        elif len(call_args) == 2:
            if method == "set":
                # set(map, SetOptions.merge()) - merge is how D1 writes anyway.
                if not call_args[1].strip().endswith("SetOptions.merge()"):
                    note(report, "skip", statement, "set with merge options", "")
                    return None
                fields = call_args[0]
            else:
                ternary = re.match(r"^(.*\?)\s*FieldValue\.arrayRemove\((.*?)\)\s*:\s*"
                                   r"FieldValue\.arrayUnion\((.*?)\)$", call_args[1].strip(), re.S)
                literal = re.fullmatch(r'"([A-Za-z0-9_]+)"', call_args[0].strip())
                if ternary and literal:
                    fields = '(%s Fb.map("%sRemove", %s) : Fb.map("%sAdd", %s))' % (
                        ternary.group(1), literal.group(1), ternary.group(2).strip(),
                        literal.group(1), ternary.group(3).strip())
                else:
                    fields = "Fb.map(%s, %s)" % (call_args[0], call_args[1])
        else:
            note(report, "skip", statement, "unexpected %s arity" % method, "")
            return None
        if not (fields.startswith("(") and "Fb.map(" in fields):
            fields, why = delta_map(src, fields, statement.start_byte)
            if fields is None:
                note(report, "skip", statement, why, "")
                return None
    elif method == "add":
        if len(call_args) != 1:
            note(report, "skip", statement, "unexpected add arity", "")
            return None
        fields, why = delta_map(src, call_args[0], statement.start_byte)
        if fields is None:
            note(report, "skip", statement, why, "")
            return None

    original = one_line(txt(src, statement).rstrip(";"))
    legacy = "() -> %s" % original

    # ---- continuations -------------------------------------------------------------------------
    success = "null"
    for lam in listeners(src, statement, "addOnSuccessListener"):
        params, body = lambda_parts(lam)
        if params is None:
            continue
        if not params or not any(mentions(body, p) for p in params):
            success = "() -> {%s}" % one_line(body)
            break
        if method == "add" and len(params) == 1:
            p = params[0]
            only_id = re.sub(r"\b%s\.getId\(\)" % re.escape(p), "", body)
            if not mentions(only_id, p):
                success = "__NEW_ID__ -> {%s}" % one_line(
                    re.sub(r"\b%s\.getId\(\)" % re.escape(p), "__NEW_ID__", body))
                break
        note(report, "listener-dropped", statement, "success body uses its snapshot argument",
             body[:70])
        break

    failure = "null"
    for lam in listeners(src, statement, "addOnFailureListener"):
        params, body = lambda_parts(lam)
        if params is None or len(params) != 1:
            continue
        p = params[0]
        body2 = re.sub(r"\b%s\.(getMessage\(\)|getLocalizedMessage\(\))" % re.escape(p), "__MSG__", body)
        if mentions(body2, p):
            # The body needs the exception object itself; drop it rather than emit broken Java.
            note(report, "failure-dropped", statement, "failure body uses the exception object",
                 body[:60])
            break
        failure = "__MSG__ -> {%s}" % one_line(body2)
        break

    # ---- emit ----------------------------------------------------------------------------------
    if method == "delete":
        if doc_id is None:
            note(report, "skip", statement, "delete without a document id", "")
            return None
        code = "D1Write.delete(%s, %s, %s, %s, %s)" % (collection, doc_id, success, failure, legacy)
        kind = "delete"
    elif method == "add":
        if success.startswith("__NEW_ID__ -> "):
            body = success[len("__NEW_ID__ -> "):].strip()[1:-1]
            code = "D1Write.add(%s, %s, __NEW_ID__ -> {%s}, %s, %s)" % (
                collection, fields, body, failure, legacy)
            kind = "add+id"
        else:
            code = "D1Write.add(%s, %s, null, %s, %s)" % (collection, fields, failure, legacy)
            kind = "add"
    else:
        if doc_id is None:
            note(report, "skip", statement, "write without a document id", "")
            return None
        code = "D1Write.write(%s, %s, %s, %s, %s, %s)" % (
            collection, doc_id, fields, success, failure, legacy)
        kind = method
    return Rewrite(statement.start_byte, statement.end_byte, code, kind)


IMPORT_LINE = b"import com.friendbook.social.core.D1Write;"


def ensure_import(new_src: bytes, path: pathlib.Path) -> bytes:
    """`D1Write` has to be visible: the class itself is in that package, every other file imports it."""
    if b"D1Write." not in new_src:
        return new_src
    if b"import com.friendbook.social.core.D1Write;" in new_src:
        return new_src
    if b"\npackage com.friendbook.social.core;" in new_src:
        return new_src  # same package
    lines = new_src.split(b"\n")
    last_import = None
    for i, line in enumerate(lines):
        if line.startswith(b"import "):
            last_import = i
    if last_import is None:
        for i, line in enumerate(lines):
            if line.startswith(b"package "):
                last_import = i
                break
    if last_import is None:
        return new_src
    lines.insert(last_import + 1, IMPORT_LINE)
    return b"\n".join(lines)


CURRENT_FILE = [""]


def node_name(node):
    """The file this node belongs to, for a readable report."""
    return CURRENT_FILE[0]


def note(report, kind, node, why, extra=""):
    loc = "%d:%d" % (node.start_point[0] + 1, node.start_point[1]) if node is not None else "-"
    report.append((kind, loc, CURRENT_FILE[0], why, extra))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--only", default="")
    ap.add_argument("--quiet", action="store_true")
    opts = ap.parse_args()

    report, sites, files, kinds = [], 0, 0, {}
    here = ""
    for path in sorted(SRC.rglob("*.java")):
        if opts.only and opts.only not in str(path):
            continue
        src = path.read_bytes()
        here = str(path.relative_to(SRC))
        CURRENT_FILE[0] = here
        tree = PARSER.parse(src)
        if tree.root_node.has_error:
            note(report, "unparsed-file", None, here, "")
            continue
        statements, stack = [], [tree.root_node]
        while stack:
            node = stack.pop()
            if node.type == "expression_statement":
                statements.append(node)
            stack.extend(node.children)
        # Only outermost statements: the Firestore call inside an already-converted site lives in a
        # lambda that is itself an expression statement, and converting it again would nest forever.
        statements.sort(key=lambda n: (n.start_byte, -n.end_byte))
        protected = []   # ranges of statements that are already a D1Write call
        keep = []
        for node in statements:
            inside = any(a <= node.start_byte and node.end_byte <= b for a, b in protected)
            if inside:
                continue
            snippet = src[node.start_byte:node.end_byte]
            keep.append(node)
            if b"D1Write." in snippet:
                protected.append((node.start_byte, node.end_byte))
            # A converted write's own lambda is the pre-migration statement: never convert it again.
        statements = keep
        rewrites = []
        for statement in statements:
            snippet = src[statement.start_byte:statement.end_byte]
            if b"Fb.db()" not in snippet:
                continue
            if b"D1Write." in snippet:
                continue  # already on the single write path
            try:
                rw = plan(src, statement, report)
            except Exception as exc:  # a converter bug must never damage a file
                note(report, "converter-error", statement, str(exc), "")
                continue
            if rw is not None:
                rewrites.append(rw)
        if not rewrites:
            continue
        rewrites.sort(key=lambda r: r.start, reverse=True)
        new_src = src
        for rw in rewrites:
            new_src = new_src[:rw.start] + rw.code.encode() + b";" + new_src[rw.end:]
        new_src = ensure_import(new_src, path)
        if PARSER.parse(new_src).root_node.has_error:
            note(report, "rewrite-unparsed", None, path.name, "")
            continue
        sites += len(rewrites)
        files += 1
        for rw in rewrites:
            kinds[rw.kind] = kinds.get(rw.kind, 0) + 1
        if opts.apply:
            path.write_text(new_src.decode("utf-8"))

    if not opts.quiet:
        for kind, loc, name, why, extra in report:
            print("%-17s %-9s %-42s %-46s %s" % (kind, loc, name, why, extra[:60]))
    print("\nrewritten: %d statements in %d files  %s" % (sites, files, kinds))
    print("left for a human: %d  (%s)" % (len(report), sorted({r[0] for r in report})))
    return 0


if __name__ == "__main__":
    sys.exit(main())
