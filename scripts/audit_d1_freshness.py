#!/usr/bin/env python3
"""Ask D1 directly when it was last written to.

Why this exists
---------------
The app is pinned to D1 (`CLOUDFLARE_DATA_ENABLED = true`), and D1 holds more documents than
Firestore reports. That rules out "some documents are missing", but it does not rule out "D1
is a stale copy that nobody has written to since it was made". Those two situations produce
identical row counts and require opposite decisions, so the only thing that separates them is
a timestamp.

`friendbook_cf_documents` is where writes are recorded: the document API stamps `updated_at`
on every put. The per-feature tables (users, posts, chats, ...) are projections written by the
copy and have no timestamp column at all, so they are counted for coverage and nothing else.

What it prints
--------------
One `::notice::` line per collection with its row count, newest and oldest `updated_at`, and
whether the newest row is from today. Then a verdict line. `::notice::` rather than `print()`
because this repository's CI log download is broken — the check-run annotations are the only
channel that reliably shows step output.

Read-only: it submits SELECT statements and nothing else.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.cloudflare.com/client/v4"

# Counted for coverage only. None of these has an updated_at column.
PROJECTION_TABLES = [
    "users",
    "posts",
    "chats",
    "groups",
    "friend_requests",
    "notifications",
    "stories",
    "reels",
    "comments",
    "messages",
    "active_calls",
]


def d1_query(account_id: str, database_id: str, token: str, sql: str) -> list[dict]:
    """Run one read-only statement through the D1 REST API."""
    url = f"{API}/accounts/{account_id}/d1/database/{database_id}/query"
    body = json.dumps({"sql": sql}).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=45) as response:
        payload = json.loads(response.read().decode("utf-8"))

    if not payload.get("success", False):
        errors = payload.get("errors") or [{"message": "unknown error"}]
        raise RuntimeError("; ".join(str(e.get("message", e)) for e in errors))

    result = payload.get("result") or []
    if not result:
        return []
    return result[0].get("results") or []


def age_phrase(minutes: int | None) -> str:
    if minutes is None:
        return "never written"
    if minutes < 0:
        return "clock skew (newest row is in the future)"
    if minutes < 60:
        return f"{minutes} min old"
    if minutes < 60 * 48:
        return f"{minutes // 60} h old"
    return f"{minutes // (60 * 24)} d old"


def main() -> int:
    token = os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()
    account_id = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "").strip()
    database_id = os.environ.get("D1_DATABASE_ID", "").strip()

    if not token:
        print("::error::CLOUDFLARE_API_TOKEN is not available to this workflow")
        return 1
    if not account_id or not database_id:
        print("::error::account id or database id is missing")
        return 1

    # 1. Per-collection recency, from the one table that records writes.
    try:
        rows = d1_query(
            account_id,
            database_id,
            token,
            "SELECT collection, COUNT(*) AS total, "
            "MIN(updated_at) AS oldest, MAX(updated_at) AS newest "
            "FROM friendbook_cf_documents GROUP BY collection ORDER BY total DESC",
        )
    except (urllib.error.URLError, RuntimeError, TimeoutError) as error:
        print(f"::error::could not query friendbook_cf_documents: {error}")
        return 1

    if not rows:
        print("::error::friendbook_cf_documents returned no rows")
        return 1

    # Server time comes from D1 itself so the ages do not depend on the runner's clock.
    try:
        clock = d1_query(account_id, database_id, token, "SELECT CAST(strftime('%s','now') AS INTEGER) AS now_s")
        now_ms = int(clock[0]["now_s"]) * 1000
    except Exception:  # noqa: BLE001 - fall back rather than fail a read-only report
        import time

        now_ms = int(time.time() * 1000)

    print(f"::notice::D1 audited at {now_ms} ms epoch (server clock)")

    import datetime

    def iso(ms: object) -> str:
        try:
            value = int(ms)  # type: ignore[arg-type]
        except (TypeError, ValueError):
            return "unknown"
        if value <= 0:
            return "unknown"
        return datetime.datetime.fromtimestamp(value / 1000, datetime.timezone.utc).isoformat(timespec="seconds")

    fresh: list[str] = []
    stale: list[str] = []
    total_rows = 0
    newest_overall = 0
    oldest_overall = 0

    for row in rows:
        collection = row.get("collection", "?")
        total = int(row.get("total") or 0)
        oldest = int(row.get("oldest") or 0)
        newest = int(row.get("newest") or 0)
        total_rows += total
        newest_overall = max(newest_overall, newest)
        oldest_overall = min(oldest_overall, oldest) if oldest_overall else oldest

        minutes = int((now_ms - newest) / 60000) if newest > 0 else None
        print(
            f"::notice::{collection}: {total} docs | newest {iso(newest)} "
            f"({age_phrase(minutes)}) | oldest {iso(oldest)}"
        )
        if minutes is not None and 0 <= minutes <= 60 * 24:
            fresh.append(collection)
        elif total:
            stale.append(collection)

    total_minutes = int((now_ms - newest_overall) / 60000) if newest_overall > 0 else None
    print(
        f"::notice::ALL COLLECTIONS: {total_rows} docs | newest {iso(newest_overall)} "
        f"({age_phrase(total_minutes)}) | oldest {iso(oldest_overall)}"
    )

    # 2. Coverage of the projection tables, which carry no timestamps.
    parts = []
    for table in PROJECTION_TABLES:
        try:
            result = d1_query(account_id, database_id, token, f"SELECT COUNT(*) AS n FROM {table}")
            parts.append(f"{table}={int(result[0]['n'])}")
        except Exception as error:  # noqa: BLE001 - a missing table is a finding, not a crash
            parts.append(f"{table}=?")
            print(f"::warning::{table}: {error}")
    print("::notice::projection row counts: " + ", ".join(parts))

    # 3. The verdict, stated plainly so the log answers the question on its own.
    if fresh:
        print(
            "::notice::VERDICT: D1 is being written to now — "
            + ", ".join(sorted(fresh))
            + " each have a document newer than 24 h. This is the live store."
        )
    else:
        print(
            "::notice::VERDICT: no collection has been written to in the last 24 h. "
            "D1 looks like a finished copy rather than the live store."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
