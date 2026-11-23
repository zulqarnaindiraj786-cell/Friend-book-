import {canonicalSeed} from './fixtures/canonical.js';
import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const migration=n=>readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8');
function fixture() {
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0018_social_guards.sql','0023_friend_request_lookup_indexes.sql'])sql.exec(migration(n));
  const canonical=sql.prepare('INSERT OR REPLACE INTO friendbook_cf_firestore_documents VALUES(?,?,?,?,?,?,?,?)');
  const legacy=canonicalSeed(sql);
  for(const uid of ['alice','bob','carol'])canonical.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid}),1,1);
  let beforeBatch=null, inserts=0, guards=0;
  const db={prepare(q){const stmt=sql.prepare(q);return {bind(...a){return {
    run:async()=>{if(q.includes('INSERT INTO friendbook_cf_social_guards'))guards++;if(q.includes('INSERT INTO friendbook_cf_firestore_documents'))inserts++;return stmt.run(...a)},
    all:async()=>({results:stmt.all(...a)}),first:async()=>stmt.get(...a)||null,
  };}}},async batch(stmts){if(beforeBatch){const f=beforeBatch;beforeBatch=null;f();}sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const send=(to='bob',uid='alice',from='alice')=>app.fetch(new Request('https://example.test/v1/firestore/document?parent=friend_requests',{
    method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify({data:{fromUid:from,toUid:to,status:'pending'}})}),env);
  const add=(store,id,doc)=>{const path=`friend_requests/${id}`;if(store==='canonical')canonical.run(path,'friend_requests','friend_requests',id,'alice',JSON.stringify(doc),1,1);else legacy.run(path,'friend_requests',id,'alice',JSON.stringify(doc),1);};
  return {sql,send,add,race:f=>{beforeBatch=f},guards:()=>guards,inserts:()=>inserts};
}
const pending={fromUid:'alice',toUid:'bob',status:'pending'};
const wrapped={fields:{fromUid:{stringValue:'alice'},toUid:{stringValue:'bob'},status:{stringValue:'pending'}}};

test('friend request create skips temporary guard rows and keeps authorization and mirrors',async()=>{
  const f=fixture();
  assert.equal((await f.send('bob','carol','alice')).status,403);
  const res=await f.send();assert.equal(res.status,201);const data=await res.json();
  assert.equal(data.data.status,'pending');assert.equal(f.guards(),0);assert.equal(f.inserts(),1);
  const c=f.sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(data.path);
  const l=f.sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').get(data.path);
  assert.deepEqual(JSON.parse(c.document_json),pending);assert.equal(JSON.parse(l.document_json).id,data.id);
  assert.equal((await f.send()).status,409);
  assert.equal((await f.send('carol')).status,201);
  f.sql.close();
});

test('pending duplicates, historical envelopes and canonical shadowing remain intact',async()=>{
  for(const [store,doc] of [['canonical',pending],['canonical',wrapped],['legacy',pending],['legacy',wrapped]]){
    const f=fixture();f.add(store,'r',doc);assert.equal((await f.send()).status,409,store);f.sql.close();
  }
  const f=fixture();f.add('legacy','r',wrapped);f.add('canonical','r',{...pending,status:'accepted'});
  assert.equal((await f.send()).status,201,'canonical accepted state shadows stale pending legacy mirror');
  f.sql.close();
});

test('transactional duplicates and mirror errors roll back, without swallowing storage errors',async()=>{
  for(const kind of ['canonical','legacy','mirror-failure']){
    const f=fixture();f.race(()=>{
      if(kind==='canonical')f.add('canonical','r',pending);
      if(kind==='legacy')f.add('legacy','r',wrapped);
      if(kind==='mirror-failure')f.sql.exec("CREATE TRIGGER reject_request BEFORE INSERT ON friendbook_cf_documents WHEN NEW.collection='friend_requests' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
    });
    const response=await f.send();assert.equal(response.status,kind==='mirror-failure'?503:409,kind);
    const c=f.sql.prepare("SELECT count(*) n FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests'").get().n;
    const l=f.sql.prepare("SELECT count(*) n FROM friendbook_cf_documents WHERE collection='friend_requests'").get().n;
    assert.equal(c,kind==='mirror-failure'?0:1,kind);assert.equal(l,0,kind);
    f.sql.close();
  }
});
