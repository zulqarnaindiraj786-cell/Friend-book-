import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';

const migration=readFileSync(new URL('../migrations/0109_archive_reappeared_document_mirror.sql',import.meta.url),'utf8');

test('0109 archives the reappeared mirror by rename without changing rows or schema',()=>{
 const db=new DatabaseSync(':memory:');
 try{
  db.exec(`CREATE TABLE friendbook_cf_documents(path TEXT PRIMARY KEY,collection TEXT,doc_id TEXT,owner_uid TEXT,document_json TEXT,updated_at INTEGER);
    CREATE INDEX ix_mirror_collection ON friendbook_cf_documents(collection,updated_at);
    CREATE TABLE friendbook_cf_documents_retired_backup(path TEXT);
    CREATE TABLE friendbook_cf_documents_reappeared_0106(path TEXT);
    INSERT INTO friendbook_cf_documents VALUES('users/a','users','a','a','{"uid":"a"}',4);
    INSERT INTO friendbook_cf_documents VALUES('posts/b','posts','b','a','{"id":"b"}',9);
    CREATE TRIGGER mirror_archive_audit AFTER UPDATE ON friendbook_cf_documents BEGIN SELECT 1; END;`);
  db.exec(migration);
  assert.equal(db.prepare("SELECT COUNT(*) n FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents'").get().n,0);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM friendbook_cf_documents_reappeared_archive_0109').get().n,2);
  assert.deepEqual(db.prepare('SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents_reappeared_archive_0109 ORDER BY path').all().map(row=>({...row})),[
   {path:'posts/b',collection:'posts',doc_id:'b',owner_uid:'a',document_json:'{"id":"b"}',updated_at:9},
   {path:'users/a',collection:'users',doc_id:'a',owner_uid:'a',document_json:'{"uid":"a"}',updated_at:4},
  ]);
  assert.equal(db.prepare("SELECT COUNT(*) n FROM sqlite_master WHERE type='index' AND name='ix_mirror_collection' AND tbl_name='friendbook_cf_documents_reappeared_archive_0109'").get().n,1);
  assert.equal(db.prepare("SELECT COUNT(*) n FROM sqlite_master WHERE type='trigger' AND name='mirror_archive_audit' AND tbl_name='friendbook_cf_documents_reappeared_archive_0109'").get().n,1);
  db.exec('ALTER TABLE friendbook_cf_documents_reappeared_archive_0109 RENAME TO friendbook_cf_documents');
  assert.equal(db.prepare('SELECT COUNT(*) n FROM friendbook_cf_documents').get().n,2,'reverse rename restores exact data');
 } finally { db.close(); }
});
