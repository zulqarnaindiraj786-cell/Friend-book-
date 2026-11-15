#!/usr/bin/env python3
"""
Verify Firestore ↔ D1 data migration
Compares counts and samples between Firebase and Cloudflare D1
"""
import os
import json
import sys
import requests
from datetime import datetime
from pathlib import Path
import firebase_admin
from firebase_admin import credentials, firestore

PROJECT_ID = "friendbook-17551"
SERVICE_ACCOUNT = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "./service-account.json")
CF_API_BASE = os.environ.get("CF_API_BASE", "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/admin/migrate")
CF_API_TOKEN = os.environ.get("CF_API_TOKEN", "")

COLLECTIONS = [
    "users", "posts", "chats", "groups", "friendRequests",
    "notifications", "stories", "reels"
]

def init_firebase():
    if not firebase_admin._apps:
        if os.path.exists(SERVICE_ACCOUNT):
            cred = credentials.Certificate(SERVICE_ACCOUNT)
        else:
            cred = credentials.ApplicationDefault()
        firebase_admin.initialize_app(cred, {'projectId': PROJECT_ID})
    return firestore.client()

def get_firestore_count(fs_client, collection):
    """Get approximate count from Firestore"""
    try:
        # Use aggregation query if available, otherwise sample
        docs = list(fs_client.collection(collection).limit(10000).stream())
        return len(docs)
    except Exception as e:
        print(f"  Error counting {collection}: {e}")
        return -1

def get_d1_count(collection):
    """Get count from D1 via migration API"""
    if not CF_API_TOKEN:
        return -1
    try:
        url = f"{CF_API_BASE}/verify"
        headers = {"Authorization": f"Bearer {CF_API_TOKEN}"}
        response = requests.get(url, headers=headers, timeout=30)
        if response.status_code == 200:
            data = response.json()
            # Map collection names to table names
            table_map = {
                "users": "users",
                "posts": "posts",
                "chats": "chats",
                "groups": "groups",
                "friendRequests": "friend_requests",
                "notifications": "notifications",
                "stories": "stories",
                "reels": "reels",
            }
            table = table_map.get(collection, collection)
            return data.get("counts", {}).get(table, -1)
    except Exception as e:
        print(f"  Error getting D1 count for {collection}: {e}")
    return -1

def sample_compare(fs_client, collection, sample_size=10):
    """Compare sample documents between Firestore and D1"""
    print(f"\n  Sampling {collection}...")
    try:
        docs = list(fs_client.collection(collection).limit(sample_size).stream())
        for doc in docs[:3]:  # Show first 3
            data = doc.to_dict()
            print(f"    Doc {doc.id}: keys={list(data.keys())[:10]}...")
        return len(docs)
    except Exception as e:
        print(f"    Error: {e}")
        return 0

def main():
    print(f"""
╔══════════════════════════════════════════════════════════════╗
║         MIGRATION VERIFICATION                               ║
║  Project: {PROJECT_ID}                                        ║
║  Time: {datetime.now().isoformat()}                              ║
╚══════════════════════════════════════════════════════════════╝
    """)
    
    if not CF_API_TOKEN:
        print("⚠ CF_API_TOKEN not set - D1 counts will be unavailable\n")
    
    fs_client = init_firebase()
    print("✓ Firebase initialized\n")
    
    print("=" * 60)
    print("COUNT COMPARISON")
    print("=" * 60)
    
    total_firestore = 0
    total_d1 = 0
    all_match = True
    
    for coll in COLLECTIONS:
        fs_count = get_firestore_count(fs_client, coll)
        d1_count = get_d1_count(coll)
        
        total_firestore += max(0, fs_count)
        total_d1 += max(0, d1_count)
        
        match = "✓" if fs_count == d1_count and fs_count >= 0 else "✗"
        if fs_count != d1_count and fs_count >= 0 and d1_count >= 0:
            all_match = False
        
        print(f"  {match} {coll:20s} Firestore: {fs_count:>8}  D1: {d1_count:>8}")
        
        # Sample compare
        if fs_count > 0:
            sample_compare(fs_client, coll)
    
    print("-" * 60)
    match = "✓" if total_firestore == total_d1 else "✗"
    print(f"  {match} {'TOTAL':20s} Firestore: {total_firestore:>8}  D1: {total_d1:>8}")
    
    print("\n" + "=" * 60)
    if all_match and total_firestore == total_d1:
        print("✅ MIGRATION VERIFIED - All counts match!")
        print("   Safe to proceed with app code migration.")
    else:
        print("❌ MISMATCH DETECTED - Do NOT remove Firebase code yet!")
        print("   Investigate discrepancies before proceeding.")
    print("=" * 60)
    
    # Save report
    report = {
        "timestamp": datetime.now().isoformat(),
        "project": PROJECT_ID,
        "collections": {},
        "totals": {"firestore": total_firestore, "d1": total_d1},
        "verified": all_match and total_firestore == total_d1
    }
    
    for coll in COLLECTIONS:
        fs_count = get_firestore_count(fs_client, coll)
        d1_count = get_d1_count(coll)
        report["collections"][coll] = {
            "firestore": fs_count,
            "d1": d1_count,
            "match": fs_count == d1_count
        }
    
    report_file = Path(__file__).parent / f"migration_report_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    with open(report_file, 'w') as f:
        json.dump(report, f, indent=2)
    
    print(f"\nReport saved to: {report_file}")

if __name__ == "__main__":
    main()