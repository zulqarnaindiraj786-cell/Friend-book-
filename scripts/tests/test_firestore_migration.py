import importlib.util
from pathlib import Path
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('firestore_copy', ROOT / 'scripts/admin/migrate_firestore_to_d1.py')
copy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(copy)

class LocalD1:
    def __init__(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.executescript((ROOT / 'cloudflare/migrations/0014_additive_firestore_documents.sql').read_text())
        self.db.executescript((ROOT / 'cloudflare/migrations/0016_additive_firestore_migration_progress.sql').read_text())
    def query(self, sql, params=()):
        cur = self.db.execute(sql, params)
        if sql.lstrip().upper().startswith(('INSERT','UPDATE','CREATE')):
            self.db.commit()
            return []
        return [dict(r) for r in cur.fetchall()]
    def task(self, path):
        rows = self.query(f'SELECT * FROM {copy.PROGRESS_TABLE} WHERE collection_path=?', (path,))
        return rows[0] if rows else None
    def add_task(self, path, priority):
        parent, collection = path.rsplit('/', 1)
        self.query(f'''INSERT OR IGNORE INTO {copy.PROGRESS_TABLE}
          (collection_path,priority,parent_path,collection_id,status,updated_at) VALUES (?,?,?,?,?,?)''',
          (path, priority, parent, collection, 'pending', 0))
    def tasks(self):
        return self.query(f"SELECT * FROM {copy.PROGRESS_TABLE} WHERE status!='complete' ORDER BY priority,collection_path LIMIT 1")

class FakeSource:
    def __init__(self, pages, total): self.pages, self.total, self.calls = pages, total, []
    def count(self, path): self.calls.append(('count', path)); return self.total
    def page(self, path, token):
        self.calls.append(('page', path, token))
        return self.pages[token or 'first']
    def child_collections(self, doc): self.calls.append(('children', doc)); return []

class FakeResponse:
    def __init__(self, status, body): self.status_code, self.body = status, body
    def json(self): return self.body
class RetrySession:
    def __init__(self): self.calls = 0
    def get(self, url, **kwargs):
        self.calls += 1
        if self.calls == 1: return FakeResponse(429, {'error': {'status':'RESOURCE_EXHAUSTED'}})
        return FakeResponse(200, {'documents': []})

class FirestoreMigrationTests(unittest.TestCase):
    def test_firestore_typed_values_convert_to_worker_plain_document_shape(self):
        self.assertEqual(copy.firestore_fields({
            'n': {'integerValue':'12'}, 'name': {'stringValue':'Rae'},
            'flag': {'booleanValue':True}, 'nested': {'mapValue':{'fields':{'x':{'stringValue':'y'}}}},
            'arr': {'arrayValue':{'values':[{'doubleValue':1.5},{'nullValue':None}]}}}),
            {'n':12,'name':'Rae','flag':True,'nested':{'x':'y'},'arr':[1.5,None]})

    def test_paged_collection_checkpoint_and_exact_counts(self):
        d1 = LocalD1()
        for i in range(3):
            uid = f'native-test-{i}'
            d1.db.execute(f'''INSERT INTO {copy.DOC_TABLE}
              (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
              VALUES(?,?,?,?,?,?,0,0)''', (f'users/{uid}','users','users',uid,uid,'{"proofMarker":"d1-only","uid":"'+uid+'"}'))
        d1.db.commit()
        root = copy.PREFIX + '/users'
        d1.add_task(root, 0)
        docs = [
            {'name':copy.PREFIX+f'/users/u{i}','createTime':f'2026-11-10T00:00:{i:02d}Z','fields':{'uid':{'stringValue':f'u{i}'}}}
            for i in range(13)
        ]
        source = FakeSource({'first':(docs[:12],'cursor-2'),'cursor-2':(docs[12:],None)},13)
        sleeps=[]
        final = copy.run_collection(source,d1,root,pause=copy.PAUSE_SECONDS,sleep=sleeps.append)
        self.assertEqual(final['status'],'complete')
        self.assertEqual(final['documents_read'],13)
        self.assertEqual(final['documents_written'],13)
        self.assertEqual(final['firebase_count'],13)
        self.assertEqual(final['d1_count'],13)
        self.assertEqual(sleeps,[copy.PAUSE_SECONDS,copy.PAUSE_SECONDS])
        rows=d1.query(f"SELECT path,owner_uid FROM {copy.DOC_TABLE} WHERE path LIKE 'users/u%' ORDER BY path")
        self.assertEqual(len(rows),13)
        total=d1.query(f'SELECT COUNT(*) AS total FROM {copy.DOC_TABLE} WHERE parent_path=? AND collection_id=?',('users','users'))[0]['total']
        self.assertEqual(total,16)
        self.assertEqual(rows[0],{'path':'users/u0','owner_uid':'u0'})
        self.assertEqual(rows[-1],{'path':'users/u9','owner_uid':'u9'})

    def test_quota_429_waits_ten_minutes_then_retries_same_page(self):
        waits=[]
        source=copy.FirestoreSource(RetrySession(),sleep=waits.append)
        docs, token=source.page(copy.PREFIX+'/users',None)
        self.assertEqual(docs,[])
        self.assertIsNone(token)
        self.assertEqual(waits,[600])

    def test_local_progress_migration_is_repeatable_and_preserves_existing_data(self):
        db=sqlite3.connect(':memory:')
        self.assertTrue(copy.validate_local_migration(db))
        db.execute(f"INSERT INTO {copy.PROGRESS_TABLE}(collection_path,parent_path,collection_id,status) VALUES('p','d','users','pending')")
        self.assertTrue(copy.validate_local_migration(db))
        self.assertEqual(db.execute(f'SELECT COUNT(*) FROM {copy.PROGRESS_TABLE}').fetchone()[0],1)

if __name__ == '__main__': unittest.main()
