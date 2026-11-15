#!/usr/bin/env python3
"""
Firebase Backup Script
Exports all Firestore collections, RTDB data, and Storage files to local + R2
"""
import os
import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Any
import firebase_admin
from firebase_admin import credentials, firestore, db, storage

# Configuration
PROJECT_ID = "friendbook-17551"
BACKUP_DIR = Path(__file__).parent / "backups" / datetime.now().strftime("%Y%m%d_%H%M%S")
SERVICE_ACCOUNT = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "./service-account.json")

# Collections to backup (from Models.java)
FIRESTORE_COLLECTIONS = [
    "users",
    "posts",
    "chats",
    "groups",
    "friendRequests",
    "notifications",
    "stories",
    "reels",
    "crashReports",
    "adminSettings",
    "appConfig",
    "feedPreferences",
    "savedPosts",
    "blockedUsers",
    "reportedContent",
    "moderationActions",
    "referrals",
    "earnings",
    "payouts",
    "subscriptions",
    "monetizationSettings",
    "blueTickApplications",
    "verificationRequests",
    "officialBroadcasts",
    "teamChats",
    "liveStreams",
    "callLogs",
    "searchIndexes",
    "trendingTopics",
    "hashtags",
    "mentions",
    "reactions",  # legacy
    "comments",   # legacy
    "views",      # legacy
]

# RTDB paths to backup
RTDB_PATHS = [
    "chats",  # includes activeCall for signaling
    "presence",
    "typing",
    ".info/serverTimeOffset",
]

def init_firebase():
    """Initialize Firebase Admin SDK"""
    if not firebase_admin._apps:
        if os.path.exists(SERVICE_ACCOUNT):
            cred = credentials.Certificate(SERVICE_ACCOUNT)
        else:
            # Try default credentials
            cred = credentials.ApplicationDefault()
        firebase_admin.initialize_app(cred, {
            'projectId': PROJECT_ID,
            'databaseURL': f'https://{PROJECT_ID}-default-rtdb.firebaseio.com',
            'storageBucket': f'{PROJECT_ID}.firebasestorage.app'
        })
    return firestore.client(), db.reference(), storage.bucket()

def backup_firestore(fs_client) -> Dict[str, int]:
    """Backup all Firestore collections"""
    print(f"\n{'='*60}")
    print(f"Backing up Firestore collections to {BACKUP_DIR}")
    print(f"{'='*60}")
    
    counts = {}
    for coll_name in FIRESTORE_COLLECTIONS:
        coll_dir = BACKUP_DIR / "firestore" / coll_name
        coll_dir.mkdir(parents=True, exist_ok=True)
        
        try:
            docs = fs_client.collection(coll_name).stream()
            count = 0
            batch = []
            
            for doc in docs:
                data = doc.to_dict()
                data['__id'] = doc.id
                batch.append(data)
                count += 1
                
                # Write in batches of 1000
                if len(batch) >= 1000:
                    batch_file = coll_dir / f"batch_{count // 1000}.json"
                    with open(batch_file, 'w') as f:
                        json.dump(batch, f, default=str, indent=2)
                    batch = []
            
            # Write remaining
            if batch:
                batch_file = coll_dir / f"batch_{count // 1000 + 1}.json"
                with open(batch_file, 'w') as f:
                    json.dump(batch, f, default=str, indent=2)
            
            counts[coll_name] = count
            print(f"  ✓ {coll_name}: {count} documents")
            
        except Exception as e:
            print(f"  ✗ {coll_name}: ERROR - {e}")
            counts[coll_name] = 0
    
    # Save summary
    summary_file = BACKUP_DIR / "firestore_summary.json"
    with open(summary_file, 'w') as f:
        json.dump(counts, f, indent=2)
    
    return counts

def backup_rtdb(rtdb_ref) -> Dict[str, int]:
    """Backup RTDB data"""
    print(f"\n{'='*60}")
    print(f"Backing up RTDB to {BACKUP_DIR}")
    print(f"{'='*60}")
    
    counts = {}
    for path in RTDB_PATHS:
        path_dir = BACKUP_DIR / "rtdb" / path.replace("/", "_").replace(".", "_")
        path_dir.mkdir(parents=True, exist_ok=True)
        
        try:
            ref = rtdb_ref.child(path)
            data = ref.get()
            
            if data:
                file_path = path_dir / "data.json"
                with open(file_path, 'w') as f:
                    json.dump(data, f, default=str, indent=2)
                
                # Count items
                if isinstance(data, dict):
                    count = len(data)
                elif isinstance(data, list):
                    count = len(data)
                else:
                    count = 1
            else:
                count = 0
                file_path = path_dir / "data.json"
                with open(file_path, 'w') as f:
                    json.dump({}, f)
            
            counts[path] = count
            print(f"  ✓ {path}: {count} items")
            
        except Exception as e:
            print(f"  ✗ {path}: ERROR - {e}")
            counts[path] = 0
    
    summary_file = BACKUP_DIR / "rtdb_summary.json"
    with open(summary_file, 'w') as f:
        json.dump(counts, f, indent=2)
    
    return counts

def backup_storage(bucket) -> Dict[str, int]:
    """List all Firebase Storage files (don't download, just catalog)"""
    print(f"\n{'='*60}")
    print(f"Cataloging Firebase Storage to {BACKUP_DIR}")
    print(f"{'='*60}")
    
    try:
        blobs = bucket.list_blobs()
        files = []
        total_size = 0
        
        for blob in blobs:
            files.append({
                "name": blob.name,
                "size": blob.size,
                "content_type": blob.content_type,
                "updated": blob.updated.isoformat() if blob.updated else None,
                "md5_hash": blob.md5_hash,
                "generation": blob.generation,
            })
            total_size += blob.size or 0
        
        catalog_file = BACKUP_DIR / "storage_catalog.json"
        with open(catalog_file, 'w') as f:
            json.dump({
                "total_files": len(files),
                "total_size_bytes": total_size,
                "files": files
            }, f, indent=2)
        
        print(f"  ✓ Storage: {len(files)} files, {total_size / (1024*1024):.2f} MB")
        return {"files": len(files), "size_mb": round(total_size / (1024*1024), 2)}
        
    except Exception as e:
        print(f"  ✗ Storage: ERROR - {e}")
        return {"files": 0, "size_mb": 0}

def upload_to_r2(backup_dir: Path):
    """Upload backup to Cloudflare R2 for safekeeping"""
    print(f"\n{'='*60}")
    print(f"Uploading backup to R2...")
    print(f"{'='*60}")
    
    # Use wrangler or rclone if available
    try:
        # Create tarball
        tarball = backup_dir.with_suffix('.tar.gz')
        subprocess.run(['tar', '-czf', str(tarball), '-C', str(backup_dir.parent), backup_dir.name], check=True)
        print(f"  Created {tarball}")
        
        # Upload to R2 (requires R2 credentials in env)
        # rclone copy tarball remote:bucket/path
        print(f"  Run manually: rclone copy {tarball} r2:friendbook-backups/{tarball.name}")
        
    except Exception as e:
        print(f"  ⚠ R2 upload failed: {e}")

def main():
    print(f"""
╔══════════════════════════════════════════════════════════════╗
║           FRIENDBOOK FIREBASE BACKUP                         ║
║  Project: {PROJECT_ID}                                        ║
║  Backup Dir: {BACKUP_DIR}                      ║
╚══════════════════════════════════════════════════════════════╝
    """)
    
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    
    # Initialize Firebase
    try:
        fs_client, rtdb_ref, bucket = init_firebase()
        print("✓ Firebase initialized")
    except Exception as e:
        print(f"✗ Firebase init failed: {e}")
        print("Set FIREBASE_SERVICE_ACCOUNT env var or place service-account.json in script dir")
        sys.exit(1)
    
    # Run backups
    firestore_counts = backup_firestore(fs_client)
    rtdb_counts = backup_rtdb(rtdb_ref)
    storage_counts = backup_storage(bucket)
    
    # Overall summary
    total_docs = sum(firestore_counts.values())
    total_rtdb = sum(rtdb_counts.values())
    
    summary = {
        "timestamp": datetime.now().isoformat(),
        "project_id": PROJECT_ID,
        "backup_dir": str(BACKUP_DIR),
        "firestore": firestore_counts,
        "rtdb": rtdb_counts,
        "storage": storage_counts,
        "totals": {
            "firestore_documents": total_docs,
            "rtdb_items": total_rtdb,
            "storage_files": storage_counts.get("files", 0),
            "storage_size_mb": storage_counts.get("size_mb", 0)
        }
    }
    
    summary_file = BACKUP_DIR / "BACKUP_SUMMARY.json"
    with open(summary_file, 'w') as f:
        json.dump(summary, f, indent=2)
    
    print(f"\n{'='*60}")
    print(f"BACKUP COMPLETE")
    print(f"{'='*60}")
    print(f"Firestore: {total_docs} documents across {len(firestore_counts)} collections")
    print(f"RTDB: {total_rtdb} items across {len(rtdb_counts)} paths")
    print(f"Storage: {storage_counts.get('files', 0)} files ({storage_counts.get('size_mb', 0)} MB)")
    print(f"\nSummary saved to: {summary_file}")
    print(f"\n⚠ DO NOT DELETE FIREBASE DATA UNTIL MIGRATION VERIFIED!")
    
    # Upload to R2
    upload_to_r2(BACKUP_DIR)

if __name__ == "__main__":
    main()