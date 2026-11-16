#!/usr/bin/env python3
"""Prove that every Firestore document reached D1, or name the ones that did not.

A migration is only finished when the destination can be checked against the source. Counting
"documents we sent" proves nothing: a batch that silently failed, a collection never walked, or
a document whose id collided would all report success. This compares the two sides directly.

Two passes:

  --inventory   walks Firestore, records every document path and the flat key it maps to, writes
                firestore-inventory.json. No D1 traffic beyond one existence check at the end.
  --verify      reads that inventory, asks D1 which of those keys exist, and lists every one that
                does not. Exit code 1 if anything is missing.

The existence check is the same endpoint the selective copy uses, batched, so this costs one
query per 2,000 documents rather than one per document.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

CF_API_BASE = os.environ.get(
    "CF_API_BASE",
    "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/admin/migrate",
)
CHUNK = 2000


def firestore_walk():
    from copy_all_to_d1 import firestore_client, walk

    return firestore_client(), walk


def worker_post(path: str, payload: dict) -> dict:
    token = os.environ.get("MIGRATION_ADMIN_TOKEN", "")
    if len(token) < 32:
        raise SystemExit("MIGRATION_ADMIN_TOKEN is missing or too short")
    request = urllib.request.Request(
        f"{CF_API_BASE.rstrip('/')}/{path.lstrip('/')}",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")[:300]
        raise RuntimeError(f"HTTP {error.code}: {body}") from error


def cmd_inventory(argv: list[str]) -> int:
    from copy_all_to_d1 import flat_key

    client, walk = firestore_walk()
    entries = []
    for path, collection_path, doc in walk(client):
        name, doc_id = flat_key(collection_path, doc.id)
        entries.append({"path": path, "collection": name, "id": doc_id})

    with open("firestore-inventory.json", "w", encoding="utf-8") as handle:
        json.dump({"count": len(entries), "documents": entries}, handle)

    by_collection: dict[str, int] = {}
    for entry in entries:
        by_collection[entry["collection"]] = by_collection.get(entry["collection"], 0) + 1
    for name, count in sorted(by_collection.items(), key=lambda kv: -kv[1]):
        print(f"::notice::firestore {name}: {count}")
    print(f"::notice::FIRESTORE TOTAL: {len(entries)} documents, {len(by_collection)} collections")
    return 0


def cmd_verify(argv: list[str]) -> int:
    inventory_path = "firestore-inventory.json"
    with open(inventory_path, encoding="utf-8") as handle:
        inventory = json.load(handle)

    by_collection: dict[str, list[str]] = {}
    for entry in inventory["documents"]:
        by_collection.setdefault(entry["collection"], []).append(entry["id"])

    missing_total = 0
    checked_total = 0
    for name, ids in sorted(by_collection.items()):
        missing: list[str] = []
        for start in range(0, len(ids), CHUNK):
            slice_ids = ids[start:start + CHUNK]
            try:
                answer = worker_post("existing", {"collection": name, "ids": slice_ids})
            except RuntimeError as error:
                print(f"::error::{name}: existence check failed - {error}")
                return 1
            missing.extend(answer.get("missing", []))
        checked_total += len(ids)
        missing_total += len(missing)
        level = "error" if missing else "notice"
        print(f"::{level}::{name}: {len(ids)} in Firestore, {len(missing)} missing from D1")
        for doc_id in missing[:10]:
            print(f"::notice::  missing {name}/{doc_id}")

    print(
        f"::notice::VERIFIED {checked_total - missing_total} of {checked_total} documents present"
    )
    if missing_total:
        print(f"::error::{missing_total} documents are in Firestore but not in D1")
        return 1
    print("::notice::COMPLETE: every Firestore document is present in D1")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("inventory")
    sub.add_parser("verify")
    args = parser.parse_args()

    if args.command == "inventory":
        return cmd_inventory(sys.argv[2:])
    return cmd_verify(sys.argv[2:])


if __name__ == "__main__":
    raise SystemExit(main())
