import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const migration=n=>readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8');
function fixture({count=1,legacyOnly=false,indexed=true}={}) {
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0018_social_guards.sql','0019_trusted_friendships.sql','0027_friend_participant_indexes.sql','0084_friend_guard_exceptions.sql'])sql.exec(migration(n));
  const c=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES(?,?,?,?,?,?,?,?)');
  const l=sql.prepare('INSERT INTO friendbook_cf_documents VALUES(?,?,?,?,?,?)');
  const put=(path,doc)=>{const [coll,id]=path.split('/');c.run(path,coll,coll,id,'',JSON.stringify(doc),1,1);};
  const old=(path,doc)=>{const [coll,id]=path.split('/');l.run(path,coll,id,'',JSON.stringify(doc),1);};
  for(const uid of ['alice','bob'])put(`users/${uid}`,{uid,friendsCount:count});
  for(let i=0;i<count;i++) {
    const path=`friends/f${i}`,doc={participants:['alice','bob']};
    if(!legacyOnly)put(path,doc);old(path,doc);
  }
  sql.prepare('INSERT INTO friendbook_cf_trusted_friendships VALUES(?,?,?)').run('alice','bob','friends/f0');
  let beforeBatch=null,guardInserts=0;
  const db={prepare(q){const s=sql.prepare(q);return{bind(...a){return{
    run:async()=>{if(q.includes('INSERT INTO friendbook_cf_social_guards'))guardInserts++;return s.run(...a)},
    all:async()=>({results:s.all(...a)}),first:async()=>s.get(...a)||null,
  };}}},async batch(stmts){if(beforeBatch){const f=beforeBatch;beforeBatch=null;f();}sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test',FRIEND_GUARD_INDEXED:indexed?'true':'false'};
  const ops=[...Array.from({length:count},(_,i)=>({op:'delete',path:`friends/f${i}`})),
    ...['alice','bob'].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:-count}}}))];
  const call=(uid='bob')=>app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify({operations:ops})}),env);
  return {sql,put,old,call,race:f=>{beforeBatch=f},guards:()=>guardInserts};
}

test('one-friend removal uses one guard and preserves compatibility',async()=>{
  for(const [indexed,legacyOnly] of [[true,false],[false,false],[true,true],[false,true]]){
    const f=fixture({indexed,legacyOnly});
    assert.equal((await f.call('mallory')).status,403);
    assert.equal((await f.call()).status,200);
    assert.equal(f.guards(),1);
    assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_social_guards').get().n,0);
    assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_trusted_friendships').get().n,0);
    assert.equal(JSON.parse(f.sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='users/alice'").get().document_json).friendsCount,0);
    f.sql.close();
  }
});

test('unfriend still rejects raced snapshots, historical duplicates and mirror errors atomically',async()=>{
  for(const kind of ['profile','friend','canonical-duplicate','legacy-duplicate','mirror-failure']){
    const f=fixture();f.race(()=>{
      if(kind==='profile')f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.bio','race') WHERE path='users/alice'").run();
      if(kind==='friend')f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.participants',json('[\"bob\",\"eve\"]')) WHERE path='friends/f0'").run();
      if(kind==='canonical-duplicate')f.put('friends/race',{participants:['bob','alice']});
      if(kind==='legacy-duplicate')f.old('friends/race',{fields:{participants:{arrayValue:{values:[{stringValue:'alice'},{stringValue:'bob'}]}}}});
      if(kind==='mirror-failure')f.sql.exec("CREATE TRIGGER reject_unfriend BEFORE DELETE ON friendbook_cf_documents WHEN OLD.path='friends/f0' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
    });
    assert.equal((await f.call()).status,409,kind);
    assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_trusted_friendships').get().n,1,kind);
    assert.ok(f.sql.prepare("SELECT path FROM friendbook_cf_firestore_documents WHERE path='friends/f0'").get(),kind);
    assert.ok(f.sql.prepare("SELECT path FROM friendbook_cf_documents WHERE path='friends/f0'").get(),kind);
    assert.equal(JSON.parse(f.sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='users/bob'").get().document_json).friendsCount,1,kind);
    assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_social_guards').get().n,0,kind);
    f.sql.close();
  }
});

test('large historical duplicate removal keeps the old bounded-parameter guard path',async()=>{
  const f=fixture({count:19});assert.equal((await f.call()).status,200);
  assert.equal(f.guards(),22,'21 snapshots and one exact duplicate count');
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_firestore_documents WHERE parent_path='friends'").get().n,0);
  assert.equal(JSON.parse(f.sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='users/bob'").get().document_json).friendsCount,0);
  f.sql.close();
});
