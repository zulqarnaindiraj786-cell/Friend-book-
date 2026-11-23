import {canonicalSeed} from './fixtures/canonical.js';
import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const migration=n=>readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8');
function fixture() {
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0018_social_guards.sql','0019_trusted_friendships.sql']) sql.exec(migration(n));
  const insert=sql.prepare('INSERT OR REPLACE INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const put=(path,doc)=>{const [root,id]=path.split('/');insert.run(path,root,root,id,id,JSON.stringify(doc),1,1);};
  put('users/alice',{uid:'alice',friendsCount:0});
  put('users/bob',{uid:'bob',friendsCount:0});
  put('friend_requests/r',{fromUid:'alice',toUid:'bob',status:'pending'});
  let beforeBatch=null,guardStatements=0;
  const db={prepare(q){const s=sql.prepare(q);return {bind(...args){return {
    run:async()=>{if(q.startsWith('INSERT INTO friendbook_cf_social_guards'))guardStatements++;return s.run(...args)},
    all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null,
  };}}},async batch(statements){if(beforeBatch){const f=beforeBatch;beforeBatch=null;f();}sql.exec('BEGIN');try{for(const s of statements)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token), env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const ops=[{op:'update',path:'friend_requests/r',updates:{status:'accepted'}},
    {op:'set',path:'friends/new',data:{participants:['bob','alice'],createdAt:{__fieldValue:'serverTimestamp'}}},
    ...['alice','bob'].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:1}}}))];
  const call=(uid='bob')=>app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify({operations:ops})}),env);
  return {sql,put,call,race:f=>{beforeBatch=f},guards:()=>guardStatements};
}

test('friend acceptance combines transactional assertions into one temporary row',async()=>{
  const f=fixture();
  assert.equal((await f.call('alice')).status,403);
  assert.equal((await f.call()).status,200);
  assert.equal(f.guards(),1);
  assert.equal(f.sql.prepare('SELECT count(*) AS n FROM friendbook_cf_social_guards').get().n,0);
  assert.equal(JSON.parse(f.sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='users/alice'").get().document_json).friendsCount,1);
  assert.equal(JSON.parse(f.sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='friend_requests/r'").get().document_json).status,'accepted');
  assert.equal((await f.call()).status,403);
  f.sql.close();
});

test('friend acceptance refuses snapshot, duplicate, path and mirror races atomically',async()=>{
  for(const kind of ['request','user','duplicate','canonical-path','legacy-path','mirror-failure']) {
    const f=fixture();
    f.race(()=>{
      if(kind==='request')f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.status','declined') WHERE path='friend_requests/r'").run();
      if(kind==='user')f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.blocked',json('[\"alice\"]')) WHERE path='users/bob'").run();
      if(kind==='duplicate')f.put('friends/race',{participants:['eve','bob','alice']});
      if(kind==='canonical-path')f.put('friends/new',{participants:['eve','mallory']});
      if(kind==='legacy-path')canonicalSeed(f.sql).run('friends/new','friends','new','eve',JSON.stringify({participants:['eve','mallory']}),1);
      if(kind==='mirror-failure')f.sql.exec("CREATE TRIGGER reject_friend BEFORE INSERT ON friendbook_cf_documents WHEN NEW.path='friends/new' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
    });
    assert.equal((await f.call()).status,409,kind);
    assert.equal(f.sql.prepare('SELECT count(*) AS n FROM friendbook_cf_trusted_friendships').get().n,0,kind);
    assert.equal(f.sql.prepare('SELECT count(*) AS n FROM friendbook_cf_social_guards').get().n,0,kind);
    assert.equal(JSON.parse(f.sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='users/alice'").get().document_json).friendsCount,0,kind);
    if(kind!=='request')assert.equal(JSON.parse(f.sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='friend_requests/r'").get().document_json).status,'pending',kind);
    if(!['canonical-path','legacy-path'].includes(kind))assert.equal(f.sql.prepare("SELECT path FROM friendbook_cf_firestore_documents WHERE path='friends/new'").get(),undefined,kind);
    f.sql.close();
  }
});
