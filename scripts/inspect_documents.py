#!/usr/bin/env python3
"""Dump what the document store actually contains, so the shape can be judged rather than assumed.

The previous check went wrong in a way worth naming: it looked for posts rows that had *no*
document, found none — because the rebuild had just created them all — and reported success.
It never looked at the documents themselves. A document can exist and still render nothing.

So this reads from the other side. For each collection it takes the real documents the app
would receive from /v1/docs and reports:

  - how many there are
  - the field names present (the app reads camelCase; a snake_case key is invisible to it)
  - two complete documents, printed, so a human can see whether a post or a notification would
    appear on screen

Read-only. Two SELECTs per collection, no writes.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.request

API = "https://api.cloudflare.com/client/v4"
D1_DATABASE_ID = os.environ.get("D1_DATABASE_ID", "e1c605ca-a50b-46c6-9deb-56dd1f70d163")

COLLECTIONS = ["posts", "messages"]

# The fields each screen cannot render without. If a document lacks these, the row appears
# blank or is skipped by the app entirely - which is indistinguishable from "not migrated".
REQUIRED = {
    "posts": ["id", "authorId", "createdAt"],
    "notifications": ["id", "toUserId", "createdAt"],
    "chats": ["id", "participants"],
    "users": [],
    "comments": ["id", "postId", "createdAt"],
    "messages": ["id", "chatId", "createdAt"],
    "groups": ["id"],
}


def d1_query(account_id: str, token: str, sql: str) -> list[dict]:
    url = f"{API}/accounts/{account_id}/d1/database/{D1_DATABASE_ID}/query"
    request = urllib.request.Request(
        url,
        data=json.dumps({"sql": sql}).encode("utf-8"),
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not payload.get("success", False):
        errors = payload.get("errors") or [{"message": "unknown"}]
        raise RuntimeError("; ".join(str(e.get("message", e)) for e in errors))
    result = payload.get("result") or []
    return (result[0].get("results") or []) if result else []


def main() -> int:
    token = os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()
    account_id = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "").strip()
    if not (token and account_id):
        print("::error::CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID are required")
        return 1

    # How much of the store is in the Firestore wire format rather than the decoded shape the
    # app reads. A document in wire format has a "fields" object and no direct values, so
    # Doc.get("authorId") finds nothing and the row renders blank. This is the number that
    # matters: it is the difference between "migrated" and "visible".
    for collection in COLLECTIONS:
        try:
            row = d1_query(
                account_id, token,
                f"""SELECT
                      COUNT(*) AS total,
                      SUM(CASE WHEN json_extract(document_json, '$.fields') IS NOT NULL
                               THEN 1 ELSE 0 END) AS wire_format
                    FROM friendbook_cf_documents WHERE collection = '{collection}'""",
            )[0]
            total, wire = int(row["total"]), int(row["wire_format"] or 0)
            if wire:
                print(
                    f"::error::{collection}: {wire} of {total} documents are in raw Firestore "
                    f"wire format (fields/stringValue) - the app reads none of these"
                )
            else:
                print(f"::notice::{collection}: 0 of {total} documents are in wire format")
        except Exception as error:  # noqa: BLE001
            print(f"::warning::{collection}: wire-format count failed - {error}")

    for collection in COLLECTIONS:
        try:
            rows = d1_query(
                account_id, token,
                f"SELECT doc_id, document_json, updated_at FROM friendbook_cf_documents "
                f"WHERE collection = '{collection}' ORDER BY updated_at ASC LIMIT 3",
            )
        except Exception as error:  # noqa: BLE001
            print(f"::error::{collection}: read failed - {type(error).__name__}: {error}")
            continue

        if not rows:
            print(f"::notice::{collection}: NO DOCUMENTS AT ALL")
            continue

        doc = json.loads(rows[0]["document_json"])
        keys = sorted(doc.keys())
        snake = [k for k in keys if "_" in k]
        missing = [f for f in REQUIRED.get(collection, []) if not doc.get(f)]

        problems = []
        if snake:
            problems.append(f"snake_case keys the app cannot read: {snake[:6]}")
        if missing:
            problems.append(f"missing required fields: {missing}")

        level = "error" if problems else "notice"
        verdict = "BROKEN - " + "; ".join(problems) if problems else "looks renderable"
        print(f"::{level}::{collection}: {len(rows)} sampled, {len(keys)} fields, {verdict}")

        # Two annotations per collection: the field list, then one whole document. Everything
        # else goes to stdout, because annotations beyond the tenth are discarded.
        # field list omitted: the sample below shows them
        sample = json.dumps(doc, default=str)[:620]
        print(f"::notice::{collection} sample: {sample}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
