import {recordInboxSortCost} from '../inbox-plan-hints.js';
import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';

function fixture(withIndex=false,measuredCost=null) {
  const sql=new DatabaseSync(':memory:');
  for(const migration of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0040_chat_participant_lookup','0073_chat_membership_source_seek'])
    sql.exec(readFileSync(new URL(`../migrations/${migration}.sql`,import.meta.url),'utf8'));
  if(withIndex)sql.exec(readFileSync(new URL('../migrations/0091_chat_activity_seek.sql',import.meta.url),'utf8'));
  const reads=[];
  const db={prepare(q){const stmt=sql.prepare(q);return {bind(...a){return {
    async all(){const results=stmt.all(...a);reads.push({q,args:a,rows:results.length,jsonBytes:results.reduce((n,r)=>n+(r.document_json?.length||0),0)});return {results,...(measuredCost!==null && q.includes('WITH quota_inbox_candidates')?{meta:{rows_read:measuredCost}}:{})};},
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
  return {sql,reads,add,query,expensive(){recordInboxSortCost(db,'bob',{rows_read:2000});}};
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


test('first inbox page tries a 4x bounded shared head before any full membership sort',async()=>{
  const f=fixture(true);try {
    for(let i=0;i<1200;i++)f.add(`room${String(i).padStart(4,'0')}`,{participants:['alice','bob'],lastMessageTime:5000-i});
    const page=await f.query({});
    const head=f.reads.find(r=>r.q.includes('idx_cf_chat_activity_seek'));
    assert.ok(head);assert.equal(head.rows,40,'first attempt is capped at four times the requested page');
    assert.deepEqual(page.map(d=>d.id),Array.from({length:10},(_,i)=>`room${String(i).padStart(4,'0')}`));
    assert.ok(!f.reads.some(r=>r.q.includes('WITH quota_inbox_candidates')),'dense inbox skips the unbounded sort on first request');
  } finally {f.sql.close();}
});

test('shared activity index seeks a bounded head with parity and no per-member write fanout',async()=>{
  const f=fixture(true);try {
    for(let i=0;i<1000;i++)f.add(`room${String(i).padStart(4,'0')}`,{participants:['alice','bob'],lastMessageTime:{__type:'timestamp',millis:2000-i}});
    f.expensive();const page=await f.query({});
    const head=f.reads.find(r=>r.q.includes('idx_cf_chat_activity_seek'));
    assert.ok(head);assert.equal(head.rows,64);
    assert.ok(!f.reads.some(r=>r.q.includes('WITH quota_inbox_candidates')));
    const plan=f.sql.prepare('EXPLAIN QUERY PLAN '+head.q).all('bob',64).map(r=>r.detail);
    assert.ok(plan.some(p=>p.includes('idx_cf_chat_activity_seek')));
    assert.ok(!plan.some(p=>p.includes('TEMP B-TREE')));
    assert.deepEqual(page,await f.query({},true));
    const tail=page.at(-1),cursor=[tail.data.inboxActivityAt,tail.id];
    for(const spec of [{startAfter:cursor},{startAt:cursor},{endBefore:cursor},{endAt:cursor},
      {orderBy:[{field:'inboxActivityAt',direction:'ASCENDING'}]}])
      assert.deepEqual(await f.query(spec),await f.query(spec,true));
    for(const read of f.reads.filter(r=>r.q.includes('idx_cf_chat_activity_seek'))) {
      const details=f.sql.prepare('EXPLAIN QUERY PLAN '+read.q).all(...read.args).map(r=>r.detail);
      assert.ok(!details.some(p=>p.includes('TEMP B-TREE')));
      if(read.args.length>2)assert.ok(details.some(p=>p.includes('SEARCH f USING INDEX idx_cf_chat_activity_seek')));
    }
    const before=f.sql.prepare('SELECT total_changes() n').get().n;
    f.sql.exec("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.lastMessageTime.millis',9999) WHERE path='chats/room0000'");
    // Local total_changes does not include index entries: this asserts there
    // are no per-member row triggers, NOT that the new index is free to write.
    assert.equal(f.sql.prepare('SELECT total_changes() n').get().n-before,5); // old fixture membership trigger deletes/reinserts two edges
    assert.equal((await f.query({}))[0].id,'room0000');
    f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.participants',json(?)) WHERE path='chats/room0000'").run(JSON.stringify(['alice','eve']));
    assert.ok(!(await f.query({})).some(d=>d.id==='room0000'),'a performance hint never caches membership after revocation');
  } finally {f.sql.close();}
});

test('sparse shared head uses exact membership page once, then cools down',async()=>{
  const f=fixture(true);try {
    for(let i=0;i<100;i++)f.add(`other${i}`,{participants:['alice','eve'],lastMessageTime:1000-i});
    for(let i=0;i<20;i++)f.add(`own${i}`,{participants:['alice','bob'],lastMessageTime:100-i});
    f.expensive();assert.deepEqual(await f.query({}),await f.query({},true));
    assert.equal(f.reads.filter(r=>r.q.includes('idx_cf_chat_activity_seek')).length,1);
    assert.ok(f.reads.some(r=>r.q.includes('WITH quota_inbox_candidates')));
    f.reads.length=0;await f.query({});
    assert.ok(!f.reads.some(r=>r.q.includes('idx_cf_chat_activity_seek')));
  } finally {f.sql.close();}
});

test('missing seek index and Unicode ties preserve the exact compatibility result',async()=>{
  for(const index of [false,true]) {
    const f=fixture(index);try {
      f.add('a',{participants:['alice','bob'],lastMessageTime:5});
      f.add('\ue000',{participants:['alice','bob'],lastMessageTime:5});
      f.add('\u{10000}',{participants:['alice','bob'],lastMessageTime:5});
      f.expensive();assert.deepEqual(await f.query({}),await f.query({},true));
    } finally {f.sql.close();}
  }
});


test('shared seek warms only from actual membership-query metadata, without an extra count query',async()=>{
  const f=fixture(true,2000);try {
    for(let i=0;i<200;i++)f.add(`room${String(i).padStart(4,'0')}`,{participants:['alice','bob'],lastMessageTime:1000-i});
    const first=await f.query({});
    assert.equal(f.reads.filter(r=>r.q.includes('WITH quota_inbox_candidates')).length,0);
    assert.ok(f.reads.some(r=>r.q.includes('idx_cf_chat_activity_seek')),'an observed expensive sort makes the next request seek immediately');
    f.reads.length=0;assert.deepEqual(await f.query({}),first);
    assert.equal(f.reads.length,1);assert.ok(f.reads[0].q.includes('idx_cf_chat_activity_seek'));
  } finally {f.sql.close();}
});


test('unsafe JSON integers preserve JS-rounded activity ties and cursors through compatibility ordering',async()=>{
  const f=fixture(true);try {
    f.add('z',{participants:['alice','bob'],lastMessageTime:9007199254740992});
    f.add('a',{participants:['alice','bob'],lastMessageTime:1});
    f.sql.exec("UPDATE friendbook_cf_firestore_documents SET document_json='{\"participants\":[\"alice\",\"bob\"],\"lastMessageTime\":9007199254740993}' WHERE path='chats/a'");
    f.expensive();assert.deepEqual((await f.query({})).map(d=>d.id),['z','a']);
    assert.deepEqual(await f.query({startAfter:[9007199254740992,'z']}),await f.query({startAfter:[9007199254740992,'z']},true));
  } finally {f.sql.close();}
});


test('shared head does not materialize unrelated large chat document JSON',async()=>{
  const f=fixture(true);try {
    for(let i=0;i<40;i++)f.add(`other${i}`,{participants:['alice','eve'],lastMessageTime:1000-i,lastMessage:'x'.repeat(50000)});
    for(let i=0;i<20;i++)f.add(`own${i}`,{participants:['alice','bob'],lastMessageTime:100-i});
    f.expensive();assert.deepEqual(await f.query({}),await f.query({},true));
    const head=f.reads.find(r=>r.q.includes('idx_cf_chat_activity_seek'));
    assert.equal(head.rows,60);assert.ok(head.jsonBytes<5000,'unrelated multi-megabyte payload stays out of Worker materialization');
  } finally {f.sql.close();}
});
