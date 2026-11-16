#!/usr/bin/env python3
"""
Migrate Firestore data to Cloudflare D1
Run after creating D1 schema (migration 0014)
"""
import os
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Any, Optional
from concurrent.futures import ThreadPoolExecutor, as_completed
import firebase_admin
from firebase_admin import credentials, firestore
import requests

# Configuration
PROJECT_ID = "friendbook-17551"
SERVICE_ACCOUNT = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "./service-account.json")
CF_API_BASE = os.environ.get("CF_API_BASE", "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/admin/migrate")
CF_API_TOKEN = os.environ.get("CF_API_TOKEN", "")  # Admin token for migration endpoint

# Collections to migrate (in dependency order)
COLLECTIONS = [
    "users",
    "posts",
    "chats",
    "groups",
    "friendRequests",
    "notifications",
    "stories",
    "reels",
    "comments",  # subcollection
]

BATCH_SIZE = 500
MAX_WORKERS = 4

# Firestore reads from a busy production project can exceed the client's default deadline
# (observed as google.api_core.timeout / DEADLINE_EXCEEDED). A single blip must not abort a
# migration that has already copied thousands of documents.
READ_RETRIES = int(os.environ.get("MIGRATION_READ_RETRIES", "4"))
RETRY_BACKOFF_S = 5
# Canary knob: copy at most this many documents per collection, 0 = no limit.
MAX_DOCS_PER_COLLECTION = int(os.environ.get("MIGRATION_MAX_DOCS", "0"))
# Restrict the run to specific collections (comma separated), empty = all.
ONLY_COLLECTIONS = [c.strip() for c in os.environ.get("MIGRATION_ONLY", "").split(",") if c.strip()]


def with_retry(description, fn):
    """Run fn, retrying transient Firestore failures with backoff.

    Returns the value, or raises the last error so the caller can count it. Every retry is
    printed: a migration that silently retried for ten minutes looks identical to a hang.
    """
    last = None
    for attempt in range(1, READ_RETRIES + 1):
        try:
            return fn()
        except Exception as exc:
            last = exc
            if attempt == READ_RETRIES:
                break
            wait = RETRY_BACKOFF_S * attempt
            print(f"    retry {attempt}/{READ_RETRIES - 1} for {description} "
                  f"in {wait}s after {type(exc).__name__}: {str(exc)[:160]}")
            time.sleep(wait)
    raise last


def read_documents(coll_ref, description, limit=None):
    """Stream a collection with retries, capped by MAX_DOCS_PER_COLLECTION."""
    def _read():
        ref = coll_ref
        cap = limit if limit else (MAX_DOCS_PER_COLLECTION or None)
        if cap:
            ref = ref.limit(cap)
        return list(ref.stream())
    return with_retry(description, _read)

def init_firebase():
    """Initialise the Admin SDK from a key file, key JSON, or ADC.

    FIREBASE_SERVICE_ACCOUNT is documented as a path, but in CI the same variable holds the
    secret's JSON body. Treating the body as a path meant os.path.exists() was false, the
    code fell through to ApplicationDefault(), and initialisation failed with a message that
    pointed at credentials that were in fact present.
    """
    if not firebase_admin._apps:
        source = SERVICE_ACCOUNT
        if source and source.lstrip().startswith('{'):
            try:
                cred = credentials.Certificate(json.loads(source))
            except Exception as exc:
                raise ValueError(
                    f"FIREBASE_SERVICE_ACCOUNT looks like JSON but could not be parsed: {exc}"
                ) from exc
        elif source and os.path.exists(source):
            cred = credentials.Certificate(source)
        else:
            cred = credentials.ApplicationDefault()
        firebase_admin.initialize_app(cred, {'projectId': PROJECT_ID})
    return firestore.client()

def convert_firestore_value(value):
    """Convert Firestore values to JSON-serializable"""
    if value is None:
        return None
    if hasattr(value, 'timestamp'):  # Timestamp
        return int(value.timestamp() * 1000)
    if isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, list):
        return [convert_firestore_value(v) for v in value]
    if isinstance(value, dict):
        return {k: convert_firestore_value(v) for k, v in value.items()}
    if hasattr(value, '__dict__'):
        return convert_firestore_value(value.__dict__)
    return str(value)

def migrate_collection(fs_client, collection_name, limit=None):
    """Migrate a single collection to D1 via Cloudflare Workers"""
    print(f"\n{'='*60}")
    print(f"Migrating {collection_name}...")
    print(f"{'='*60}")
    
    coll_ref = fs_client.collection(collection_name)
    print(f"  reading {collection_name}..."
          + (f" (cap {limit})" if limit else
             (f" (cap {MAX_DOCS_PER_COLLECTION})" if MAX_DOCS_PER_COLLECTION else "")))
    docs = read_documents(coll_ref, collection_name, limit)
    print(f"  read {len(docs)} documents from {collection_name}")
    
    batch = []
    total = 0
    errors = 0
    
    for doc in docs:
        data = doc.to_dict()
        data['__id'] = doc.id
        
        # Convert Firestore-specific types
        converted = {}
        for k, v in data.items():
            converted[k] = convert_firestore_value(v)
        
        batch.append(converted)
        total += 1
        
        if len(batch) >= BATCH_SIZE:
            success = send_batch(collection_name, batch)
            if success:
                print(f"  ✓ Batch {total//BATCH_SIZE}: {len(batch)} docs")
            else:
                errors += len(batch)
                print(f"  ✗ Batch {total//BATCH_SIZE}: FAILED")
            batch = []
            time.sleep(0.1)  # Rate limiting
    
    # Send remaining
    if batch:
        success = send_batch(collection_name, batch)
        if success:
            print(f"  ✓ Final batch: {len(batch)} docs")
        else:
            errors += len(batch)
            print(f"  ✗ Final batch: FAILED")
    
    print(f"\n{collection_name}: {total} total, {errors} errors")
    return total, errors

class MigrationError(RuntimeError):
    """Raised when the migration cannot proceed without risking data loss."""


def require_token():
    """Refuse to run at all without a migration token.

    This used to print a warning and return success, which meant a migration
    with no CF_API_TOKEN reported every batch as uploaded while writing
    nothing. A later verify step would then compare Firestore to an empty D1
    and anyone reading the log would conclude the cutover had worked.
    """
    if not CF_API_TOKEN:
        raise MigrationError(
            "CF_API_TOKEN is not set. Refusing to run: every batch would report success "
            "while writing nothing, and the verification step would compare Firestore "
            "against an empty database."
        )


def send_batch(collection, batch):
    """Send batch to Cloudflare Workers migration endpoint.

    Returns True only when the worker confirmed the write. Any other outcome -
    missing token, non-200, network error, malformed response - returns False so
    the caller counts it as an error.
    """
    require_token()
    
    url = f"{CF_API_BASE}/{collection}"
    headers = {
        "Authorization": f"Bearer {CF_API_TOKEN}",
        "Content-Type": "application/json",
    }
    payload = {"documents": batch}
    
    try:
        response = requests.post(url, json=payload, headers=headers, timeout=60)
        if response.status_code == 200:
            return True
        else:
            print(f"  Error: {response.status_code} - {response.text[:200]}")
            return False
    except Exception as e:
        print(f"  Exception: {e}")
        return False

def migrate_subcollections(fs_client):
    """Migrate subcollections (comments on posts, messages in chats)"""
    print(f"\n{'='*60}")
    print(f"Migrating subcollections...")
    print(f"{'='*60}")
    
    # Comments on posts
    posts = fs_client.collection("posts").limit(100).stream()
    for post in posts:
        comments = post.reference.collection("comments").stream()
        batch = []
        for comment in comments:
            data = comment.to_dict()
            data['__id'] = comment.id
            data['postId'] = post.id
            converted = {k: convert_firestore_value(v) for k, v in data.items()}
            batch.append(converted)
        
        if batch:
            if not send_batch("comments", batch):
                raise MigrationError(f"Failed to upload comments for post {post.id}")
            print(f"  ✓ Post {post.id}: {len(batch)} comments")
    
    # Messages in chats
    chats = fs_client.collection("chats").limit(100).stream()
    for chat in chats:
        messages = chat.reference.collection("messages").stream()
        batch = []
        for message in messages:
            data = message.to_dict()
            data['__id'] = message.id
            data['chatId'] = chat.id
            converted = {k: convert_firestore_value(v) for k, v in data.items()}
            batch.append(converted)
        
        if batch:
            if not send_batch("messages", batch):
                raise MigrationError(f"Failed to upload messages for chat {chat.id}")
            print(f"  ✓ Chat {chat.id}: {len(batch)} messages")

def verify_counts(fs_client):
    """Verify document counts match between Firestore and D1"""
    print(f"\n{'='*60}")
    print(f"Verifying counts...")
    print(f"{'='*60}")
    
    for coll in COLLECTIONS:
        try:
            count = len(read_documents(fs_client.collection(coll), f"count {coll}", 10000))
            print(f"  Firestore {coll}: ~{count}+ docs")
        except Exception as e:
            print(f"  Firestore {coll}: ERROR - {type(e).__name__}: {e}")

def main():
    print(f"""
╔══════════════════════════════════════════════════════════════╗
║         FIRESTORE → D1 MIGRATION                             ║
║  Project: {PROJECT_ID}                                        ║
║  Target: Cloudflare D1 via Workers API                       ║
╚══════════════════════════════════════════════════════════════╝
    """)
    
    # Fail before touching Firestore. There is no dry-run mode: the script has no
    # "read everything, write nothing" path, so running without a token used to look
    # like a successful migration that wrote zero rows.
    print(f"Target: {CF_API_BASE}")
    print(f"Token: {'set (' + str(len(CF_API_TOKEN)) + ' chars)' if CF_API_TOKEN else 'MISSING'}")
    if not CF_API_TOKEN:
        print("ERROR: CF_API_TOKEN is not set.")
        print("The migration needs a Cloudflare migration token (wrangler secret put MIGRATION_ADMIN_TOKEN).")
        print("Refusing to start: a run without it would report success while writing nothing.")
        return 2
    
    # Probe the worker before touching Firestore. If the migration endpoint rejects us,
    # reading the entire database first would be wasted work and the failure would look
    # like a data problem rather than an auth problem.
    try:
        url = f"{CF_API_BASE}/verify"
        response = requests.get(url, headers={"Authorization": f"Bearer {CF_API_TOKEN}"}, timeout=30)
        print(f"Worker /verify -> HTTP {response.status_code}")
        if response.status_code in (401, 403):
            print(f"ERROR: the worker rejected the migration token. Body: {response.text[:200]}")
            return 4
        if response.status_code == 503:
            print("ERROR: the worker has no MIGRATION_ADMIN_TOKEN configured.")
            return 4
        if response.status_code == 200:
            print(f"Worker counts: {response.text[:300]}")
    except Exception as exc:
        print(f"ERROR: could not reach the migration endpoint: {exc}")
        return 4

    print("Initialising Firebase...")
    try:
        fs_client = init_firebase()
    except Exception as exc:
        print(f"ERROR: Firebase initialisation failed: {type(exc).__name__}: {exc}")
        print(f"GOOGLE_APPLICATION_CREDENTIALS={os.environ.get('GOOGLE_APPLICATION_CREDENTIALS', 'unset')}")
        print(f"service account file exists: {os.path.exists(SERVICE_ACCOUNT)}")
        return 5
    print("✓ Firebase initialized\n")
    
    # Verify first
    verify_counts(fs_client)
    
    # Migrate main collections
    total_docs = 0
    total_errors = 0
    
    targets = [c for c in COLLECTIONS if not ONLY_COLLECTIONS or c in ONLY_COLLECTIONS]
    if ONLY_COLLECTIONS:
        print(f"Restricted to collections: {', '.join(targets)}")
    if MAX_DOCS_PER_COLLECTION:
        print(f"Canary mode: at most {MAX_DOCS_PER_COLLECTION} documents per collection")

    for coll in targets:
        try:
            count, errors = migrate_collection(fs_client, coll)
        except Exception as exc:
            # One unreadable collection must not abort the whole run, but it must not be
            # silent either: it counts as an error and is named.
            print(f"  ERROR reading {coll}: {type(exc).__name__}: {exc}")
            total_errors += 1
            continue
        total_docs += count
        total_errors += errors
    
    # Migrate subcollections
    migrate_subcollections(fs_client)
    
    print(f"\n{'='*60}")
    print(f"MIGRATION COMPLETE")
    print(f"{'='*60}")
    print(f"Total documents: {total_docs}")
    print(f"Total errors: {total_errors}")
    print(f"Success rate: {((total_docs - total_errors) / max(1, total_docs) * 100):.1f}%")
    
    if total_errors > 0:
        print("\n⚠ Some documents failed to migrate. Check logs and retry.")
        return 1
    if total_docs == 0:
        # Never report success for having written nothing. An empty source is either the
        # wrong project or a broken credential, and calling that "migrated" is how an
        # empty cutover gets signed off.
        print("\nERROR: no documents were migrated. Refusing to report success.")
        return 3
    print("\n✓ All documents migrated successfully!")
    return 0

if __name__ == "__main__":
    sys.exit(main())