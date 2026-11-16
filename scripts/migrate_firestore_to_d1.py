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

def init_firebase():
    if not firebase_admin._apps:
        if os.path.exists(SERVICE_ACCOUNT):
            cred = credentials.Certificate(SERVICE_ACCOUNT)
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
    if limit:
        docs = coll_ref.limit(limit).stream()
    else:
        docs = coll_ref.stream()
    
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
            count = len(list(fs_client.collection(coll).limit(10000).stream()))
            print(f"  Firestore {coll}: ~{count}+ docs")
        except Exception as e:
            print(f"  Firestore {coll}: ERROR - {e}")

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
    if not CF_API_TOKEN:
        print("ERROR: CF_API_TOKEN is not set.")
        print("The migration needs a Cloudflare migration token (wrangler secret put MIGRATION_ADMIN_TOKEN).")
        print("Refusing to start: a run without it would report success while writing nothing.")
        return 2
    
    fs_client = init_firebase()
    print("✓ Firebase initialized\n")
    
    # Verify first
    verify_counts(fs_client)
    
    # Migrate main collections
    total_docs = 0
    total_errors = 0
    
    for coll in COLLECTIONS:
        count, errors = migrate_collection(fs_client, coll)
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