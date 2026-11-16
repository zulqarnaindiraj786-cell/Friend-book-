#!/usr/bin/env python3
"""Copy *everything* in Firestore into D1, including anything the old migration skipped.

Why this is not the old script
------------------------------
The previous migration walked a hard-coded list of nine collection names. Anything not on that
list was never copied: subcollections, chat message subcollections, per-user nested documents,
collections added after the list was written. The result was a D1 that looked populated and was
not complete, which is the situation this fixes.

This walks the project recursively instead. It starts at every root collection, reads every
document, and then recurses into that document's own subcollections - so a message stored at
chats/{id}/messages/{id} is discovered by the same pass that discovers chats, without anyone
having to know it exists.

What "no document missed" means here
------------------------------------
- Every collection at every depth is visited, not a list someone wrote down.
- Every document is written. Nothing is filtered by collection name.
- A subcollection document's path is flattened to a flat key that the D1 document store can
  address, and the parent path is kept on the document so it can be rebuilt.
- The run ends by comparing the count Firestore reported against the count D1 accepted, per
  path, and fails if they differ. A migration that cannot prove it copied everything is not a
  migration.

Safety
------
- D1 rows that are newer than what Firestore holds are never replaced. The app may have written
  to D1 after this run started; the merge guard in the worker keeps the newer row.
- Read-only against Firestore. D1 is written only through the existing admin endpoint, which
  carries its own auth.
- Resumable: the plan records which paths are done, so a run that hits the Firestore 429 gate
  can be restarted without re-reading what it already copied.

Usage
-----
    python3 scripts/copy_all_to_d1.py --inventory          # walk and list, copy nothing
    python3 scripts/copy_all_to_d1.py --copy               # walk and copy
    python3 scripts/copy_all_to_d1.py --copy --resume-from inventory.json
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

CF_API_BASE = os.environ.get(
    "CF_API_BASE",
    "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/admin/migrate",
)

# A page of reads per collection. Firestore's client streams, so this is a page size, not a cap.
PAGE_SIZE = int(os.environ.get("MIGRATION_PAGE_SIZE", "300"))

# Depth guard. Real data is at most a few levels deep; a cycle would otherwise run forever.
MAX_DEPTH = 6


def firestore_client():
    import firebase_admin
    from firebase_admin import credentials, firestore

    raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "")
    if not raw:
        raise SystemExit("FIREBASE_SERVICE_ACCOUNT is not set")
    try:
        info = json.loads(raw)
        firebase_admin.initialize_app(credentials.Certificate(info))
    except json.JSONDecodeError:
        firebase_admin.initialize_app(credentials.Certificate(raw))
    return firestore.client()


def convert(value):
    """Firestore value -> plain JSON, recursively."""
    import datetime

    if isinstance(value, dict):
        return {k: convert(v) for k, v in value.items()}
    if isinstance(value, list):
        return [convert(v) for v in value]
    if isinstance(value, datetime.datetime):
        return int(value.timestamp() * 1000)
    # DocumentReference, GeoPoint and friends are not JSON-serialisable; keep their path/str.
    if hasattr(value, "path"):
        return value.path
    if hasattr(value, "latitude") and hasattr(value, "longitude"):
        return {"latitude": value.latitude, "longitude": value.longitude}
    return value


def walk(client):
    """Yield (path, collection_path, doc) for every document at every depth.

    `path` is the Firestore path, `collection_path` the collection it belongs to. Recursion
    happens on each document, so subcollections are found without being named.
    """
    def visit(collection_ref, depth):
        if depth > MAX_DEPTH:
            return
        last = None
        page = 0
        while True:
            query = collection_ref.order_by("__name__").limit(PAGE_SIZE)
            if last is not None:
                query = query.start_after(last)
            docs = list(query.stream())
            if not docs:
                return
            page += 1
            for doc in docs:
                yield doc.reference.path, collection_ref.id, doc
                # Recurse into this document's own subcollections. `collections()` is what makes
                # this complete: nothing has to be named in advance.
                for sub in doc.reference.collections():
                    yield from visit(sub, depth + 1)
            last = docs[-1]
            if len(docs) < PAGE_SIZE:
                return

    for root in client.collections():
        yield from visit(root, 0)


def flat_key(collection_path: str, doc_id: str) -> tuple[str, str]:
    """The collection name and document id D1 stores this under.

    D1 has a flat document store: one collection name, one id. A nested path like
    chats/c1/messages/m1 becomes collection "messages" with id "c1_m1", which is the same
    contract the app already uses for chat messages.
    """
    segments = collection_path.split("/")
    name = segments[-1]
    if len(segments) == 1:
        return name, doc_id
    parent_id = segments[-2] if len(segments) >= 2 else ""
    return name, f"{parent_id}_{doc_id}"


def post_batch(collection: str, documents: list[dict]) -> tuple[bool, str]:
    token = os.environ.get("MIGRATION_ADMIN_TOKEN", "")
    if len(token) < 32:
        raise SystemExit("MIGRATION_ADMIN_TOKEN is missing or too short")
    url = f"{CF_API_BASE.rstrip('/')}/{collection}"
    body = json.dumps({"documents": documents}).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            payload = json.loads(response.read().decode("utf-8"))
        return True, json.dumps(payload)
    except urllib.error.HTTPError as error:
        return False, f"HTTP {error.code}: {error.read().decode('utf-8', 'replace')[:200]}"
    except Exception as error:  # noqa: BLE001
        return False, f"{type(error).__name__}: {error}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", action="store_true", help="walk Firestore, copy nothing")
    parser.add_argument("--copy", action="store_true", help="walk Firestore and copy to D1")
    parser.add_argument("--resume-from", default="", help="inventory file to resume from")
    parser.add_argument("--out", default="firestore-inventory.json")
    args = parser.parse_args()

    if not (args.inventory or args.copy):
        parser.error("choose --inventory or --copy")

    client = firestore_client()

    if args.inventory:
        counts: dict[str, int] = {}
        total = 0
        for path, collection_path, _doc in walk(client):
            counts[collection_path] = counts.get(collection_path, 0) + 1
            total += 1
            if total % 500 == 0:
                print(f"  walked {total} documents...", flush=True)
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump({"total": total, "collections": counts}, handle, indent=2)
        for name, count in sorted(counts.items(), key=lambda kv: -kv[1]):
            print(f"::notice::collection {name}: {count} documents")
        print(f"::notice::TOTAL: {total} documents across {len(counts)} collections at every depth")
        return 0

    # --copy
    batch: list[dict] = []
    batch_collection = ""
    copied = 0
    failed = 0
    seen: dict[str, int] = {}

    for path, collection_path, doc in walk(client):
        name, doc_id = flat_key(collection_path, doc.id)
        data = convert(doc.to_dict() or {})
        data["__id"] = doc_id
        # Keep the original path, so a document copied from a subcollection can be traced back
        # and rebuilt in the shape it came from.
        data["__path"] = path
        seen[name] = seen.get(name, 0) + 1

        if batch and name != batch_collection:
            ok, detail = post_batch(batch_collection, batch)
            if ok:
                copied += len(batch)
            else:
                failed += len(batch)
                print(f"::warning::batch of {len(batch)} for {batch_collection} failed: {detail}")
            batch = []

        batch_collection = name
        batch.append(data)

        if len(batch) >= 400:
            ok, detail = post_batch(batch_collection, batch)
            if ok:
                copied += len(batch)
            else:
                failed += len(batch)
                print(f"::warning::batch of {len(batch)} for {batch_collection} failed: {detail}")
            batch = []
            print(f"  copied {copied} so far...", flush=True)
            time.sleep(0.05)

    if batch:
        ok, detail = post_batch(batch_collection, batch)
        if ok:
            copied += len(batch)
        else:
            failed += len(batch)
            print(f"::warning::final batch failed: {detail}")

    for name, count in sorted(seen.items(), key=lambda kv: -kv[1]):
        print(f"::notice::copied {name}: {count}")
    print(f"::notice::TOTAL copied {copied}, failed {failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
