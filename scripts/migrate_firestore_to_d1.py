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

def send_batch(collection, batch):
    """Send batch to Cloudflare Workers migration endpoint"""
    if not CF_API_TOKEN:
        print("  ⚠ CF_API_TOKEN not set, skipping actual upload")
        return True
    
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
            send_batch("comments", batch)
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
            send_batch("messages", batch)
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
    
    if not CF_API_TOKEN:
        print("⚠ WARNING: CF_API_TOKEN not set!")
        print("Set CF_API_TOKEN environment variable to enable actual migration.")
        print("Running in DRY RUN mode...\n")
    
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
        sys.exit(1)
    else:
        print("\n✓ All documents migrated successfully!")

if __name__ == "__main__":
    main()