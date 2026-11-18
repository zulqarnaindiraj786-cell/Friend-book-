"""Resumable Firestore -> private D1 archive, with a durable per-day reservation.

Firestore only: excludes Firebase Auth, Storage, RTDB and other services. Data is
archived, NOT activated in the application tables. The reservation limits only
this tool, not other clients, provider billing or aggregate/index-read charges.
"""
import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from urllib.parse import quote
from zoneinfo import ZoneInfo

from migrate_firestore_to_d1 import D1, FirestoreSource, CopyError, PREFIX, PROJECT, FIRESTORE

# Conservative maximum for this tool, keeping 40,000 of a 50k daily quota unreserved.
DAILY_LIMIT = 10000
PAGE_SIZE = 10
BATCH = 'budgeted-firestore-v1'

class BudgetExhausted(CopyError):
    pass

class Budget:
    def __init__(self, d1, cap=DAILY_LIMIT, clock=None):
        if not isinstance(cap, int) or isinstance(cap, bool) or cap < 1 or cap > DAILY_LIMIT:
            raise CopyError('Invalid daily budget')
        self.d1, self.cap = d1, cap
        self.clock = clock or (lambda: datetime.now(timezone.utc))

    def reserve(self, units):
        if not isinstance(units, int) or units < 1 or units > self.cap:
            raise BudgetExhausted('Reservation exceeds daily budget; no source request made')
        today = self.clock()
        if today.tzinfo is None:
            raise CopyError('Budget clock must be timezone aware')
        day = today.astimezone(ZoneInfo('America/Los_Angeles')).date().isoformat()
        rows = self.d1.query('''INSERT INTO friendbook_cf_copy_read_budget(quota_day,reserved,cap)
          VALUES (?,?,?) ON CONFLICT(quota_day) DO UPDATE SET
          reserved=reserved+excluded.reserved, cap=MIN(cap,excluded.cap)
          WHERE reserved+excluded.reserved<=MIN(cap,excluded.cap)
          RETURNING reserved,cap''', (day, units, self.cap))
        if len(rows) != 1:
            raise BudgetExhausted('Daily migration reservation exhausted; no source request made')
        return rows[0]

class CappedSource(FirestoreSource):
    def __init__(self, session, budget):
        super().__init__(session)
        self.budget = budget
    def count(self, collection_path):
        raise CopyError('Aggregation queries are forbidden in this copy')
    def request(self, method, url, operation='Firebase read', **kwargs):
        if operation == 'Firebase paginated collection read':
            if kwargs.get('params', {}).get('pageSize') != PAGE_SIZE:
                raise CopyError('Unsafe page size')
            self.budget.reserve(PAGE_SIZE)
        elif operation == 'Firebase child-collection listing':
            self.budget.reserve(1)
        else:
            raise CopyError('Unreviewed Firebase request forbidden')
        return super().request(method, url, operation=operation, **kwargs)
    def page(self, collection_path, token):
        params = {'pageSize': PAGE_SIZE, 'showMissing': 'true'}
        if token: params['pageToken'] = token
        data = self.request('get', FIRESTORE + quote(collection_path, safe='/()'),
                            operation='Firebase paginated collection read', params=params)
        docs = data.get('documents', [])
        if not isinstance(docs, list) or len(docs) > PAGE_SIZE:
            raise CopyError('Invalid or oversized source page; checkpoint not advanced')
        return docs, data.get('nextPageToken')

SCHEMAS = [
    '''CREATE TABLE IF NOT EXISTS friendbook_cf_copy_read_budget (
      quota_day TEXT PRIMARY KEY NOT NULL, reserved INTEGER NOT NULL CHECK(reserved>=0),
      cap INTEGER NOT NULL CHECK(cap>0))''',
    '''CREATE TABLE IF NOT EXISTS friendbook_cf_fullcopy_queue (
      path TEXT PRIMARY KEY NOT NULL, page_token TEXT, status TEXT NOT NULL DEFAULT 'pending',
      documents INTEGER NOT NULL DEFAULT 0)''',
    '''CREATE TABLE IF NOT EXISTS friendbook_cf_fullcopy_archive (
      path TEXT PRIMARY KEY NOT NULL, document_json TEXT NOT NULL CHECK(json_valid(document_json)),
      sha256 TEXT NOT NULL)''',
    '''CREATE TABLE IF NOT EXISTS friendbook_cf_fullcopy_tokens (
      path TEXT NOT NULL, token TEXT NOT NULL, PRIMARY KEY(path,token))''',
]

def valid_collection(name):
    return isinstance(name, str) and bool(name) and name not in ('.', '..') and '/' not in name and not any(ord(c) < 32 for c in name)

def enqueue(d1, path):
    if not path.startswith(PREFIX + '/'):
        raise CopyError('Unexpected source path')
    segments = path[len(PREFIX)+1:].split('/')
    if len(segments) % 2 != 1 or not all(valid_collection(s) for s in segments):
        raise CopyError('Invalid collection path')
    d1.query('INSERT OR IGNORE INTO friendbook_cf_fullcopy_queue(path) VALUES (?)', (path,))

def archive(d1, doc):
    name = doc.get('name', '')
    if not name.startswith(PREFIX + '/'):
        raise CopyError('Unexpected document path')
    path = name[len(PREFIX)+1:]
    parts = path.split('/')
    if len(parts) % 2 or not all(valid_collection(s) for s in parts) or 'createTime' not in doc:
        raise CopyError('Invalid document identity')
    text = json.dumps(doc, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    digest = hashlib.sha256(text.encode('utf8')).hexdigest()
    d1.query('INSERT OR IGNORE INTO friendbook_cf_fullcopy_archive(path,document_json,sha256) VALUES (?,?,?)', (path,text,digest))
    rows = d1.query('SELECT sha256,document_json FROM friendbook_cf_fullcopy_archive WHERE path=?',(path,))
    if len(rows)!=1 or rows[0]['sha256']!=digest or rows[0]['document_json']!=text:
        raise CopyError('Source document changed or archive mismatch; preserved existing data and stopped')


def copy_queue(source, d1):
    # Collection discovery uses metadata only and itself counts towards the reservation.
    for root in source.child_collections(PREFIX):
        if not valid_collection(root): raise CopyError('Invalid root collection')
        enqueue(d1, PREFIX+'/'+root)
    while True:
        tasks=d1.query("SELECT path,page_token FROM friendbook_cf_fullcopy_queue WHERE status='pending' ORDER BY CASE WHEN path=? THEN 0 ELSE 1 END,path LIMIT 1",(PREFIX+'/users',))
        if not tasks: break
        task=tasks[0]; path=task['path']; token=task['page_token']
        docs,next_token=source.page(path,token)
        if next_token and next_token==token: raise CopyError('Repeated page cursor')
        saved=0
        for doc in docs:
            name=doc.get('name','')
            if not name.startswith(path+'/') or '/' in name[len(path)+1:]: raise CopyError('Unexpected page document')
            if 'createTime' in doc:
                archive(d1,doc);saved+=1
            for child in source.child_collections(name):
                if not valid_collection(child): raise CopyError('Invalid child collection')
                enqueue(d1,name+'/'+child)
        # Persist visited cursors to detect cycles, including across interrupted runs.
        if token:
            d1.query('INSERT OR IGNORE INTO friendbook_cf_fullcopy_tokens(path,token) VALUES (?,?)',(path,token))
        if next_token and d1.query('SELECT token FROM friendbook_cf_fullcopy_tokens WHERE path=? AND token=?',(path,next_token)):
            raise CopyError('Repeated page cursor; checkpoint retained')
        # The next page is checkpointed only after every record and child task is saved.
        updated=d1.query("UPDATE friendbook_cf_fullcopy_queue SET page_token=?,status=?,documents=documents+? WHERE path=? AND status='pending' AND page_token IS ? RETURNING path",
                 (next_token,'pending' if next_token else 'complete',saved,path,token))
        if len(updated)!=1: raise CopyError('Concurrent checkpoint changed; stopped')
    return d1.query("SELECT COUNT(*) AS collections, SUM(CASE WHEN status='pending' THEN 1 ELSE 0 END) AS pending FROM friendbook_cf_fullcopy_queue")[0]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--execute',action='store_true')
    p.add_argument('--daily-budget',type=int,default=DAILY_LIMIT)
    args=p.parse_args()
    if not args.execute:
        print('NOT EXECUTED: no provider contacted'); return 2
    if args.daily_budget < 1 or args.daily_budget > DAILY_LIMIT:
        print('Invalid daily budget; no provider contacted'); return 2
    d1=None
    try:
        from google.oauth2 import service_account
        from google.auth.transport.requests import AuthorizedSession
        import requests
        firebase=json.loads(os.environ['FIREBASE_ADMIN_SERVICE_ACCOUNT'])
        if firebase.get('project_id') != PROJECT or firebase.get('type') != 'service_account':
            raise CopyError('Unexpected Firebase service account project')
        token=os.environ['CLOUDFLARE_API_TOKEN']
        if not token: raise CopyError('Cloudflare token unavailable')
        with requests.Session() as session:
            session.headers['Authorization']='Bearer '+token
            d1=D1(session)
            for sql in SCHEMAS: d1.query(sql)
            budget=Budget(d1,args.daily_budget)
            credentials=service_account.Credentials.from_service_account_info(firebase,scopes=['https://www.googleapis.com/auth/datastore'])
            with AuthorizedSession(credentials) as firebase_session:
                source=CappedSource(firebase_session,budget)
                progress=copy_queue(source,d1)
            totals=d1.query('SELECT COUNT(*) AS archived_documents FROM friendbook_cf_fullcopy_archive')[0]
            print('::notice::COPY_RESULT='+json.dumps({'archive':totals,'queue':progress,'source_writes':0,'application_tables_modified':False,'point_in_time_snapshot':False,'safe_to_cutover':False}))
        return 0
    except Exception as exc:
        print(f'::error::Copy stopped ({type(exc).__name__}); checkpoints retained; no Firebase writes. Details deliberately omitted.')
        if d1:
            try:
                totals=d1.query("SELECT (SELECT COUNT(*) FROM friendbook_cf_fullcopy_archive) AS archived_documents,(SELECT COUNT(*) FROM friendbook_cf_fullcopy_queue WHERE status='pending') AS pending_collections,(SELECT reserved FROM friendbook_cf_copy_read_budget ORDER BY quota_day DESC LIMIT 1) AS reserved_units")
                print('::notice::COPY_CHECKPOINT='+json.dumps(totals[0]))
            except Exception: pass
        return 1

if __name__=='__main__': sys.exit(main())
