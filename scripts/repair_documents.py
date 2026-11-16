#!/usr/bin/env python3
"""Repair the document store rows the app cannot read, and nothing else.

The two defects
---------------
1. Wire format. Some posts documents hold the shape Firestore's API returns rather than the
   decoded document: `{"fields": {"authorId": {"stringValue": "..."}}}`. The app calls
   `Doc.get("authorId")`, which finds nothing there, so the row renders blank. The fix is to
   decode the typed values into plain JSON.

2. Missing chatId. The chat screen opens with `whereEqualTo("chatId", chatId)`, and a large
   share of message documents carry `senderId`/`receiverId` but no `chatId` at all. Those
   messages exist and are never returned by any query, so the conversation looks empty. The
   `messages` table has `chat_id`, so the value can be restored from there by primary key.

Safety
------
- Dry run by default. `--apply` is required to write, and it also requires `--confirm REPAIR`,
  because this is the one script here that modifies rows in place.
- Only broken rows are touched. A posts row is rewritten only if it is in wire format; a
  message is updated only if it has no chatId. A healthy document is never read into a write.
- Nothing is deleted, and the source tables are not written.
- Every update is a single-row UPDATE by path, so a failure affects one document.

Usage
-----
    python3 scripts/repair_documents.py                # dry run, reports counts
    python3 scripts/repair_documents.py --apply --confirm REPAIR
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.cloudflare.com/client/v4"
D1_DATABASE_ID = os.environ.get("D1_DATABASE_ID", "e1c605ca-a50b-46c6-9deb-56dd1f70d163")

# Firestore's typed-value wrapper -> the plain value the app reads.
SCALAR_TYPES = {
    "stringValue": lambda v: v,
    "integerValue": lambda v: int(v),
    "doubleValue": lambda v: float(v),
    "booleanValue": lambda v: bool(v),
    "nullValue": lambda v: None,
}


def decode_value(value: object) -> object:
    """One Firestore typed value into plain JSON."""
    if not isinstance(value, dict):
        return value
    for key, convert in SCALAR_TYPES.items():
        if key in value:
            try:
                return convert(value[key])
            except (TypeError, ValueError):
                return value[key]
    if "timestampValue" in value:
        return value["timestampValue"]
    if "arrayValue" in value:
        items = (value["arrayValue"] or {}).get("values") or []
        return [decode_value(item) for item in items]
    if "mapValue" in value:
        return decode_fields((value["mapValue"] or {}).get("fields") or {})
    return value


def decode_fields(fields: dict) -> dict:
    return {name: decode_value(value) for name, value in fields.items()}


def d1_query(account_id: str, token: str, sql: str, params: list | None = None) -> list[dict]:
    """Run one statement through the D1 API. Params are bound, never interpolated."""
    url = f"{API}/accounts/{account_id}/d1/database/{D1_DATABASE_ID}/query"
    payload: dict = {"sql": sql}
    if params:
        payload["params"] = params
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        body = json.loads(response.read().decode("utf-8"))
    if not body.get("success", False):
        errors = body.get("errors") or [{"message": "unknown"}]
        raise RuntimeError("; ".join(str(e.get("message", e)) for e in errors))
    result = body.get("result") or []
    return (result[0].get("results") or []) if result else []


def is_wire_format(doc: dict) -> bool:
    """True for a document that still holds Firestore's typed wrapper."""
    return "fields" in doc and "name" in doc and not doc.get("id")


def repair_posts(account_id: str, token: str, apply: bool) -> tuple[int, int]:
    rows = d1_query(
        account_id, token,
        """SELECT path, document_json FROM friendbook_cf_documents
           WHERE collection = 'posts'
             AND json_extract(document_json, '$.fields') IS NOT NULL""",
    )
    broken = [(row["path"], json.loads(row["document_json"])) for row in rows]
    fixed = 0
    for path, doc in broken:
        plain = decode_fields(doc.get("fields") or {})
        # The document id is the last segment of the Firestore name.
        name = str(doc.get("name") or "")
        doc_id = name.rsplit("/", 1)[-1]
        if doc_id:
            plain["id"] = doc_id
        if not apply:
            fixed += 1
            continue
        d1_query(
            account_id, token,
            "UPDATE friendbook_cf_documents SET document_json = ? WHERE path = ?",
            [json.dumps(plain), path],
        )
        fixed += 1
    return len(broken), fixed


def repair_message_chat_ids(account_id: str, token: str, apply: bool) -> tuple[int, int]:
    # chat_id lives in the messages table; the document is matched by primary key.
    rows = d1_query(
        account_id, token,
        """SELECT d.path AS path, d.document_json AS document_json, m.chat_id AS chat_id
           FROM friendbook_cf_documents d
           JOIN messages m ON m.id = d.doc_id
           WHERE d.collection = 'messages'
             AND json_extract(d.document_json, '$.chatId') IS NULL
             AND m.chat_id IS NOT NULL AND m.chat_id <> ''""",
    )
    fixed = 0
    for row in rows:
        if apply:
            doc = json.loads(row["document_json"])
            doc["chatId"] = row["chat_id"]
            # The table's chat_id and the document's id must agree with the app's contract:
            # a message's path is chatId + "_" + messageId, which is what the chat window
            # addresses. Where the stored id already carries that prefix it is kept.
            d1_query(
                account_id, token,
                "UPDATE friendbook_cf_documents SET document_json = ? WHERE path = ?",
                [json.dumps(doc), row["path"]],
            )
        fixed += 1
    return len(rows), fixed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="write the repairs")
    parser.add_argument("--confirm", default="", help="must be REPAIR when --apply is used")
    args = parser.parse_args()

    if args.apply and args.confirm != "REPAIR":
        print("refusing to write without --confirm REPAIR", file=sys.stderr)
        return 2

    token = os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()
    account_id = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "").strip()
    if not (token and account_id):
        print("CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID are required", file=sys.stderr)
        return 1

    mode = "APPLY" if args.apply else "DRY RUN"
    print(f"mode: {mode}")

    posts_broken, posts_fixed = repair_posts(account_id, token, args.apply)
    print(f"posts in wire format: {posts_broken}, repaired: {posts_fixed}")

    messages_broken, messages_fixed = repair_message_chat_ids(account_id, token, args.apply)
    print(f"messages with no chatId (matchable in the table): {messages_broken}, repaired: {messages_fixed}")

    if not args.apply:
        print("nothing written; pass --apply --confirm REPAIR to write")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
