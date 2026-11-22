import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';
const migration=n=>readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8');

test('older chat history seeks retain numeric and timestamp ranks, ties, and fallback',async()=>{
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0019_legacy_query_indexes.sql','0024_legacy_chat_prefix_index.sql','0028_chat_history_page_index.sql'])sql.exec(migration(n));
  const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(const uid of ['alice','bob'])f.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid}),1,1);
  f.run('chats/room','chats','chats','room','alice',JSON.stringify({participants:['alice','bob']}),1,1);
  for(let i=0;i<160;i++){
    const id=`m${String(i).padStart(3,'0')}`,time=Math.floor((i%80)/4)-15;
    f.run(`chats/room/messages/${id}`,'chats/room/messages','messages',id,'alice',JSON.stringify({senderId:'alice',createdAt:i<80?time:{__type:'timestamp',millis:time}}),1,1);
  }
  const plans=[];
  const db={prepare(q){const s=sql.prepare(q);return{bind(...a){return{
    all:async()=>{if(q.includes('quota_chat_rank'))plans.push(sql.prepare('EXPLAIN QUERY PLAN '+q).all(...a).map(r=>r.detail));return{results:s.all(...a)};},
    first:async()=>s.get(...a)||null,
  };}};}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const query=async(spec,reference=false,uid='bob')=>{
    const body={parent:'chats/room/messages',orderBy:[{field:'createdAt',direction:'DESCENDING'}],...spec};
    if(reference)body.filters=[{field:'senderId',op:'==',value:'alice'}];
    const res=await app.fetch(new Request('https://local.invalid/v1/firestore/query',{method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify(body)}),env);
    if(uid==='eve'){assert.equal(res.status,403);return [];}
    assert.equal(res.status,200,await res.clone().text());return (await res.json()).documents.map(x=>x.id);
  };
  const cursors=[[{__type:'timestamp',millis:99},'z'],[{__type:'timestamp',millis:4},'m159'],[{__type:'timestamp',millis:0},'m100'],[{__type:'timestamp',millis:-15},'m080'],[99,'z'],[4,'m070'],[0,'m060'],[-15,'m003']];
  for(const cursor of cursors)for(const limit of [1,3,10,100]){
    const spec={limit,startAfter:cursor};assert.deepEqual(await query(spec),await query(spec,true),JSON.stringify(spec));
  }
  await query({limit:10,startAfter:cursors[0]},false,'eve');
  assert.ok(plans.length>0);
  for(const plan of plans){const text=plan.join('\n');assert.match(text,/idx_cf_firestore_chat_created_page/);assert.match(text,/<expr>=\?/);assert.match(text,/<expr><\?/);}
  // Old schemas retain the existing query, not a failed request.
  sql.exec('DROP INDEX idx_cf_firestore_chat_created_page');
  const oldSchema={limit:10,startAfter:cursors[1]};assert.deepEqual(await query(oldSchema),await query(oldSchema,true));
  sql.exec(migration('0028_chat_history_page_index.sql'));
  const l=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  l.run('messages/room_old','messages','room_old','alice',JSON.stringify({senderId:'alice',chatId:'room',createdAt:3}),1);
  let before=plans.length;assert.deepEqual(await query(oldSchema),await query(oldSchema,true));assert.equal(plans.length,before,'legacy bridge must use the compatibility merge');
  sql.exec("DELETE FROM friendbook_cf_documents WHERE path='messages/room_old'");
  f.run('chats/room/messages/odd','chats/room/messages','messages','odd','alice',JSON.stringify({senderId:'alice',createdAt:'unusual'}),1,1);
  before=plans.length;assert.deepEqual(await query(oldSchema),await query(oldSchema,true));assert.equal(plans.length,before,'unusual ordering must use the compatibility merge');
  sql.close();
});
