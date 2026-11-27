import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';
import {createWorker} from '../../worker.js';

// Compare actual Worker queries and SQLite plans with and without the old index.
// SQLite plans/VM work are not Cloudflare billed-row measurements.
test('removing message sender index preserves scoped queries, cursors and authorization',async t=>{
  const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
  const dir=new URL('../migrations/',import.meta.url), removal='0079_remove_unused_message_sender_index.sql';
  for(const file of readdirSync(dir).filter(f=>f.endsWith('.sql')&&f!==removal&&f!=='0105_retire_legacy_document_mirror.sql'&&f!=='0106_enforce_document_mirror_retirement.sql'&&f!=='0109_archive_reappeared_document_mirror.sql').sort()) sql.exec(readFileSync(new URL(file,dir),'utf8'));
  const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const add=(parent,id,doc)=>insert.run(`${parent}/${id}`,parent,parent.split('/').at(-1),id,'alice',JSON.stringify(doc),1,1);
  add('users','alice',{uid:'alice'});add('users','bob',{uid:'bob'});add('chats','r',{participants:['alice','bob']});
  for(let i=0;i<1000;i++)add(`chats/${i%5===0?'r':'other'}/messages`,`m${String(i).padStart(4,'0')}`,{senderId:i%2?'alice':'bob',createdAt:{__type:'timestamp',millis:i}});
  let plans=[];
  const db={withSession(){return this;},prepare(q){const stmt=sql.prepare(q);return{bind(...args){
    if(/^\s*(SELECT|WITH)\b/i.test(q))plans.push({q,plan:sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(x=>x.detail)});
    return{async first(){return stmt.get(...args)||null;},async all(){return{results:stmt.all(...args)};},async run(){return stmt.run(...args);}};
  }};}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const specs=[
    {orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:10},
    {filters:[{field:'senderId',op:'==',value:'alice'}],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:10},
    {filters:[{field:'senderId',op:'==',value:'bob'}],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:10,startAfter:[{__type:'timestamp',millis:700},'m0700']},
    {filters:[{field:'senderId',op:'==',value:'alice'}],limit:20},
  ];
  async function suite(){
    plans=[];const responses=[];
    for(const [actor,spec] of [...specs.map(spec=>['alice',spec]),['eve',specs[1]]]) {
      const response=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:`Bearer ${actor}`,'content-type':'application/json'},body:JSON.stringify({parent:'chats/r/messages',...spec})}),env);
      responses.push({status:response.status,body:await response.json()});
    }
    return {responses,plans};
  }
  const before=await suite();assert.deepEqual(before.responses.map(x=>x.status),[200,200,200,200,403]);
  assert.deepEqual(before.responses.slice(0,4).map(x=>x.body.documents.length),[10,10,10,20]);
  assert.ok(before.plans.some(x=>x.plan.some(p=>p.includes('idx_cf_firestore_chat_created_page'))));
  assert.ok(before.plans.some(x=>x.plan.some(p=>p.includes('idx_friendbook_cf_firestore_parent'))));
  assert.ok(before.plans.every(x=>x.plan.every(p=>!p.includes('idx_cf_message_sender'))));
  const migration=readFileSync(new URL(removal,dir),'utf8');sql.exec(migration);sql.exec(migration);
  assert.equal(sql.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_cf_message_sender'").get(),undefined);
  assert.deepEqual(await suite(),before);
  // Explicit restoration remains possible, and it leaves all messages intact.
  sql.exec("CREATE INDEX idx_cf_message_sender ON friendbook_cf_firestore_documents(json_extract(document_json,'$.senderId'),create_time DESC) WHERE collection_id='messages'");
  assert.deepEqual(await suite(),before);
});
