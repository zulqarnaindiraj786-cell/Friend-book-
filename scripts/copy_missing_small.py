#!/usr/bin/env python3
"""Copy what is missing from Firestore in small pages, and never scan a collection.

Why this is a different shape from every previous attempt
---------------------------------------------------------
Earlier runs asked Firestore for whole collections, ordered by document name, and walked them.
One collection large enough to matter exhausted the daily read allowance before reaching the
end, and the next attempt started over from the beginning. Nothing was gained, and the reads
were spent again.

This asks a different question per page: "the next 20 documents after this cursor". It records
where it stopped in a progress file, so a run that hits the limit tomorrow resumes from that
cursor instead of re-reading what was already copied. The read cost of a run is therefore
proportional to what it actually copied, not to the size of the collections.

Progress is kept per collection:
    {"posts": {"cursor": "<doc name>", "copied": 4211}}

A collection is done when a page comes back with fewer than LIMIT documents; its cursor is then
cleared and it is skipped on later runs.

Usage
-----
    python3 scripts/copy_missing_small.py --plan          # show what is left, copy nothing
    python3 scripts/copy_missing_small.py --copy          # copy up to --pages pages per collection
    python3 scripts/copy_missing_small.py --copy --pages 50
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import d1_direct

PROGRESS_PATH = "firestore-copy-progress.json"

# Twenty documents per page, as specified. Small enough that one page cannot be the thing that
# exhausts the allowance, and stable under concurrent writes because it is ordered by name.
LIMIT = int(os.environ.get("COPY_PAGE_LIMIT", "20"))

# The collections the app reads. Anything not listed is still copied if it exists; this list
# only decides the order, so the screens people use fill first.
ORDER = [
    "posts", "comments", "users", "chats", "messages",
    "notifications", "groups", "friend_requests", "stories", "reels",
]


def load_progress() -> dict:
    if os.path.exists(PROGRESS_PATH):
        with open(PROGRESS_PATH, encoding="utf-8") as handle:
            return json.load(handle)
    return {}


def save_progress(progress: dict) -> None:
    with open(PROGRESS_PATH, "w", encoding="utf-8") as handle:
        json.dump(progress, handle, indent=2)


def client():
    import firebase_admin
    from firebase_admin import credentials, firestore

    raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "")
    if not raw:
        raise SystemExit("FIREBASE_SERVICE_ACCOUNT is not set")
    try:
        firebase_admin.initialize_app(credentials.Certificate(json.loads(raw)))
    except json.JSONDecodeError:
        firebase_admin.initialize_app(credentials.Certificate(raw))
    return firestore.client()


def ordered_collections(fs):
    present = {c.id for c in fs.collections()}
    head = [name for name in ORDER if name in present]
    tail = sorted(present - set(head))
    return head + tail


def next_page(fs, collection: str, cursor: str | None):
    """One page of at most LIMIT documents, resuming after `cursor`."""
    query = fs.collection(collection).order_by("__name__")
    if cursor:
        from google.cloud.firestore_v1 import DocumentReference

        query = query.start_after(fs.collection(collection).document(cursor))
    return list(query.limit(LIMIT).stream())


def to_document(doc) -> dict:
    data = doc.to_dict() or {}
    data["__id"] = doc.id
    return data


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", action="store_true", help="report what is left")
    parser.add_argument("--copy", action="store_true", help="copy pages")
    parser.add_argument("--pages", type=int, default=25, help="pages per collection this run")
    args = parser.parse_args()

    if not (args.plan or args.copy):
        parser.error("choose --plan or --copy")

    fs = client()
    progress = load_progress()
    collections = ordered_collections(fs)

    if args.plan:
        for name in collections:
            done = progress.get(name, {}).get("done")
            copied = progress.get(name, {}).get("copied", 0)
            # A plan costs one page read per collection - the cheapest way to see what is left
            # without walking anything.
            page = next_page(fs, name, None) if not done else []
            state = "done" if done else ("has more" if len(page) >= LIMIT else f"{len(page)} total")
            print(f"::notice::{name}: {state}, copied so far {copied}")
        return 0

    total_copied = 0
    for name in collections:
        entry = progress.setdefault(name, {"cursor": None, "copied": 0})
        if entry.get("done"):
            continue

        for page_number in range(args.pages):
            try:
                docs = next_page(fs, name, entry.get("cursor"))
            except Exception as error:  # noqa: BLE001
                # A refusal stops this collection, not the run. The cursor is already saved,
                # so tomorrow resumes exactly here instead of starting over.
                print(f"::warning::{name}: stopped at page {page_number + 1} - {type(error).__name__}")
                save_progress(progress)
                break

            if not docs:
                entry["done"] = True
                print(f"::notice::{name}: finished, {entry['copied']} copied in total")
                break

            batch = [to_document(doc) for doc in docs]
            for doc in batch:
                doc["__id"] = doc.get("__id")
            written = d1_direct.insert_documents(name, batch)
            entry["copied"] = entry.get("copied", 0) + written
            total_copied += written
            entry["cursor"] = docs[-1].id

            if len(docs) < LIMIT:
                entry["done"] = True
                save_progress(progress)
                print(f"::notice::{name}: finished, {entry['copied']} copied in total")
                break

            save_progress(progress)
            print(f"  {name}: {entry['copied']} copied, cursor {entry['cursor']}")

        save_progress(progress)

    print(f"::notice::this run copied {total_copied} documents")
    remaining = [name for name in collections if not progress.get(name, {}).get("done")]
    if remaining:
        print(f"::notice::still to come: {', '.join(remaining)}")
    else:
        print("::notice::every collection has been walked to the end")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
