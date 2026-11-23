import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';

function fixture() {
  const sql=new DatabaseSync(':memory:');
  for(const m of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0018_social_guards'])
    sql.exec(readFileSync(new URL(`../migrations/${m}.sql`,import.meta.url),'utf8'));
  const data={participants:['alice','bob'],lastMessage:'old',unreadCount:{bob:0}};
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)')
    .run('chats/direct','chats','chats','direct','alice',JSON.stringify(data),1,1);
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)')
    .run('chats/direct','chats','direct','alice',JSON.stringify({...data,id:'direct'}),1);
  const batches=[];let beforeBatch=null;
  const db={prepare(q){const stmt=sql.prepare(q);return {bind(...a){return {q,
    async all(){return {results:stmt.all(...a)};},async first(){return stmt.get(...a)||null;},async run(){return stmt.run(...a);},
  };}};},async batch(statements){
    if(beforeBatch){const run=beforeBatch;beforeBatch=null;run();}
    batches.push(statements.map(s=>s.q));sql.exec('BEGIN');
    try {for(const s of statements)await s.run();sql.exec('COMMIT');}
    catch(e){sql.exec('ROLLBACK');throw e;}
  }};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const patch=(uid='alice')=>app.fetch(new Request('https://example.test/v1/firestore/document?path=chats/direct',{
    method:'PATCH',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},
    body:JSON.stringify({fields:{lastMessage:'new'}}),
  }),env);
  return {sql,batches,patch,before(fn){beforeBatch=fn;}};
}

test('existing direct room commits canonical and mirror without temporary guard writes',async()=>{
  const f=fixture();try {
    const r=await f.patch();assert.equal(r.status,200,await r.text());
    assert.equal(f.batches.length,1);assert.equal(f.batches[0].length,2);
    assert.ok(f.batches[0].every(q=>q.includes('AS quota_writer_chat_root_chats')));
    assert.ok(f.batches[0].every(q=>!q.includes('friendbook_cf_social_guards')));
    for(const table of ['friendbook_cf_firestore_documents','friendbook_cf_documents'])
      assert.equal(JSON.parse(f.sql.prepare(`SELECT document_json FROM ${table}`).get().document_json).lastMessage,'new');
    assert.equal((await f.patch('eve')).status,403);
  } finally {f.sql.close();}
});

for(const race of ['membership','revision'])test(`direct room ${race} conflict cannot commit stale canonical or mirror`,async()=>{
  const f=fixture();try {
    const mirror=f.sql.prepare('SELECT * FROM friendbook_cf_documents').get();
    f.before(()=>{
      if(race==='membership')f.sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=?')
        .run(JSON.stringify({participants:['alice','eve'],lastMessage:'concurrent'}));
      else f.sql.exec('UPDATE friendbook_cf_firestore_documents SET update_time=2');
    });
    assert.equal((await f.patch()).status,503);
    assert.deepEqual(f.sql.prepare('SELECT * FROM friendbook_cf_documents').get(),mirror);
    const doc=JSON.parse(f.sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents').get().document_json);
    assert.notEqual(doc.lastMessage,'new');
  } finally {f.sql.close();}
});

test('failed direct-room mirror rolls back canonical update',async()=>{
  const f=fixture();try {
    const before=f.sql.prepare('SELECT * FROM friendbook_cf_firestore_documents').get();
    f.sql.exec("CREATE TRIGGER reject_mirror BEFORE UPDATE ON friendbook_cf_documents BEGIN SELECT RAISE(ABORT,'mirror unavailable'); END");
    assert.equal((await f.patch()).status,503);
    assert.deepEqual(f.sql.prepare('SELECT * FROM friendbook_cf_firestore_documents').get(),before);
  } finally {f.sql.close();}
});
