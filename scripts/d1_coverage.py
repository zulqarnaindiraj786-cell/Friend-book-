#!/usr/bin/env python3
"""How much of the "missing" content is already sitting inside D1 in another shape?

The question this answers
------------------------
The app reads documents from `friendbook_cf_documents` (2,086 rows). Alongside it, D1 holds
normalised tables written by earlier copy runs — `chats` 326 rows, `notifications` 2,396,
`posts` 595 — which together are 4,651 rows. Those numbers are close enough to the gap between
Firestore and the document store that the "missing" content may not be missing at all: it may
already be here, in the other shape, written by a run that predates the document store.

If that is true, the old content does not need Firestore at all — which matters, because every
attempt to read Firestore currently comes back 429.

What it reports, per collection
-------------------------------
  documents        rows in friendbook_cf_documents
  table            rows in the normalised table
  only_in_docs     ids the app can render that the table does not have
  only_in_table    ids the table has that the app CANNOT render — the recoverable gap
  in_both          ids present in both shapes

Read-only. Two SELECTs per collection, no writes, no Firestore.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.cloudflare.com/client/v4"

# collection name -> (table, primary key column)
SHAPES = [
    ("users", "users", "uid"),
    ("posts", "posts", "id"),
    ("comments", "comments", "id"),
    ("chats", "chats", "id"),
    ("messages", "messages", "id"),
    ("groups", "groups", "id"),
    ("notifications", "notifications", "id"),
    ("stories", "stories", "id"),
    ("reels", "reels", "id"),
    ("friend_requests", "friend_requests", "id"),
]


def d1_query(account_id: str, database_id: str, token: str, sql: str) -> list[dict]:
    url = f"{API}/accounts/{account_id}/d1/database/{database_id}/query"
    request = urllib.request.Request(
        url,
        data=json.dumps({"sql": sql}).encode("utf-8"),
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not payload.get("success", False):
        errors = payload.get("errors") or [{"message": "unknown error"}]
        raise RuntimeError("; ".join(str(e.get("message", e)) for e in errors))
    result = payload.get("result") or []
    return (result[0].get("results") or []) if result else []


def main() -> int:
    token = os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()
    account_id = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "").strip()
    database_id = os.environ.get("D1_DATABASE_ID", "").strip()
    if not (token and account_id and database_id):
        print("::error::CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID and D1_DATABASE_ID are required")
        return 1

    rows_out = []
    totals = {"documents": 0, "table": 0, "only_in_docs": 0, "only_in_table": 0, "in_both": 0}

    # Failures here are printed as ::error:: so they survive into the check-run annotations;
    # this repository's log download comes back empty, so a bare traceback is invisible.
    for collection, table, pk in SHAPES:
        try:
            doc_rows = d1_query(
                account_id, database_id, token,
                f"SELECT doc_id AS id FROM friendbook_cf_documents WHERE collection = '{collection}'",
            )
        except Exception as error:  # noqa: BLE001
            print(f"::error::{collection}: document store unreadable - {type(error).__name__}: {error}")
            continue

        doc_ids = {str(r["id"]) for r in doc_rows if r.get("id") is not None}

        try:
            table_rows = d1_query(
                account_id, database_id, token,
                f"SELECT {pk} AS id FROM {table}",
            )
            table_ids = {str(r["id"]) for r in table_rows if r.get("id") is not None}
        except Exception:  # noqa: BLE001 - a missing table is a finding, not a crash
            table_ids = set()

        only_docs = doc_ids - table_ids
        only_table = table_ids - doc_ids
        both = doc_ids & table_ids

        totals["documents"] += len(doc_ids)
        totals["table"] += len(table_ids)
        totals["only_in_docs"] += len(only_docs)
        totals["only_in_table"] += len(only_table)
        totals["in_both"] += len(both)

        rows_out.append({
            "collection": collection,
            "documents": len(doc_ids),
            "table": len(table_ids),
            "only_in_docs": len(only_docs),
            "only_in_table": len(only_table),
            "in_both": len(both),
            # A sample, so the shape of the gap is visible without dumping thousands of ids.
            "only_in_table_sample": sorted(only_table)[:5],
        })

    if not rows_out:
        print("::error::no collection could be read; the comparison did not run")
        return 1

    for row in rows_out:
        print(
            f"::notice::{row['collection']}: documents {row['documents']}, "
            f"table {row['table']}, in both {row['in_both']}, "
            f"ONLY IN TABLE (not renderable) {row['only_in_table']}, "
            f"only in docs {row['only_in_docs']}"
        )

    print(
        f"::notice::TOTALS: documents {totals['documents']}, table {totals['table']}, "
        f"in both {totals['in_both']}, only in table {totals['only_in_table']}"
    )

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as handle:
            handle.write("## D1 coverage: documents vs normalised tables\n\n")
            handle.write("| Collection | Documents | Table | In both | Only in table | Only in docs |\n")
            handle.write("|---|---|---|---|---|---|\n")
            for row in rows_out:
                handle.write(
                    f"| {row['collection']} | {row['documents']} | {row['table']} | "
                    f"{row['in_both']} | {row['only_in_table']} | {row['only_in_docs']} |\n"
                )
            handle.write(
                f"| **Total** | **{totals['documents']}** | **{totals['table']}** | "
                f"**{totals['in_both']}** | **{totals['only_in_table']}** "
                f"| **{totals['only_in_docs']}** |\n"
            )

    with open("d1-coverage.json", "w", encoding="utf-8") as handle:
        json.dump({"totals": totals, "collections": rows_out}, handle, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
