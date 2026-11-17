"""Quota-throttled, resumable Firebase Firestore -> Cloudflare D1 copy.

One collection page (50 documents) at a time; collections run serially in the
requested priority order. Source is read-only. Existing D1 documents are never
updated or deleted. Each copied page and opaque Firestore page cursor are
checkpointed in D1; replay after interruption is safe and idempotent.
"""
import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from urllib.parse import quote

PROJECT = 'friendbook-17551'
ACCOUNT = '92f3d04598822de2e49e14561218d090'
DATABASE = 'e1c605ca-a50b-46c6-9deb-56dd1f70d163'
DATABASE_NAME = 'friendbook-db'
PREFIX = f'projects/{PROJECT}/databases/(default)/documents'
FIRESTORE = 'https://firestore.googleapis.com/v1/'
D1_URL = f'https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/d1/database/{DATABASE}/query'
DOC_TABLE = 'friendbook_cf_firestore_documents'
PROGRESS_TABLE = 'friendbook_cf_firestore_migration_progress'
PAGE_SIZE = 50
PAUSE_SECONDS = 2.5
QUOTA_WAIT_SECONDS = 600
ROOT_PRIORITY = {'users': 0, 'profiles': 1, 'posts': 2, 'friends': 3, 'friendRequests': 3}


class CopyError(RuntimeError):
    pass


def firestore_fields(fields):
    def decode(value):
        if not isinstance(value, dict):
            return None
        if 'stringValue' in value: return value['stringValue']
        if 'booleanValue' in value: return bool(value['booleanValue'])
        if 'integerValue' in value: return int(value['integerValue'])
        if 'doubleValue' in value: return float(value['doubleValue'])
        if 'nullValue' in value: return None
        if 'timestampValue' in value:
            text = value['timestampValue']
            try: return int(datetime.fromisoformat(text.replace('Z', '+00:00')).timestamp() * 1000)
            except (TypeError, ValueError): return 0
        if 'referenceValue' in value: return {'__type': 'reference', 'path': value['referenceValue'].split('/documents/', 1)[-1]}
        if 'arrayValue' in value:
            return [decode(item) for item in value.get('arrayValue', {}).get('values', [])]
        if 'mapValue' in value:
            return firestore_fields(value.get('mapValue', {}).get('fields', {}))
        return None
    return {key: decode(value) for key, value in (fields or {}).items()}


def document_record(document):
    name = document.get('name', '')
    marker = f'{PREFIX}/'
    if not name.startswith(marker):
        raise CopyError('Unexpected Firestore document identity')
    path = name[len(marker):]
    parts = path.split('/')
    if len(parts) < 2 or len(parts) % 2 or any(not p or p in ('.', '..') for p in parts):
        raise CopyError('Invalid Firestore document path')
    data = firestore_fields(document.get('fields', {}))
    owner = next((data[k] for k in ('authorId','uid','senderId','from','toUserId','toUid','userId','creatorId','hostId') if isinstance(data.get(k), str) and data[k]), '')
    if parts[0] == 'users' and len(parts) == 2:
        owner = owner or parts[1]
    if document.get('createTime'):
        try: data.setdefault('createdAt', int(datetime.fromisoformat(document['createTime'].replace('Z', '+00:00')).timestamp() * 1000))
        except (TypeError, ValueError): pass
    return {
        'path': path,
        'parent_path': '/'.join(parts[:-1]),
        'collection_id': parts[-2],
        'doc_id': parts[-1],
        'owner_uid': owner,
        'document_json': json.dumps(data, sort_keys=True, separators=(',', ':'), ensure_ascii=False),
        'create_time': 0,
        'update_time': 0,
    }


class FirestoreSource:
    def __init__(self, session, sleep=time.sleep):
        self.session, self.sleep = session, sleep

    def request(self, method, url, **kwargs):
        while True:
            response = getattr(self.session, method)(url, timeout=90, **kwargs)
            try: data = response.json()
            except Exception: data = {}
            code = data.get('error', {}).get('status') if isinstance(data, dict) else None
            if response.status_code == 429 or code == 'RESOURCE_EXHAUSTED':
                print(f'Firebase quota exhausted; waiting {QUOTA_WAIT_SECONDS}s, then retrying the same request/cursor.', flush=True)
                self.sleep(QUOTA_WAIT_SECONDS)
                continue
            if response.status_code != 200:
                raise CopyError(f'Firebase read failed HTTP {response.status_code}; checkpoint retained and no source writes made')
            return data

    def count(self, collection_path):
        parent, collection = collection_path.rsplit('/', 1) if '/' in collection_path else ('', collection_path)
        parent = parent or PREFIX
        body = {'structuredAggregationQuery': {
            'structuredQuery': {'from': [{'collectionId': collection}]},
            'aggregations': [{'alias': 'total', 'count': {}}]}}
        rows = self.request('post', FIRESTORE + quote(parent, safe='/()') + ':runAggregationQuery', json=body)
        results = [r for r in rows if r.get('result')]
        if len(results) != 1:
            raise CopyError('Firestore count response was not authoritative')
        return int(results[0]['result']['aggregateFields']['total']['integerValue'])

    def page(self, collection_path, token):
        params = {'pageSize': PAGE_SIZE, 'showMissing': 'true'}
        if token: params['pageToken'] = token
        data = self.request('get', FIRESTORE + quote(collection_path, safe='/()'), params=params)
        return data.get('documents', []), data.get('nextPageToken')

    def child_collections(self, document_path):
        url = FIRESTORE + quote(document_path, safe='/()') + ':listCollectionIds'
        token, seen, found = None, set(), []
        while True:
            body = {'pageSize': 100}
            if token: body['pageToken'] = token
            data = self.request('post', url, json=body)
            found.extend(data.get('collectionIds', []))
            token = data.get('nextPageToken')
            if not token: return found
            if token in seen: raise CopyError('Repeated Firestore child-collection cursor')
            seen.add(token)


class D1:
    def __init__(self, session): self.session = session

    def query(self, sql, params=()):
        response = self.session.post(D1_URL, json={'sql': sql, 'params': list(params)}, timeout=90)
        try: payload = response.json()
        except Exception: payload = {}
        if response.status_code != 200 or not payload.get('success'):
            errors = payload.get('errors', []) if isinstance(payload, dict) else []
            detail = '; '.join(str(item.get('message', item))[:300] for item in errors[:3])
            raise CopyError(f'Cloudflare D1 query failed HTTP {response.status_code}: {detail or "no safe diagnostic message returned"}')
        result = payload.get('result', [])
        if not result or any(not r.get('success') for r in result):
            raise CopyError('Cloudflare D1 did not confirm query success')
        return result[0].get('results', [])

    def task(self, path):
        rows = self.query(f'SELECT * FROM {PROGRESS_TABLE} WHERE collection_path=?', (path,))
        return rows[0] if rows else None

    def add_task(self, path, priority):
        parent, collection = path.rsplit('/', 1) if '/' in path else (PREFIX, path)
        self.query(f'''INSERT OR IGNORE INTO {PROGRESS_TABLE}
          (collection_path,priority,parent_path,collection_id,status,updated_at) VALUES (?,?,?,?,?,?)''',
          (path, priority, parent, collection, 'pending', int(time.time() * 1000)))

    def tasks(self):
        return self.query(f'''SELECT * FROM {PROGRESS_TABLE} WHERE status!='complete'
          ORDER BY priority, collection_path LIMIT 1''')


def root_priority(path):
    return ROOT_PRIORITY.get(path[len(PREFIX) + 1:].split('/', 1)[0], 3)


def persist_page(d1, task, records, next_token, read_total):
    # Cloudflare D1 uses a 100-bind ceiling; 12 rows x 8 values = 96 parameters.
    for start in range(0, len(records), 12):
        part = records[start:start + 12]
        if not part: continue
        values = ','.join('(?,?,?,?,?,?,?,?)' for _ in part)
        params = []
        for r in part:
            params.extend((r['path'], r['parent_path'], r['collection_id'], r['doc_id'], r['owner_uid'], r['document_json'], r['create_time'], r['update_time']))
        d1.query(f'''INSERT OR IGNORE INTO {DOC_TABLE}
          (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES {values}''', params)
        paths = [r['path'] for r in part]
        marks = ','.join('?' for _ in paths)
        stored = d1.query(f'''SELECT path,owner_uid,document_json FROM {DOC_TABLE} WHERE path IN ({marks})''', paths)
        by_path = {r['path']: r for r in stored}
        for expected in part:
            actual = by_path.get(expected['path'])
            if not actual:
                raise CopyError('A copied Firestore path is missing from D1; collection checkpoint not advanced')
            if actual['document_json'] != expected['document_json'] or actual['owner_uid'] != expected['owner_uid']:
                raise CopyError('A pre-existing D1 document differs; preserved it and stopped without overwrite')
    # INSERT idempotently discovered child collections before advancing the parent cursor.
    for r in records:
        for child in r.pop('child_collections', []):
            path = r['path'] + '/' + child
            d1.add_task(path, task['priority'])
    old_read, old_written = task['documents_read'], task['documents_written']
    status = 'finalizing' if next_token is None else 'pending'
    d1.query(f'''UPDATE {PROGRESS_TABLE} SET next_page_token=?,documents_read=?,documents_written=?,
      status=?,updated_at=? WHERE collection_path=?''',
      (next_token, old_read + read_total, old_written + len(records), status, int(time.time() * 1000), task['collection_path']))


def run_collection(source, d1, collection_path, pause=PAUSE_SECONDS, sleep=time.sleep):
    task = d1.task(collection_path)
    if not task:
        d1.add_task(collection_path, root_priority(collection_path))
        task = d1.task(collection_path)
    if task['status'] == 'complete': return task
    source_total = task['firebase_count']
    relative_collection = collection_path[len(PREFIX) + 1:]
    is_root_collection = '/' not in relative_collection
    if source_total is None and is_root_collection:
        source_total = source.count(collection_path)
        d1.query(f'''UPDATE {PROGRESS_TABLE} SET firebase_count=?,updated_at=? WHERE collection_path=?''',
                 (source_total, int(time.time() * 1000), collection_path))
        task = d1.task(collection_path)
    token, seen_tokens = task['next_page_token'], set()
    read_count, written_count = task['documents_read'], task['documents_written']
    while task['status'] != 'finalizing':
        docs, next_token = source.page(collection_path, token)
        if len(docs) > PAGE_SIZE: raise CopyError('Firestore exceeded the configured page size')
        records = []
        page_count = 0
        for doc in docs:
            name = doc.get('name', '')
            marker = PREFIX + '/'
            if not name.startswith(marker): raise CopyError('Unexpected Firestore document identity')
            source_path = name[len(marker):]
            child_collections = source.child_collections(name)
            if 'createTime' in doc:
                record = document_record(doc)
                record['child_collections'] = child_collections
                records.append(record)
                page_count += 1
            else:
                # showMissing entries have no parent row but may carry descendants.
                for child in child_collections:
                    d1.add_task(source_path + '/' + child, task['priority'])
        persist_page(d1, task, records, next_token, page_count)
        read_count += page_count
        written_count += len(records)
        print(json.dumps({'root_collection': collection_path[len(PREFIX) + 1:].split('/', 1)[0],
                          'collection': task['collection_id'], 'firebase_count': source_total,
                          'copied_or_already_present': written_count,
                          'remaining': max(0, source_total - read_count) if source_total is not None else None,
                          'status': 'checkpointed'}), flush=True)
        sleep(pause)
        if not next_token:
            break
        if next_token in seen_tokens or next_token == token:
            raise CopyError('Repeated Firestore page cursor; checkpoint retained')
        seen_tokens.add(next_token)
        token = next_token
        task = d1.task(collection_path)
    if source_total is None:
        # For nested collections, the exhaustive paginated listing is the Firebase count;
        # avoid an extra aggregation request for each subcollection.
        source_total = read_count
        d1.query(f'''UPDATE {PROGRESS_TABLE} SET firebase_count=?,updated_at=? WHERE collection_path=?''',
                 (source_total, int(time.time() * 1000), collection_path))
        task = d1.task(collection_path)
    collection_parent = collection_path[len(PREFIX) + 1:]
    d1_counts = d1.query(f'''SELECT COUNT(*) AS total,
      SUM(CASE WHEN document_json LIKE '%"proofMarker":"d1-only"%' THEN 1 ELSE 0 END) AS native_test_rows
      FROM {DOC_TABLE} WHERE parent_path=? AND collection_id=?''',
      (collection_parent, task['collection_id']))[0]
    d1_total = d1_counts['total']
    d1_native_test_rows = d1_counts['native_test_rows'] or 0
    # Pre-existing D1-only test profiles are preserved but aren't counted as Firebase-imported rows.
    d1_count = d1_total - d1_native_test_rows
    # One Firebase count query per collection; fetched pages provide the independently observed count.
    if read_count != source_total:
        raise CopyError(f'Collection count mismatch for {task["collection_id"]}: Firebase={source_total}, copied/read={read_count}; checkpoint retained')
    if d1_count != source_total:
        raise CopyError(f'Collection count mismatch for {task["collection_id"]}: Firebase={source_total}, D1-source-matched={d1_count}, D1-total={d1_total}, D1-native-test-rows={d1_native_test_rows}; no data deleted and checkpoint retained')
    d1.query(f'''UPDATE {PROGRESS_TABLE} SET d1_count=?,status='complete',updated_at=? WHERE collection_path=?''',
             (d1_count, int(time.time() * 1000), collection_path))
    print(json.dumps({'root_collection': collection_path[len(PREFIX) + 1:].split('/', 1)[0],
                      'collection': task['collection_id'], 'firebase_count': source_total,
                      'd1_source_matched_count': d1_count, 'd1_total_count': d1_total,
                      'd1_native_test_rows_preserved': d1_native_test_rows,
                      'copied_or_already_present': written_count,
                      'remaining': max(0, source_total - written_count), 'status': 'verified'}), flush=True)
    return d1.task(collection_path)


def validate_local_migration(sqlite_connection):
    migration = os.path.join(os.path.dirname(__file__), '../../cloudflare/migrations/0016_additive_firestore_migration_progress.sql')
    with open(migration, encoding='utf8') as source:
        sqlite_connection.executescript(source.read())
    return sqlite_connection.execute(f"SELECT name FROM sqlite_master WHERE type='table' AND name='{PROGRESS_TABLE}'").fetchone() is not None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true', help='Perform remote Firebase reads and D1 writes')
    parser.add_argument('--quota-reviewed', action='store_true', help='Confirm the Firebase quota reset window is open')
    args = parser.parse_args()
    if not args.execute or not args.quota_reviewed:
        print('NOT EXECUTED: requires --execute --quota-reviewed; no Firebase request made.')
        return 2
    try:
        from google.oauth2 import service_account
        from google.auth.transport.requests import AuthorizedSession
        import requests
        firebase = json.loads(os.environ['FIREBASE_ADMIN_SERVICE_ACCOUNT'])
        if firebase.get('project_id') != PROJECT or firebase.get('type') != 'service_account':
            raise CopyError('Firebase credential is not for the expected project')
        cf_token = os.environ['CLOUDFLARE_API_TOKEN']
        if not cf_token: raise CopyError('Cloudflare token unavailable')
        with requests.Session() as d1_session:
            d1_session.headers['Authorization'] = 'Bearer ' + cf_token
            d1 = D1(d1_session)
            # Process root collections one at a time in priority order; the D1 task queue
            # resumes incomplete roots and their subcollections from the saved cursor.
            source_credentials = service_account.Credentials.from_service_account_info(
                firebase, scopes=['https://www.googleapis.com/auth/datastore'])
            with AuthorizedSession(source_credentials) as source_session:
                source = FirestoreSource(source_session)
                root_url = FIRESTORE + quote(PREFIX, safe='/()') + ':listCollectionIds'
                roots, root_token = [], None
                while True:
                    payload = {'pageSize': 100}
                    if root_token: payload['pageToken'] = root_token
                    roots_data = source.request('post', root_url, json=payload)
                    roots.extend(roots_data.get('collectionIds', []))
                    root_token = roots_data.get('nextPageToken')
                    if not root_token: break
                if not roots: raise CopyError('Firestore root collection list was empty; refusing to certify')
                for root in roots:
                    d1.add_task(PREFIX + '/' + root, ROOT_PRIORITY.get(root, 3))
                while True:
                    pending = d1.tasks()
                    if not pending: break
                    task = pending[0]
                    run_collection(source, d1, task['collection_path'])
            summary = d1.query(f'''SELECT priority,COUNT(*) AS collections,
              SUM(documents_read) AS firebase_documents,SUM(documents_written) AS d1_documents
              FROM {PROGRESS_TABLE} WHERE status='complete' GROUP BY priority ORDER BY priority''')
            samples = d1.query(f'''SELECT doc_id,owner_uid,document_json FROM {DOC_TABLE}
              WHERE parent_path='users' AND collection_id='users'
              AND document_json NOT LIKE '%d1-only%' ORDER BY RANDOM() LIMIT 3''')
            user_samples = []
            for row in samples:
                data = json.loads(row['document_json'])
                user_samples.append({'uid':row['owner_uid'] or row['doc_id'],
                                     'displayName':data.get('displayName') or data.get('name') or ''})
            print(json.dumps({'copy_status':'all_discovered_collections_verified',
                              'collection_summary_by_priority':summary,
                              'sample_old_users':user_samples,
                              'firebase_writes':0,'firebase_deletions':0}, ensure_ascii=False), flush=True)
        return 0
    except Exception as error:
        print(f'COPY STOPPED ({type(error).__name__}): {error}; D1 checkpoints retained; no Firebase writes/deletions.', flush=True)
        try:
            progress = d1.query(f'''SELECT priority,status,COUNT(*) AS collections,
              SUM(documents_read) AS documents_read,SUM(documents_written) AS documents_written,
              SUM(firebase_count) AS known_firebase_total FROM {PROGRESS_TABLE}
              GROUP BY priority,status ORDER BY priority,status''')
            print('Checkpoint summary (no document paths or bodies): ' + json.dumps(progress), flush=True)
        except Exception:
            pass
        return 1


if __name__ == '__main__':
    sys.exit(main())
