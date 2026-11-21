import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { readPresence, writePresence, presenceRealtime, PRESENCE_TTL_MS } from '../presence-realtime.js';

function fixture() {
  const sql=new DatabaseSync(':memory:');
  for (const file of ['0010_additive_presence_realtime.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0031_presence_sessions.sql','0033_typing_sessions.sql'])
    sql.exec(readFileSync(new URL(`../migrations/${file}`,import.meta.url),'utf8'));
  const db=()=>({prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),first:async()=>s.get(...args)||null,all:async()=>({results:s.all(...args)})};}};}});
  return {sql,db};
}

test('separate isolates see presence and old disconnect cannot hide another device', async () => {
  const {sql,db}=fixture(), first=db(), second=db(), now=1_000_000;
  await writePresence(first,'alice','phone',true,true,now);
  assert.deepEqual(await readPresence(second,'alice',now+1),{uid:'alice',online:true,lastSeen:now});
  await writePresence(second,'alice','tablet',true,true,now+10);
  await writePresence(first,'alice','phone',false,true,now+20);
  assert.deepEqual(await readPresence(second,'alice',now+21),{uid:'alice',online:true,lastSeen:now+20});
  await writePresence(first,'alice','phone',true,true,now+5); // delayed heartbeat cannot revive closed session
  await writePresence(first,'alice','phone',true,true,now+20); // even equal-timestamp heartbeat cannot beat offline
  await writePresence(second,'alice','tablet',false,true,now+30);
  assert.deepEqual(await readPresence(first,'alice',now+31),{uid:'alice',online:false,lastSeen:now+30});
  await writePresence(first,'bob','phone',true,true,now);
  assert.equal((await readPresence(second,'bob',now+PRESENCE_TTL_MS)).online,false);
  sql.close();
});

test('typing is shared across isolates and only room members can observe or send it', async () => {
  const {sql,db}=fixture();
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)')
    .run('chats/room','chats','chats','room','alice',JSON.stringify({participants:['alice','bob']}),1,1);
  const call=(uid,method,body,database)=>presenceRealtime(new Request('https://example.test/v1/presence/typing/room',{
    method,headers:{'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})
  }),{DB:database,REALTIME_WS_ENABLED:'true'},{uid});
  assert.equal((await call('alice','PUT',{typing:true,sessionId:'phone'},db())).status,200);
  assert.equal((await call('alice','PUT',{typing:true,sessionId:'tablet'},db())).status,200);
  assert.equal((await (await call('bob','GET',null,db())).json()).typing,true);
  assert.equal((await call('mallory','GET',null,db())).status,403);
  assert.equal((await call('mallory','PUT',{typing:true},db())).status,403);
  assert.equal((await call('alice','PUT',{typing:false,sessionId:'phone'},db())).status,200);
  assert.equal((await (await call('bob','GET',null,db())).json()).typing,true,'one device must not hide another');
  assert.equal((await call('alice','PUT',{typing:false,sessionId:'tablet'},db())).status,200);
  assert.equal((await (await call('bob','GET',null,db())).json()).typing,false);
  assert.equal((await call('alice','PUT',{typing:true,sessionId:'invalid/session'},db())).status,400);
  assert.equal((await call('alice','PUT',{typing:true},db())).status,200,'old clients remain compatible');
  assert.equal((await (await call('bob','GET',null,db())).json()).typing,true);
  sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?')
    .run(JSON.stringify({participants:['bob','carol']}),'chats/room');
  assert.equal((await (await call('bob','GET',null,db())).json()).typing,false,'removed member typing must not leak until TTL');
  assert.equal((await call('alice','PUT',{typing:true},db())).status,403);
  assert.equal((await call('carol','PUT',{typing:true},db())).status,200);
  assert.equal((await (await call('bob','GET',null,db())).json()).typing,true);
  sql.close();
});

test('scheduled maintenance prunes bounded expired rows and retains the presence fallback guard', async () => {
  const {runMaintenance}=await import('../maintenance.js');
  const {sql,db}=fixture(), now=10*24*60*60*1000;
  sql.exec(readFileSync(new URL('../migrations/0034_realtime_maintenance_indexes.sql',import.meta.url),'utf8'));
  const presence=sql.prepare('INSERT INTO friendbook_cf_presence_sessions VALUES (?,?,?,?,?)');
  presence.run('alice','old',1,1,1);
  presence.run('alice','fresh',1,1,now);
  presence.run('bob','last',0,0,1);
  const typing=sql.prepare('INSERT INTO friendbook_cf_typing_sessions VALUES (?,?,?,?,?)');
  for (let i=0;i<101;i++) typing.run('room','alice',String(i),1,1);
  typing.run('room','bob','fresh',1,now);
  const alarms=[];
  await runMaintenance({DB:db()},{now,broadcastPush:async()=>{},videoCleanup:async()=>{throw new Error('private details');},report:v=>alarms.push(JSON.parse(v))});
  assert.deepEqual(alarms,[{event:'friendbook.maintenance_failed',job:'temporary-video'}]);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_typing_sessions').get().n,2);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_presence_sessions').get().n,2);
  assert.equal((await readPresence(db(),'alice',now)).online,true);
  assert.deepEqual(await readPresence(db(),'bob',now),{uid:'bob',online:false,lastSeen:0});
  await runMaintenance({DB:db()},{now,broadcastPush:async()=>{},videoCleanup:async()=>{},report:()=>assert.fail('unexpected maintenance error')});
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_typing_sessions').get().n,1);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_typing_sessions WHERE updated_at=?').get(now).n,1);
  sql.close();
});

test('offline, error and close share one write until the socket is active again', async () => {
  const {offlineOnce}=await import('../presence-realtime.js');
  const state={active:true}, writes=[];
  const offline=offlineOnce(state, now=>writes.push(now));
  await Promise.all([offline(100),offline(101),offline(102)]);
  assert.deepEqual(writes,[100]);
  state.active=true;
  await offline(200);
  assert.deepEqual(writes,[100,200]);
});
