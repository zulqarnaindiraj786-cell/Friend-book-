#!/usr/bin/env python3
"""Work out which Firestore documents D1 does not already have.

Why a plan and not a copy
-------------------------
D1 is the live store: the app writes to it, so it holds documents Firestore has never seen and
edits Firestore never received. Re-sending everything would overwrite those. Re-sending nothing
leaves the documents that only ever existed in Firestore missing, which is what the app shows as
missing old content.

So the operation has to be per document: copy the ones D1 has never had, leave the ones it
already has alone. This script answers the first half — what is in Firestore that D1 lacks —
and writes it to a file. It writes nothing to either store.

Existence is checked against both of D1's shapes. Earlier runs wrote only the normalised tables
(users, posts, ...) while the app reads friendbook_cf_documents, so a document can be present in
one and absent from the other. Treating "absent from the document store" as "missing" would
re-copy data D1 already has.

Output: a JSON plan, one entry per collection, listing the ids to copy with their reasons.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_COLLECTIONS = [
    "users", "posts", "comments", "chats", "groups", "friend_requests",
    "notifications", "stories", "reels", "messages",
]

# The worker accepts at most 5000 ids per existence check.
CHUNK = 2000


def worker_url(path: str) -> str:
    base = os.environ.get(
        "CF_API_BASE", "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/admin/migrate"
    )
    return f"{base.rstrip('/')}/{path.lstrip('/')}"


def worker_post(path: str, payload: dict) -> dict:
    token = os.environ.get("MIGRATION_ADMIN_TOKEN", "")
    if len(token) < 32:
        raise SystemExit(
            "MIGRATION_ADMIN_TOKEN is missing or shorter than 32 characters; "
            "the worker refuses every migration route without it."
        )
    request = urllib.request.Request(
        worker_url(path),
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")[:300]
        raise RuntimeError(f"HTTP {error.code}: {body}") from error


def firestore_ids(client, collection: str, limit: int | None) -> list[str]:
    """Every document id in one Firestore collection, in a stable order."""
    query = client.collection(collection).order_by("__name__")
    if limit:
        query = query.limit(limit)
    return [doc.id for doc in query.stream()]


def plan(collection: str, ids: list[str]) -> dict:
    """Ask the worker which of these ids D1 already has."""
    missing: list[str] = []
    existing: list[str] = []
    for start in range(0, len(ids), CHUNK):
        slice_ids = ids[start:start + CHUNK]
        answer = worker_post("existing", {"collection": collection, "ids": slice_ids})
        missing.extend(answer.get("missing", []))
        existing.extend(answer.get("existing", []))
    return {
        "collection": collection,
        "firestore": len(ids),
        "present": len(existing),
        "missing": len(missing),
        "missingIds": missing,
    }


def main(argv: list[str]) -> int:
    import firebase_admin
    from firebase_admin import credentials, firestore

    limit_raw = os.environ.get("MIGRATION_MAX_DOCS", "0")
    limit = int(limit_raw) if limit_raw.isdigit() and int(limit_raw) > 0 else None

    only_raw = os.environ.get("MIGRATION_ONLY", "")
    collections = [c.strip() for c in only_raw.split(",") if c.strip()] or DEFAULT_COLLECTIONS

    service_account = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "")
    if not service_account:
        raise SystemExit("FIREBASE_SERVICE_ACCOUNT is not set")
    try:
        info = json.loads(service_account)
        firebase_admin.initialize_app(credentials.Certificate(info))
    except json.JSONDecodeError:
        firebase_admin.initialize_app(credentials.Certificate(service_account))
    client = firestore.client()

    report = []
    for collection in collections:
        try:
            ids = firestore_ids(client, collection, limit)
        except Exception as error:  # noqa: BLE001 - a read refusal is a finding, not a crash
            print(f"::warning::{collection}: could not read Firestore - {type(error).__name__}: {error}")
            report.append({"collection": collection, "error": f"{type(error).__name__}"})
            continue

        entry = plan(collection, ids)
        report.append(entry)
        print(
            f"::notice::{collection}: Firestore {entry['firestore']}, "
            f"already in D1 {entry['present']}, missing {entry['missing']}"
        )

    totals = {
        "firestore": sum(e.get("firestore", 0) for e in report),
        "present": sum(e.get("present", 0) for e in report),
        "missing": sum(e.get("missing", 0) for e in report),
    }
    print(
        f"::notice::PLAN: {totals['firestore']} documents in Firestore, "
        f"{totals['present']} already in D1, {totals['missing']} to copy"
    )

    out_path = os.environ.get("MIGRATION_PLAN_PATH", "migration-plan.json")
    with open(out_path, "w", encoding="utf-8") as handle:
        json.dump({"totals": totals, "collections": report}, handle, indent=2)
    print(f"plan written to {out_path}")

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as handle:
            handle.write("## Migration plan\n\n")
            handle.write("| Collection | In Firestore | Already in D1 | To copy |\n")
            handle.write("|---|---|---|---|\n")
            for entry in report:
                if "error" in entry:
                    handle.write(f"| {entry['collection']} | unreadable | – | – |\n")
                    continue
                handle.write(
                    f"| {entry['collection']} | {entry['firestore']} | "
                    f"{entry['present']} | {entry['missing']} |\n"
                )
            handle.write(
                f"| **Total** | **{totals['firestore']}** | **{totals['present']}** "
                f"| **{totals['missing']}** |\n"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
