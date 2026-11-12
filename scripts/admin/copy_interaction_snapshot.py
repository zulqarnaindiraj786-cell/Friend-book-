"""Owner-authorized, INSERT-only raw snapshot backup. NOT an application cutover.
Firebase calls are read-only, at one readTime. Private rows never enter logs/artifacts.
"""
import hashlib
import json
import os
from pathlib import Path
import sys
import uuid
from urllib.parse import quote

PROJECT = 'friendbook-17551'
PREFIX = f'projects/{PROJECT}/databases/(default)/documents'
FIRESTORE = 'https://firestore.googleapis.com/v1/'
D1 = 'https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query'
TABLE = 'friendbook_cf_copy_staging'
ROOTS = ('posts', 'reels', 'comments', 'reactions', 'likes')


class BackupError(RuntimeError):
    pass


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False)


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def checked(response, service='Remote API'):
    if response.status_code != 200:
        raise BackupError(f'{service} request failed HTTP {response.status_code}; private response suppressed')
    return response.json()


class Snapshot:
    def __init__(self, session):
        self.session = session
        self.time = None
        self.docs = {}
        self.counts = {}
        self.visited = set()

    def count(self, parent, collection):
        body = {'structuredAggregationQuery': {
            'structuredQuery': {'from': [{'collectionId': collection}]},
            'aggregations': [{'alias': 'total', 'count': {}}]}}
        if self.time:
            body['readTime'] = self.time
        rows = checked(self.session.post(FIRESTORE + quote(parent, safe='/()') + ':runAggregationQuery', json=body, timeout=90), 'Firebase snapshot read')
        results = [r for r in rows if r.get('result')]
        if len(results) != 1:
            raise BackupError('Unexpected authoritative count response')
        if not self.time:
            self.time = results[0]['readTime']
        return int(results[0]['result']['aggregateFields']['total']['integerValue'])

    def collections(self, parent):
        token = None
        seen = set()
        while True:
            body = {'pageSize': 100, 'readTime': self.time}
            if token:
                body['pageToken'] = token
            data = checked(self.session.post(FIRESTORE + quote(parent, safe='/()') + ':listCollectionIds', json=body, timeout=90), 'Firebase snapshot read')
            yield from data.get('collectionIds', [])
            token = data.get('nextPageToken')
            if not token:
                return
            if token in seen:
                raise BackupError('Repeated collection pagination token')
            seen.add(token)

    def walk(self, parent, collection):
        path = parent + '/' + collection
        if path in self.visited:
            raise BackupError('Duplicate collection traversal')
        self.visited.add(path)
        expected = self.count(parent, collection)
        actual = 0
        token = None
        seen = set()
        while True:
            params = {'pageSize': 100, 'showMissing': 'true', 'readTime': self.time}
            if token:
                params['pageToken'] = token
            data = checked(self.session.get(FIRESTORE + quote(path, safe='/()'), params=params, timeout=90), 'Firebase document listing')
            for document in data.get('documents', []):
                name = document.get('name', '')
                if not name.startswith(path + '/') or '/' in name[len(path) + 1:]:
                    raise BackupError('Unexpected source document identity')
                # showMissing exposes orphaned subcollections without inventing a parent.
                if 'createTime' in document:
                    if name in self.docs or not isinstance(document.get('fields', {}), dict):
                        raise BackupError('Duplicate or malformed source document')
                    text = canonical(document)
                    if len(text.encode()) > 1_800_000:
                        raise BackupError('Oversized source document: stopped without truncation')
                    self.docs[name] = text
                    actual += 1
                for child in self.collections(name):
                    self.walk(name, child)
            token = data.get('nextPageToken')
            if not token:
                break
            if token in seen:
                raise BackupError('Repeated document pagination token')
            seen.add(token)
        if actual != expected:
            raise BackupError('Snapshot count mismatch: cutover forbidden')
        self.counts[path] = actual

    def export(self):
        self.count(PREFIX, ROOTS[0])  # fixes one authoritative readTime
        for root in ROOTS:
            self.walk(PREFIX, root)
        if not self.docs:
            raise BackupError('Refusing to certify an empty interaction backup')
        return self.docs


def d1_query(session, sql, params=()):
    payload = checked(session.post(D1, json={'sql': sql, 'params': list(params)}, timeout=90), 'Cloudflare D1 backup')
    if not payload.get('success'):
        raise BackupError('D1 operation failed; private response suppressed')
    results = payload.get('result', [])
    if not results or any(not r.get('success') for r in results):
        raise BackupError('D1 query did not report success')
    return results[0].get('results', [])


def copy_and_verify(session, batch, docs):
    # Additive table only, same definition as optional 0002; no migrations applied.
    d1_query(session, f'''CREATE TABLE IF NOT EXISTS {TABLE} (
        batch_id TEXT NOT NULL, path TEXT NOT NULL,
        document_json TEXT NOT NULL CHECK(json_valid(document_json)),
        sha256 TEXT NOT NULL, PRIMARY KEY(batch_id,path))''')
    for name, text in sorted(docs.items()):
        # INSERT only. Never replace/update/delete an earlier record or batch.
        d1_query(session, f'INSERT INTO {TABLE} (batch_id,path,document_json,sha256) VALUES (?,?,?,?)',
                 (batch, name[len(PREFIX) + 1:], text, digest(text)))
    actual = {}
    after = ''
    while True:
        rows = d1_query(session, f'SELECT path,document_json,sha256 FROM {TABLE} WHERE batch_id=? AND path>? ORDER BY path LIMIT 100', (batch, after))
        if not rows:
            break
        for row in rows:
            name = PREFIX + '/' + row['path']
            text = row['document_json']
            if name in actual or name not in docs or text != docs[name] or row['sha256'] != digest(text):
                raise BackupError('D1 identity/content/hash mismatch: activation forbidden')
            actual[name] = text
        new_after = rows[-1]['path']
        if new_after <= after:
            raise BackupError('D1 verification pagination did not advance')
        after = new_after
    if actual != docs:
        raise BackupError('D1 backup incomplete: activation forbidden')


def main():
    try:
        from google.oauth2 import service_account
        from google.auth.transport.requests import AuthorizedSession
        import requests
        info = json.loads(os.environ['FIREBASE_ADMIN_SERVICE_ACCOUNT'])
        if info.get('project_id') != PROJECT or info.get('type') != 'service_account':
            raise BackupError('Wrong Firebase project or credential type')
        token = os.environ['CLOUDFLARE_API_TOKEN']
        if not token:
            raise BackupError('Cloudflare secret unavailable')
        # Confirm D1 query permission before spending reads on a full snapshot.
        with requests.Session() as probe:
            probe.headers['Authorization'] = 'Bearer ' + token
            d1_query(probe, 'SELECT 1 AS connected')
        print('::notice::Cloudflare D1 read-only preflight passed.')
        credentials = service_account.Credentials.from_service_account_info(info, scopes=['https://www.googleapis.com/auth/datastore'])
        batch = str(uuid.uuid4())
        print('Backup batch: ' + batch)
        print('Reading interactions at one Firestore snapshot time (no Firebase writes).')
        with AuthorizedSession(credentials) as source:
            snapshot = Snapshot(source)
            docs = snapshot.export()
        # Private local backup never uploaded as a public Actions artifact.
        directory = Path('migration-artifacts') / batch
        directory.mkdir(parents=True, mode=0o700)
        with (directory / 'source.jsonl').open('x', encoding='utf8') as output:
            os.chmod(output.name, 0o600)
            for name in sorted(docs):
                output.write(docs[name] + '\n')
        print('::notice::Source collection counts verified; starting INSERT-only D1 staging backup.')
        with requests.Session() as destination:
            destination.headers['Authorization'] = 'Bearer ' + token
            copy_and_verify(destination, batch, docs)
        summary = {'batch': batch, 'read_time': snapshot.time,
                   'documents_verified': len(docs), 'collection_paths_verified': len(snapshot.counts),
                   'backup_verified': True, 'safe_to_cutover': False,
                   'normalized_import': False, 'firebase_writes': 0, 'deletions': 0}
        print(canonical(summary))
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
                out.write('Raw interaction snapshot backup verified (not normalized/cutover-ready).\n\n```json\n' + canonical(summary) + '\n```\n')
        return 0
    except Exception as error:
        if isinstance(error, BackupError):
            print('::error::' + str(error))
        # Partial batches remain intact for diagnosis; never clear them on retry.
        print(f'::error::Snapshot backup stopped ({type(error).__name__}). No Firebase writes/deletions; keep switches OFF. Private error details suppressed.')
        return 1


if __name__ == '__main__':
    sys.exit(main())
