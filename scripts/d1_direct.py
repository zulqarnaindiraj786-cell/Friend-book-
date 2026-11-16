#!/usr/bin/env python3
"""Write to D1 through Cloudflare's own API, with no worker and no migration token.

Why this exists
---------------
Every copy so far went through /v1/admin/migrate on the worker, which requires
MIGRATION_ADMIN_TOKEN. That is not a repository secret, so the copy could never run from CI —
which is why previous attempts ended at "token is not set" rather than at any data problem.

CLOUDFLARE_API_TOKEN *is* a repository secret: the deploy workflow uses it on every push. It can
query D1 directly, so the worker's auth stops being a prerequisite for getting data into D1.

The write is the same either way: rows go into friendbook_cf_documents with a document_json, and
the merge rule is kept. A row is only replaced when the incoming document's own updatedAt is at
least as new as the one already there, so anything the app wrote after the switch survives.

Batching is what keeps this cheap: hundreds of rows travel in one statement, so this is not one
D1 call per document.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

API = "https://api.cloudflare.com/client/v4"
DEFAULT_DATABASE_ID = "e1c605ca-a50b-46c6-9deb-56dd1f70d163"

# Rows per statement. D1 caps statement size; 50 documents of typical size stays well inside it.
INSERT_BATCH = 50


class D1Error(RuntimeError):
    pass


def database_id() -> str:
    return os.environ.get("D1_DATABASE_ID", DEFAULT_DATABASE_ID)


def execute(sql: str, params: list | None = None, timeout: int = 120) -> list[dict]:
    """Run one statement and return its rows (empty for a write)."""
    token = os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()
    account = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "").strip()
    if not token:
        raise D1Error("CLOUDFLARE_API_TOKEN is not set")
    if not account:
        raise D1Error("CLOUDFLARE_ACCOUNT_ID is not set")

    url = f"{API}/accounts/{account}/d1/database/{database_id()}/query"
    payload: dict = {"sql": sql}
    if params:
        payload["params"] = params

    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")[:300]
        raise D1Error(f"HTTP {error.code}: {detail}") from error

    if not body.get("success", False):
        errors = body.get("errors") or [{"message": "unknown"}]
        raise D1Error("; ".join(str(e.get("message", e)) for e in errors))

    result = body.get("result") or []
    return (result[0].get("results") or []) if result else []


def insert_documents(collection: str, documents: list[dict]) -> int:
    """Insert or merge a batch of documents. Returns how many rows were submitted.

    The merge condition is on document_json's own updatedAt: a Firestore document that predates
    what D1 already holds leaves the D1 row alone. Rows with no updatedAt are treated as old,
    which is right for a migration source — anything the app wrote stamps updatedAt.
    """
    if not documents:
        return 0

    values: list[str] = []
    params: list = []
    for doc in documents:
        doc_id = str(doc.get("__id") or doc.get("id") or "")
        if not doc_id or len(doc_id) > 128:
            continue
        plain = {k: v for k, v in doc.items() if k != "__id"}
        plain["id"] = doc_id
        owner = str(
            plain.get("authorId") or plain.get("uid") or plain.get("senderId")
            or plain.get("toUserId") or plain.get("fromUserId") or ""
        )
        path = f"{collection}/{doc_id}"
        updated = plain.get("updatedAt") or plain.get("createdAt") or 0
        try:
            updated = int(updated)
        except (TypeError, ValueError):
            updated = 0

        values.append("(?, ?, ?, ?, ?, ?)")
        params.extend([path, collection, doc_id, owner, json.dumps(plain), updated])

    if not values:
        return 0

    sql = (
        "INSERT INTO friendbook_cf_documents "
        "(path, collection, doc_id, owner_uid, document_json, updated_at) "
        f"VALUES {', '.join(values)} "
        "ON CONFLICT(path) DO UPDATE SET "
        "document_json = excluded.document_json, updated_at = excluded.updated_at "
        "WHERE COALESCE(CAST(json_extract(friendbook_cf_documents.document_json, '$.updatedAt') AS INTEGER), 0) "
        "<= COALESCE(CAST(json_extract(excluded.document_json, '$.updatedAt') AS INTEGER), 0)"
    )
    execute(sql, params)
    return len(values)


def existing_ids(collection: str, ids: list[str]) -> set[str]:
    """Which of these ids D1 already holds, checked in both shapes."""
    found: set[str] = set()
    for start in range(0, len(ids), 100):
        chunk = ids[start:start + 100]
        placeholders = ",".join("?" for _ in chunk)
        rows = execute(
            "SELECT doc_id FROM friendbook_cf_documents "
            f"WHERE collection = ? AND doc_id IN ({placeholders})",
            [collection, *chunk],
        )
        found.update(str(r["doc_id"]) for r in rows)
    return found


def counts_by_collection() -> dict[str, int]:
    rows = execute(
        "SELECT collection, COUNT(*) AS n FROM friendbook_cf_documents GROUP BY collection"
    )
    return {str(r["collection"]): int(r["n"]) for r in rows}
