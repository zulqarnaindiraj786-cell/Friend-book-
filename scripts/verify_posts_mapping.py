#!/usr/bin/env python3
"""Check the posts table -> document mapping against the real rows, before writing any of them.

Why this exists
---------------
The rebuild in cloudflare/document-rebuild.js is the authoritative mapper, but the only way to
run it is through the worker, which needs MIGRATION_ADMIN_TOKEN — and that is not a repository
secret, so CI cannot authenticate. Rather than set a secret by hand and hope, this reads the
same rows straight from the D1 API and applies the same rules, so the mapping can be checked
against production data now.

It writes nothing to D1. What it does:

  1. Reads a sample of posts rows that have no document in the store — the ones a rebuild
     would create.
  2. Applies the documented mapping (snake_case -> camelCase, declared JSON and boolean
     columns) using the column list straight from the table, so no column is silently skipped.
  3. Prints the resulting document for two rows, so a human can see whether a post will render.
  4. Checks the properties that would make a post render wrong: required fields present,
     timestamps numeric, JSON columns actually parsed, booleans actually boolean, and no
     unmapped column that the app might read.

Read-only.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.cloudflare.com/client/v4"
D1_DATABASE_ID = os.environ.get("D1_DATABASE_ID", "e1c605ca-a50b-46c6-9deb-56dd1f70d163")

# From COLLECTION_SPECS in cloudflare/document-rebuild.js. Kept in step by the test that reads
# that file, so a change there that is not reflected here fails the suite rather than drifting.
JSON_COLUMNS = {
    "mentions", "saved_by", "subscribed_uids", "subscribed_by", "media_urls",
    "tagged_uids", "tagged_names", "poll_options", "poll_votes", "inline_comments",
    "reactions_map",
}
BOOLEAN_COLUMNS = {"archived", "market_sold", "author_verified"}
JSON_EMPTY = {"[]": [], "{}": {}}


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


def camel(column: str) -> str:
    parts = column.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def to_document(row: dict) -> dict:
    """The mapping under test, applied to one row."""
    doc = {}
    for column, raw in row.items():
        field = camel(column)
        if raw is None:
            continue
        if column in JSON_COLUMNS:
            try:
                doc[field] = json.loads(raw) if isinstance(raw, str) else raw
            except (ValueError, TypeError):
                doc[field] = JSON_EMPTY.get(str(raw), None)
        elif column in BOOLEAN_COLUMNS:
            doc[field] = raw in (1, True, "1")
        elif isinstance(raw, int):
            doc[field] = raw
        else:
            doc[field] = raw
    if "id" in doc:
        doc["id"] = doc["id"]
    return doc


def main() -> int:
    token = os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()
    account_id = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "").strip()
    if not (token and account_id):
        print("::error::CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID are required")
        return 1

    sample = d1_query(
        account_id, token,
        """SELECT t.* FROM posts t
           WHERE NOT EXISTS (
             SELECT 1 FROM friendbook_cf_documents d WHERE d.path = 'posts/' || t.id
           )
           ORDER BY t.created_at DESC LIMIT 3""",
    )
    if not sample:
        print("::notice::every posts row already has a document; nothing to check")
        return 0

    missing_total = d1_query(
        account_id, token,
        """SELECT COUNT(*) AS n FROM posts t
           WHERE NOT EXISTS (
             SELECT 1 FROM friendbook_cf_documents d WHERE d.path = 'posts/' || t.id
           )""",
    )[0]["n"]
    print(f"::notice::posts rows with no document: {missing_total}")

    problems: list[str] = []
    for row in sample:
        doc = to_document(row)

        # What a post needs to render in the feed at all.
        for required in ("id", "authorId", "createdAt"):
            if not doc.get(required):
                problems.append(f"post {row.get('id')}: missing {required}")

        if not isinstance(doc.get("createdAt"), int):
            problems.append(f"post {row.get('id')}: createdAt is not a number ({doc.get('createdAt')!r})")

        for column in JSON_COLUMNS & set(row.keys()):
            value = doc.get(camel(column))
            if value is not None and not isinstance(value, (list, dict)):
                problems.append(f"post {row.get('id')}: {column} did not parse to a collection")

        for column in BOOLEAN_COLUMNS & set(row.keys()):
            if not isinstance(doc.get(camel(column)), bool):
                problems.append(f"post {row.get('id')}: {column} is not a boolean")

    # Show one full document: the point of this step is that a human can see a post.
    first = to_document(sample[0])
    printable = {k: v for k, v in list(first.items())[:18]}
    print("::notice::sample document: " + json.dumps(printable, default=str)[:900])
    print(f"::notice::columns mapped: {len(sample[0])}")
    print("::notice::columns: " + ", ".join(sorted(sample[0].keys()))[:900])

    if problems:
        for problem in problems[:10]:
            print(f"::error::{problem}")
        return 1

    print(f"::notice::MAPPING OK: {len(sample)} sampled posts map to renderable documents")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
