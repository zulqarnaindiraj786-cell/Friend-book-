// Offline replay of the actual Worker and migrations. No network or Cloudflare credentials.
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readdirSync, readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

globalThis.fetch=async()=>{throw new Error('Network access is disabled in the quota replay');};
const sql = new DatabaseSync(':memory:');
const migrations = new URL('../../cloudflare/migrations/', import.meta.url);
for (const file of readdirSync(migrations).filter(f=>f.endsWith('.sql')).sort()) {
  sql.exec(readFileSync(new URL(file,migrations),'utf8'));
}
let statements=0;
// Optional offline-only plan inventory of actual replayed SELECT statements. SQLite
// EXPLAIN does not execute the query or measure D1 rows; a SCAN may be an index scan,
// a tiny temporary/virtual table, or a legitimate full traversal. Inspect manually.
const collectPlans=process.env.QUOTA_QUERY_PLANS==='1';
const queryPlans=new Map();
function recordPlan(query,args) {
  if (!collectPlans || !/^\s*(SELECT|WITH)\b/i.test(query) || queryPlans.has(query)) return;
  try {
    const plan=sql.prepare('EXPLAIN QUERY PLAN '+query).all(...args).map(row=>row.detail);
    queryPlans.set(query,{query,plan});
  } catch(error) { queryPlans.set(query,{query,error:String(error)}); }
}
const executeWrite=Symbol('executeWrite');
const db={
  withSession(){return db;},
  prepare(query){const stmt=sql.prepare(query);return {bind(...args){recordPlan(query,args);const write=()=>{statements++;const result=stmt.run(...args);return {success:true,meta:{changes:Number(result.changes)}};};return {
    first:async()=>{statements++;return stmt.get(...args)||null;},
    all:async()=>{statements++;return {results:stmt.all(...args)};},
    run:async()=>write(), [executeWrite]:()=>/^\s*(SELECT|WITH)\b/i.test(query)||/\bRETURNING\b/i.test(query)
      ? (statements++,{success:true,results:stmt.all(...args)}) : write(),
  };}};},
  async batch(writes){sql.exec('BEGIN');try{const results=writes.map(write=>write[executeWrite]());sql.exec('COMMIT');return results;}catch(error){sql.exec('ROLLBACK');throw error;}},
};
const users=Number(process.argv[2]??30);
assert.ok(Number.isInteger(users)&&users>=2&&users<=1000&&users%2===0,'use an even user count between 2 and 1000');
const concurrency=Number(process.argv[3]??1);
assert.ok(Number.isInteger(concurrency)&&concurrency>=1&&concurrency<=users,'concurrency must be between 1 and the user count');
const now=Date.now();
const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
const add=(parent,id,owner,data)=>insert.run(`${parent}/${id}`,parent,parent.split('/').at(-1),id,owner,JSON.stringify(data),now,now);
for(let i=0;i<users;i++){
  const uid=`user${i}`;
  add('users',uid,uid,{uid,displayName:uid,isOnline:true,isActive:true,showActiveStatus:true,lastSeen:{__type:'timestamp',millis:now},lastSeenMs:now});
  if(i%2===0) add('chats',`room${i}`,uid,{participants:[uid,`user${i+1}`],unreadCount:{[uid]:0,[`user${i+1}`]:0}});
}
const app=createWorker(async token=>token);
const env={DB:db,FIREBASE_PROJECT_ID:'offline-replay',D1_INTERACTIONS_ENABLED:'true'};
async function call(uid,path,method='GET',body){
  const res=await app.fetch(new Request(`https://offline.invalid${path}`,{method,
    headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  assert.ok(res.ok,`${path} returned HTTP ${res.status}: ${res.ok ? '' : await res.text()}`);
  return res.json();
}
const changes=()=>sql.prepare('SELECT total_changes() AS n').get().n;
const report={environment:'Local SQLite + real Worker handlers; not Cloudflare billing or capacity data',users,concurrency,workloads:{}};
async function measure(name,requests,run){
  const before=changes(), start=statements;
  await run();
  report.workloads[name]={requests,sqliteStatements:statements-start,changedRowsIncludingTriggers:changes()-before};
}
const heartbeat=uid=>call(uid,`/v1/firestore/document?path=users/${uid}`,'PATCH',{fields:{isOnline:true,isActive:true,showActiveStatus:true,lastSeenMs:Date.now(),lastSeen:{__fieldValue:'serverTimestamp'}}});
const messages=i=>`chats/room${i}/messages/message0`;
const batch=(uid,operations)=>call(uid,'/v1/firestore/batch','POST',{operations});
const receipt=i=>batch(`user${i+1}`, [{op:'update',path:messages(i),updates:{isDelivered:true,isSeen:true}},{op:'update',path:`chats/room${i}`,updates:{[`unreadCount.user${i+1}`]:0}}]);
async function wave(count, operation) {
  for(let start=0;start<count;start+=concurrency){
    await Promise.all(Array.from({length:Math.min(concurrency,count-start)},(_,offset)=>operation(start+offset)));
  }
}
await measure('unchangedProfileHeartbeat',users,()=>wave(users,i=>heartbeat(`user${i}`)));
await measure('sendMessageAndUpdateRoom',users/2,()=>wave(users/2,slot=>{
  const i=slot*2;
  return batch(`user${i}`,[
    {op:'set',path:messages(i),data:{senderId:`user${i}`,receiverId:`user${i+1}`,text:'offline workload'}},
    {op:'update',path:`chats/room${i}`,updates:{lastMessage:'offline workload',[`unreadCount.user${i+1}`]:1}},
  ]);
}));
await measure('firstReceiptAndUnreadReset',users/2,()=>wave(users/2,i=>receipt(i*2)));
await measure('duplicateReceiptAndUnreadReset',users/2,()=>wave(users/2,i=>receipt(i*2)));
for(let i=0;i<users;i+=2){
  const row=sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?');
  const message=JSON.parse(row.get(messages(i)).document_json);
  assert.equal(message.receiverId,`user${i+1}`);
  assert.equal(message.isSeen,true);
  assert.equal(message.isDelivered,true);
  assert.equal(JSON.parse(row.get(`chats/room${i}`).document_json).unreadCount[`user${i+1}`],0);
}
for(const root of ['posts','reels']){
  for(let i=0;i<40;i++) add(root,`item${i}`,'user0',{authorId:'user0',createdAt:now+i,privacy:'Public'});
  add(root,'private','user0',{authorId:'user0',createdAt:now+100,privacy:'Only me'});
  await measure(`page-${root}`,users-1,()=>wave(users-1,async index=>{
    const page=await call(`user${index+1}`,'/v1/firestore/query','POST',{parent:root,limit:5,orderBy:[{field:'createdAt',direction:'DESCENDING'}]});
    assert.deepEqual(page.documents.map(doc=>doc.id),['item39','item38','item37','item36','item35']);
  }));
}
const timestamp=millis=>({__type:'timestamp',millis});
add('stories','active','user0',{authorId:'user0',privacy:'Public',expiresAt:timestamp(now+100000)});
add('stories','expired','user0',{authorId:'user0',privacy:'Public',expiresAt:timestamp(now-1)});
add('lives','open','user0',{hostId:'user0',privacy:'public',status:'live'});
add('lives','ended','user0',{hostId:'user0',privacy:'public',status:'ended'});
for(let i=0;i<users;i++) add('notifications',`notice${i}`,'user0',{fromUserId:'user0',toUserId:`user${i}`,isRead:false,createdAt:timestamp(now)});
for(const [root,options,expected] of [
  ['stories',{filters:[{field:'expiresAt',op:'>',value:timestamp(now)}]},()=>['active']],
  ['lives',{filters:[{field:'status',op:'==',value:'live'}]},()=>['open']],
  ['notifications',i=>({filters:[{field:'toUserId',op:'==',value:`user${i}`}],orderBy:[{field:'createdAt',direction:'DESCENDING'}]}),i=>[`notice${i}`]],
]) await measure(`page-${root}`,users,()=>wave(users,async i=>{
  const page=await call(`user${i}`,'/v1/firestore/query','POST',{parent:root,limit:5,...(typeof options==='function'?options(i):options)});
  assert.deepEqual(page.documents.map(d=>d.id),expected(i));
}));
await measure('profileDetail',users,()=>wave(users,async i=>{
  const profile=await call(`user${i}`,`/v1/docs/users/user${(i+1)%users}`);
  assert.equal(profile.document.displayName,`user${(i+1)%users}`);
  assert.equal(profile.document.email,undefined);
}));
for(const root of ['posts','reels','stories','lives','notifications']) assert.equal(report.workloads[`page-${root}`].changedRowsIncludingTriggers,0);
sql.prepare("INSERT INTO friendbook_cf_interaction_resources(resource,state) VALUES ('posts/item39','verified') ON CONFLICT(resource) DO NOTHING").run();
const engagement='/v1/engagement/posts/item39';
const like=i=>call(`user${i}`,`${engagement}/reactions?response=reaction`,'PUT',{type:'like'});
const comment=i=>call(`user${i}`,`${engagement}/comments`,'POST',{id:`reply${i}`,text:'A synthetic quota replay comment'});
const register=i=>call(`user${i}`,'/functions/v1/send-fcm-notification','POST',{action:'register',device_id:`device${i}`,token:`synthetic-not-a-provider-token-${i}`});
const mark=i=>call(`user${i}`,`/v1/firestore/document?path=notifications/notice${i}`,'PATCH',{fields:{isRead:true}});
for(const [label,operation] of [['PostReaction',like],['Comment',comment],['DeviceRegistration',register],['NotificationOpen',mark]]){
  await measure(`first${label}`,users,()=>wave(users,operation));
  await measure(`repeat${label}`,users,()=>wave(users,operation));
  assert.equal(report.workloads[`repeat${label}`].changedRowsIncludingTriggers,0,`repeated ${label}`);
}
assert.equal(sql.prepare("SELECT total FROM friendbook_cf_reaction_totals WHERE resource='posts/item39' AND type='like'").get().total,users);
assert.equal(report.workloads.unchangedProfileHeartbeat.changedRowsIncludingTriggers,0);
assert.equal(report.workloads.duplicateReceiptAndUnreadReset.changedRowsIncludingTriggers,0);
if (collectPlans) report.queryPlans=[...queryPlans.values()];
console.log(JSON.stringify(report,null,2));
sql.close();
