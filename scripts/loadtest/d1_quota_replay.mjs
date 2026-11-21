// Offline replay of the actual Worker and migrations. No network or Cloudflare credentials.
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readdirSync, readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const sql = new DatabaseSync(':memory:');
const migrations = new URL('../../cloudflare/migrations/', import.meta.url);
for (const file of readdirSync(migrations).filter(f=>f.endsWith('.sql')).sort()) {
  sql.exec(readFileSync(new URL(file,migrations),'utf8'));
}
let statements=0;
const db={
  prepare(query){const stmt=sql.prepare(query);return {bind(...args){return {
    first:async()=>{statements++;return stmt.get(...args)||null;},
    all:async()=>{statements++;return {results:stmt.all(...args)};},
    run:async()=>{statements++;const result=stmt.run(...args);return {success:true,meta:{changes:Number(result.changes)}};},
  };}};},
  async batch(writes){sql.exec('BEGIN');try{for(const write of writes)await write.run();sql.exec('COMMIT');}catch(error){sql.exec('ROLLBACK');throw error;}},
};
const users=Number(process.argv[2]??30);
assert.ok(Number.isInteger(users)&&users>=2&&users<=1000&&users%2===0,'use an even user count between 2 and 1000');
const now=Date.now();
const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
const add=(parent,id,owner,data)=>insert.run(`${parent}/${id}`,parent,parent.split('/').at(-1),id,owner,JSON.stringify(data),now,now);
for(let i=0;i<users;i++){
  const uid=`user${i}`;
  add('users',uid,uid,{uid,displayName:uid,isOnline:true,isActive:true,showActiveStatus:true,lastSeen:{__type:'timestamp',millis:now},lastSeenMs:now});
  if(i%2===0) add('chats',`room${i}`,uid,{participants:[uid,`user${i+1}`],unreadCount:{[uid]:0,[`user${i+1}`]:0}});
}
const app=createWorker(async token=>token);
const env={DB:db,FIREBASE_PROJECT_ID:'offline-replay'};
async function call(uid,path,method='GET',body){
  const res=await app.fetch(new Request(`https://offline.invalid${path}`,{method,
    headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  assert.ok(res.ok,`${path} returned HTTP ${res.status}: ${await res.text()}`);
}
const changes=()=>sql.prepare('SELECT total_changes() AS n').get().n;
const report={environment:'Local SQLite + real Worker handlers; not Cloudflare billing or capacity data',users,workloads:{}};
async function measure(name,requests,run){
  const before=changes(), start=statements;
  await run();
  report.workloads[name]={requests,sqliteStatements:statements-start,changedRowsIncludingTriggers:changes()-before};
}
const heartbeat=uid=>call(uid,`/v1/firestore/document?path=users/${uid}`,'PATCH',{fields:{isOnline:true,isActive:true,showActiveStatus:true,lastSeenMs:Date.now(),lastSeen:{__fieldValue:'serverTimestamp'}}});
const messages=i=>`chats/room${i}/messages/message0`;
const batch=(uid,operations)=>call(uid,'/v1/firestore/batch','POST',{operations});
const receipt=i=>batch(`user${i+1}`, [{op:'update',path:messages(i),updates:{isDelivered:true,isSeen:true}},{op:'update',path:`chats/room${i}`,updates:{[`unreadCount.user${i+1}`]:0}}]);
await measure('unchangedProfileHeartbeat',users,async()=>{for(let i=0;i<users;i++)await heartbeat(`user${i}`);});
await measure('sendMessageAndUpdateRoom',users/2,async()=>{for(let i=0;i<users;i+=2)await batch(`user${i}`,[
  {op:'set',path:messages(i),data:{senderId:`user${i}`,receiverId:`user${i+1}`,text:'offline workload'}},
  {op:'update',path:`chats/room${i}`,updates:{lastMessage:'offline workload',[`unreadCount.user${i+1}`]:1}},
]);});
await measure('firstReceiptAndUnreadReset',users/2,async()=>{for(let i=0;i<users;i+=2)await receipt(i);});
await measure('duplicateReceiptAndUnreadReset',users/2,async()=>{for(let i=0;i<users;i+=2)await receipt(i);});
assert.equal(report.workloads.unchangedProfileHeartbeat.changedRowsIncludingTriggers,0);
assert.equal(report.workloads.duplicateReceiptAndUnreadReset.changedRowsIncludingTriggers,0);
console.log(JSON.stringify(report,null,2));
sql.close();
