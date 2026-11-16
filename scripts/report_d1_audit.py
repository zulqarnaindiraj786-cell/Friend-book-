#!/usr/bin/env python3
"""Turn the D1 audit JSON into annotations a human can read in the Actions summary.

The endpoint reports, per table, how many rows exist and the oldest and newest `updated_at`.
That is what separates "the app is writing here" from "this is a snapshot of an old copy":
a table being written to has a newest row minutes old, while a one-time copy has every row
older than the copy itself.

Output goes through `::notice::` because plain stdout from a workflow step is not visible in
the check-run annotations, and log downloads from this repository come back empty.

Read-only: parses a file, prints lines.
"""

from __future__ import annotations

import json
import sys

# Ordered so the summary reads like the app's screens: identity, content, conversation.
COLLECTION_ORDER = [
    "users",
    "posts",
    "comments",
    "chats",
    "messages",
    "groups",
    "notifications",
    "stories",
    "reels",
]


def _age_phrase(minutes: object) -> str:
    if not isinstance(minutes, int):
        return "age unknown"
    if minutes < 0:
        return "clock skew (newest row is in the future)"
    if minutes < 60:
        return f"{minutes} min old"
    if minutes < 60 * 48:
        return f"{minutes // 60} h old"
    return f"{minutes // (60 * 24)} d old"


def _emit_recency(name: str, info: dict) -> bool:
    """Print one row and report whether it looks live (written within a day)."""
    if "error" in info:
        print(f"::warning::{name}: {info['error']}")
        return False
    if "total" not in info:
        return False
    age = info.get("newestAgeMinutes")
    print(
        f"::notice::{name}: {info.get('total')} rows | newest {info.get('newest')} "
        f"({_age_phrase(age)}) | oldest {info.get('oldest')}"
    )
    return isinstance(age, int) and 0 <= age <= 60 * 24


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: report_d1_audit.py <audit.json>", file=sys.stderr)
        return 2

    try:
        with open(argv[1], encoding="utf-8") as handle:
            data = json.load(handle)
    except Exception as error:  # noqa: BLE001 - a broken payload must not fail the job loudly
        print(f"::error::could not read audit JSON: {error}")
        return 1

    if not isinstance(data, dict):
        print("::error::audit returned something other than a JSON object")
        return 1

    server_time = data.get("serverTime", "unknown time")
    print(f"::notice::D1 audited at {server_time}")

    totals = data.get("totals")
    if isinstance(totals, dict):
        _emit_recency("all documents", totals)

    fresh, stale = [], []
    known = [name for name in COLLECTION_ORDER if name in data]
    rest = [
        name
        for name in data
        if name not in COLLECTION_ORDER
        and name not in {"serverTime", "totals", "projections"}
    ]
    for name in known + sorted(rest):
        info = data.get(name)
        if not isinstance(info, dict):
            continue
        if _emit_recency(name, info):
            fresh.append(name)
        elif info.get("total"):
            stale.append(name)

    projections = data.get("projections")
    if isinstance(projections, dict):
        parts = []
        for table, info in projections.items():
            if isinstance(info, dict) and "total" in info:
                parts.append(f"{table}={info['total']}")
        if parts:
            print("::notice::projection row counts (no timestamps): " + ", ".join(parts))

    if fresh:
        print("::notice::written to within the last day: " + ", ".join(fresh))
    if stale:
        print("::notice::no writes in the last day: " + ", ".join(stale))
    if not fresh and stale:
        print(
            "::notice::nothing in D1 has been written to in the last day — "
            "it looks like a finished copy, not the live store"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
touch
