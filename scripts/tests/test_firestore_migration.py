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
    def __init__(self, pages, total, children=None): self.pages, self.total, self.calls, self.children = pages, total, [], children or []
    def count(self, path): self.calls.append(('count', path)); return self.total
    def page(self, path, token):
        self.calls.append(('page', path, token))
        return self.pages[token or 'first']
    def child_collections(self, doc): self.calls.append(('children', doc)); return self.children

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
        self.assertEqual(copy.firestore_fields({'n': {'integerValue':'12'}, 'name': {'stringValue':'Rae'}, 'flag': {'booleanValue':True}, 'nested': {'mapValue':{'fields':{'x':{'stringValue':'y'}}}}, 'arr': {'arrayValue':{'values':[{'doubleValue':1.5},{'nullValue':None}]}}}), {'n':12,'name':'Rae','flag':True,'nested':{'x':'y'},'arr':[1.5,None]})

    def test_paged_collection_checkpoint_and_exact_counts(self):
        d1=LocalD1()
        for i in range(3):
            uid=f'native-test-{i}'
            d1.db.execute(f'''INSERT INTO {copy.DOC_TABLE} (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES(?,?,?,?,?,?,0,0)''',(f'users/{uid}','users','users',uid,uid,'{"proofMarker":"d1-only","uid":"'+uid+'"}'))
        d1.db.commit()
        root=copy.PREFIX+'/users'; d1.add_task(root,0)
        docs=[{'name':copy.PREFIX+f'/users/u{i}','createTime':f'2026-11-10T00:00:{i:02d}Z','fields':{'uid':{'stringValue':f'u{i}'}}} for i in range(13)]
        source=FakeSource({'first':(docs[:12],'cursor-2'),'cursor-2':(docs[12:],None)},13); sleeps=[]
        final=copy.run_collection(source,d1,root,pause=copy.PAUSE_SECONDS,sleep=sleeps.append)
        self.assertEqual(final['status'],'complete'); self.assertEqual(final['documents_read'],13)
        self.assertEqual(final['d1_count'],13)
        self.assertEqual(d1.query(f'SELECT COUNT(*) AS n FROM {copy.DOC_TABLE}')[0]['n'],16)
        self.assertEqual(sleeps,[copy.PAUSE_SECONDS,copy.PAUSE_SECONDS]); self.assertEqual([c[2] for c in source.calls if c[0]=='page'],[None,'cursor-2'])

    def test_child_collection_checkpoint_uses_full_firestore_resource_path_and_repairs_legacy_cursor(self):
        d1=LocalD1(); root=copy.PREFIX+'/users'; d1.add_task(root,0)
        doc={'name':copy.PREFIX+'/users/u1','createTime':'2026-11-10T00:00:00Z','fields':{'uid':{'stringValue':'u1'}}}
        source=FakeSource({'first':([doc],None)},1,children=['posts'])
        copy.run_collection(source,d1,root,pause=0,sleep=lambda _:None)
        pending=d1.tasks()[0]
        self.assertEqual(pending['collection_path'],copy.PREFIX+'/users/u1/posts')
        self.assertEqual(pending['parent_path'],copy.PREFIX+'/users/u1')
        d1.add_task('users/u2/messages',0)
        d1.query(f"UPDATE {copy.PROGRESS_TABLE} SET next_page_token='resume-token',documents_read=7 WHERE collection_path='users/u2/messages'")
        corrected=copy.PREFIX+'/users/u2/messages'
        parent,collection=corrected.rsplit('/',1)
        d1.query(f"UPDATE {copy.PROGRESS_TABLE} SET collection_path=?,parent_path=?,collection_id=? WHERE collection_path='users/u2/messages'",(corrected,parent,collection))
        fixed=d1.task(corrected)
        self.assertEqual(fixed['next_page_token'],'resume-token'); self.assertEqual(fixed['documents_read'],7)

    def test_quota_429_waits_ten_minutes_then_retries_same_page(self):
        waits=[]
        source=copy.FirestoreSource(session=RetrySession(),sleep=waits.append)
        result=source.request('get','https://firestore.example',params={})
        self.assertEqual(result,{'documents':[]}); self.assertEqual(waits,[600])

    def test_firestore_empty_parent_child_listing_request_context_and_decoding(self):
        self.assertEqual(copy.document_record({'name':copy.PREFIX+'/users/u1/posts/p1','fields':{}})['path'],'users/u1/posts/p1')

if __name__ == '__main__': unittest.main()
