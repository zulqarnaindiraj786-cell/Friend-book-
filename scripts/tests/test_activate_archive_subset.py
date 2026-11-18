import hashlib, json, sqlite3, sys, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/admin'))
import activate_archive_subset as a

class DB:
 def __init__(self):
  self.db=sqlite3.connect(':memory:');self.db.row_factory=sqlite3.Row
  for file in ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql']:
   self.db.executescript((ROOT/'cloudflare/migrations'/file).read_text())
  self.db.execute('CREATE TABLE friendbook_cf_fullcopy_archive(path TEXT PRIMARY KEY,document_json TEXT,sha256 TEXT)')
 def query(self,sql,params=()):
  cur=self.db.execute(sql,params);result=[dict(r) for r in cur.fetchall()];self.db.commit();return result
 def archive(self,path,data):
  raw={'name':a.PREFIX+'/'+path,'createTime':'2026-01-01T00:00:00Z','updateTime':'2026-01-02T00:00:00Z','fields':data}
  text=json.dumps(raw,sort_keys=True,separators=(',',':'),ensure_ascii=False)
  self.query('INSERT INTO friendbook_cf_fullcopy_archive VALUES (?,?,?)',(path,text,hashlib.sha256(text.encode()).hexdigest()))

def s(x): return {'stringValue':x}
def arr(*x): return {'arrayValue':{'values':[s(v) for v in x]}}
class ActivationTests(unittest.TestCase):
 def test_preserves_existing_rows_restores_supported_and_is_idempotent(self):
  db=DB()
  db.query('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',('users/existing','users','existing','existing','{"uid":"existing","displayName":"current"}',1))
  db.query('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',('chats/live','chats','live','alice','{"participants":["alice","bob"]}',1))
  db.archive('users/existing',{'uid':s('existing'),'displayName':s('old')})
  db.archive('users/alice',{'uid':s('alice'),'displayName':s('Alice')})
  db.archive('chats/new',{'participants':arr('alice','bob')})
  db.archive('chats/live/messages/m1',{'senderId':s('alice'),'createdAt':{'timestampValue':'2026-01-01T00:00:00Z'}})
  db.archive('chats/new/messages/m2',{'senderId':s('bob'),'text':s('restored')})
  db.archive('chats/missing/messages/m3',{'senderId':s('alice')})
  db.archive('friend_requests/r1',{'fromUid':s('alice'),'toUid':s('bob'),'status':s('pending')})
  db.archive('friend_requests/r2',{'fromUid':s('alice'),'toUid':s('bob'),'status':s('pending')})
  db.archive('friends/f1',{'participants':arr('alice','bob')})
  db.archive('authVault/secret',{'secret':s('secret')})
  stats=a.activate(db)
  self.assertEqual(stats['messages_inserted'],2);self.assertEqual(stats['messages_not_inserted'],1)
  self.assertEqual(stats['friend_requests_inserted'],1);self.assertEqual(stats['friend_requests_duplicate_pending'],1)
  self.assertEqual(db.query("SELECT count(*) n FROM friendbook_cf_firestore_documents WHERE path='users/existing'")[0]['n'],0)
  self.assertEqual(db.query("SELECT document_json FROM friendbook_cf_documents WHERE path='users/existing'")[0]['document_json'],'{"uid":"existing","displayName":"current"}')
  message=json.loads(db.query("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='chats/live/messages/m1'")[0]['document_json'])
  self.assertEqual(message['createdAt'],{'__type':'timestamp','millis':1767225600000})
  self.assertFalse(db.query("SELECT path FROM friendbook_cf_firestore_documents WHERE path='authVault/secret'"))
  before=db.query('SELECT count(*) n FROM friendbook_cf_firestore_documents')[0]['n'];a.activate(db)
  self.assertEqual(db.query('SELECT count(*) n FROM friendbook_cf_firestore_documents')[0]['n'],before)
 def test_hash_bad_types_or_other_paths_never_insert(self):
  db=DB();db.archive('users/u',{'uid':s('u'),'photo':{'bytesValue':'bad'}})
  db.archive('chats/x/not_messages/m',{'senderId':s('u')})
  stats=a.activate(db);self.assertEqual(stats['users_invalid_or_unsupported'],1)
  self.assertEqual(db.query('SELECT count(*) n FROM friendbook_cf_firestore_documents')[0]['n'],0)
  text='{}'
  with self.assertRaises(ValueError):a.record('users/u',text,'wrong','users')
 def test_existing_request_pair_not_duplicated(self):
  db=DB(); db.query('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',('friend_requests/live','friend_requests','live','alice',json.dumps({'fromUid':'alice','toUid':'bob','status':'pending'}),1))
  db.archive('friend_requests/old',{'fromUid':s('alice'),'toUid':s('bob'),'status':s('pending')})
  self.assertEqual(a.activate(db)['friend_requests_not_inserted'],1)
  self.assertEqual(db.query('SELECT count(*) n FROM friendbook_cf_firestore_documents')[0]['n'],0)
if __name__=='__main__':unittest.main()
