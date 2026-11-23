import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';

function fixture() {
  const sql=new DatabaseSync(':memory:');
  for(const migration of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0040_chat_participant_lookup','0073_chat_membership_source_seek'])
    sql.exec(readFileSync(new URL(`../migrations/${migration}.sql`,import.meta.url),'utf8'));
  const reads=[];
  const db={prepare(q){const stmt=sql.prepare(q);return {bind(...a){return {
    async all(){const results=stmt.all(...a);reads.push({q,rows:results.length});return {results};},
    async first(){return stmt.get(...a)||null;},async run(){return stmt.run(...a);},
  };}};}};
  const add=(id,data)=>sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)')
    .run(`chats/${id}`,'chats','chats',id,'alice',JSON.stringify(data),1,1);
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  async function query(spec,compat=false) {
    const body={parent:'chats',filters:[{field:'participants',op:'array-contains',value:'bob'}],limit:10,
      orderBy:[{field:'inboxActivityAt',direction:'DESCENDING'}],...spec};
    if(compat)body.orderBy.push({field:'__name__',direction:body.orderBy[0].direction});
    const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify(body)}),env);
    assert.equal(r.status,200);return (await r.json()).documents;
  }
  return {sql,reads,add,query};
}

test('inbox materializes bounded full documents and matches compatibility ordering/cursors',async()=>{
  const f=fixture();
  try {
    for(let i=0;i<240;i++)f.add(`room${String(i).padStart(3,'0')}`,{
      participants:['alice','bob'],lastMessage:'x'.repeat(8000),
      ...[ {lastMessageTime:{__type:'timestamp',millis:1000-i+.9}},
        {lastMessageTime:0,lastMessageTimeMs:1000-i},
        {lastMessageTime:false,lastMessageAt:{__type:'timestamp',millis:1000-i}},
        {lastMessageTime:'invalid',lastMessageTimeMs:1000-i}][i%4],
    });
    const first=await f.query({});
    const bounded=f.reads.filter(r=>r.q.includes('WITH quota_inbox_candidates'));
    assert.equal(bounded.length,1);assert.equal(bounded[0].rows,10);
    assert.ok(!f.reads.some(r=>r.q.includes('SELECT f.* FROM friendbook_cf_chat_participants')));
    assert.deepEqual(first,await f.query({},true));
    const tail=first.at(-1),cursor=[tail.data.inboxActivityAt,tail.id];
    for(const spec of [{startAfter:cursor},{startAt:cursor},{endBefore:cursor},{endAt:cursor},
      {startAfter:[tail.data.inboxActivityAt]},
      {orderBy:[{field:'inboxActivityAt',direction:'ASCENDING'}]},
      {startAfter:[tail.data.inboxActivityAt,'deleted-anchor']}])
      assert.deepEqual(await f.query(spec),await f.query(spec,true));
  } finally {f.sql.close();}
});

test('inbox fills past stale membership before LIMIT and observes revocation on next request',async()=>{
  const f=fixture();
  try {
    for(let i=0;i<60;i++) {
      const id=`room${String(i).padStart(3,'0')}`;
      f.add(id,{participants:i<30?['alice','eve']:['alice','bob'],lastMessageTime:100-i});
      if(i<30)f.sql.prepare('INSERT INTO friendbook_cf_chat_participants VALUES (?,?,1)').run('bob',`chats/${id}`);
    }
    assert.deepEqual((await f.query({})).map(d=>d.id),Array.from({length:10},(_,i)=>`room${i+30}`.replace('room','room0')));
    assert.deepEqual(await f.query({}),await f.query({},true));
    f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='chats/room030'")
      .run(JSON.stringify({participants:['alice','eve'],lastMessageTime:999}));
    assert.ok(!(await f.query({})).some(d=>d.id==='room030'));
  } finally {f.sql.close();}
});

test('historical timestamps, zero/fractional fallthrough and Unicode ties keep compatibility semantics',async()=>{
  const f=fixture();
  try {
    const values=[0,-3,12.5,{__type:'timestamp',millis:.9},{__type:'timestamp',millis:-12.9},
      {__type:'timestamp',millis:1e30},true,'bad',null];
    values.forEach((value,i)=>f.add(`room${i}`,{participants:['bob','alice'],lastMessageTime:value,lastMessageTimeMs:5}));
    for(const direction of ['ASCENDING','DESCENDING']) {
      const spec={orderBy:[{field:'inboxActivityAt',direction}],limit:4};
      assert.deepEqual(await f.query(spec),await f.query(spec,true));
    }
    f.add('\ue000',{participants:['alice','bob'],lastMessageTime:5});
    f.add('\u{10000}',{participants:['alice','bob'],lastMessageTime:5});
    assert.deepEqual(await f.query({limit:20}),await f.query({limit:20},true));
  } finally {f.sql.close();}
});
