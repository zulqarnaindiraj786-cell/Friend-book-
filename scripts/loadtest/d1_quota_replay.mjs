// Offline replay of the actual Worker and migrations. No network or Cloudflare credentials.
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readdirSync, readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';
import { assertReadOnlyWorkloads } from './quota_replay_contract.mjs';

globalThis.fetch=async()=>{throw new Error('Network access is disabled in the quota replay');};
const sql = new DatabaseSync(':memory:');
const migrations = new URL('../../cloudflare/migrations/', import.meta.url);
for (const file of readdirSync(migrations).filter(f=>f.endsWith('.sql')).sort()) {
  // 0106 archives the mirror only when it reappears after 0105. In a clean
  // replay, 0105 has already retired it, so there is nothing to archive.
  // Likewise 0109 only renames a mirror if the historical 0106 restore path
  // was exercised; skip it in the clean canonical-only replay.
  if((file==='0106_enforce_document_mirror_retirement.sql'
      || file==='0109_archive_reappeared_document_mirror.sql')
      && !sql.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents'").get()) continue;
  sql.exec(readFileSync(new URL(file,migrations),'utf8'));
}
// Simulate a nonempty stale mirror left by a pre-canonical Worker; canonical
// mode must keep using canonical rows and never write to this test-only table.
sql.exec(`CREATE TABLE friendbook_cf_documents(path TEXT PRIMARY KEY,collection TEXT,doc_id TEXT,owner_uid TEXT,document_json TEXT,updated_at INTEGER)`);
// Offline-only comparison: keep the data/schema but restore prior update triggers.
if(process.env.QUOTA_FORCE_CHAT_MEMBERS_BASELINE==='1')
  sql.exec(readFileSync(new URL('0058_skip_unchanged_chat_members.sql',migrations),'utf8'));
let statements=0, returnedRows=0, shadowProbes=0;
// Instrument only the old people-page correlated shadow predicate. This counts
// logical callback invocations in SQLite, not Cloudflare billed row reads.
const countShadowProbes=process.env.QUOTA_SHADOW_PROBES==='1';
if(countShadowProbes) sql.function('quota_probe',path=>{shadowProbes++;return path;});
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
  prepare(query){
    // Offline-only baseline: simulate the page indexes being absent.
    if(process.env.QUOTA_FORCE_LEGACY_SCAN==='1' && query.includes('INDEXED BY idx_cf_comment_page_'))throw Error('no such index: idx_cf_comment_page_post');
    if(process.env.QUOTA_FORCE_FOLLOW_SCAN==='1' && query.includes('friendbook_cf_user_follow_'))throw Error('no such table: friendbook_cf_user_follow_membership');
    const stmt=sql.prepare(countShadowProbes?query.replace('f.path=c.path','f.path=quota_probe(c.path)'):query);return {bind(...args){recordPlan(query,args);const write=()=>{statements++;const result=stmt.run(...args);return {success:true,meta:{changes:Number(result.changes)}};};return {
    first:async()=>{statements++;const row=stmt.get(...args)||null;if(row)returnedRows++;return row;},
    all:async()=>{statements++;const results=stmt.all(...args);returnedRows+=results.length;return {results};},
    run:async()=>write(), [executeWrite]:()=>/^\s*(SELECT|WITH)\b/i.test(query)||/\bRETURNING\b/i.test(query)
      ? (()=>{statements++;const results=stmt.all(...args);returnedRows+=results.length;return {success:true,results};})() : write(),
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
const env={DB:db,FIREBASE_PROJECT_ID:'offline-replay',D1_INTERACTIONS_ENABLED:'true',
  DOCUMENT_MIRROR_MODE:'canonical',
  FRIEND_GUARD_INDEXED:process.env.QUOTA_EXPERIMENT_FRIEND_INDEXES==='1'?'true':'false'};
async function call(uid,path,method='GET',body){
  const res=await app.fetch(new Request(`https://offline.invalid${path}`,{method,
    headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  assert.ok(res.ok,`${path} returned HTTP ${res.status}: ${res.ok ? '' : await res.text()}`);
  return res.json();
}
const changes=()=>sql.prepare('SELECT total_changes() AS n').get().n;
const report={environment:'Local SQLite + real Worker handlers; not Cloudflare billing or capacity data',users,concurrency,friendGuardIndexed:env.FRIEND_GUARD_INDEXED==='true',workloads:{}};
async function measure(name,requests,run){
  const before=changes(), start=statements, startRows=returnedRows, startProbes=shadowProbes;
  await run();
  report.workloads[name]={requests,sqliteStatements:statements-start,sqliteReturnedRows:returnedRows-startRows,changedRowsIncludingTriggers:changes()-before,
    ...(countShadowProbes?{sqliteShadowProbes:shadowProbes-startProbes}:{})};
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
await measure('emptyMentionPrefix',users,()=>wave(users,async i=>{
  const result=await call(`user${i}`,'/v1/firestore/query','POST',{
    parent:'users',orderBy:[{field:'displayName',direction:'ASCENDING'}],startAt:[''],endAt:['\uf8ff'],limit:10,
  });
  assert.equal(result.documents.length,Math.min(users,10));
}));
await measure('asciiMentionPrefix',users,()=>wave(users,async i=>{
  const result=await call(`user${i}`,'/v1/firestore/query','POST',{
    parent:'users',orderBy:[{field:'displayName',direction:'ASCENDING'}],startAt:['user'],endAt:['user\uf8ff'],limit:10,
  });
  assert.equal(result.documents.length,Math.min(users,10));
}));
add('users','unicode-name','unicode-name',{displayName:'آمنہ',uid:'unicode-name'});
await measure('unicodeMentionPrefix',users,()=>wave(users,async i=>{
  const result=await call(`user${i}`,'/v1/firestore/query','POST',{
    parent:'users',orderBy:[{field:'displayName',direction:'ASCENDING'}],startAt:['آ'],endAt:['آ\uf8ff'],limit:10,
  });
  assert.deepEqual(result.documents.map(doc=>doc.id),['unicode-name']);
}));
await measure('unicodePeopleSearch',users,()=>wave(users,async i=>{
  const result=await call(`user${i}`,'/v1/firestore/query','POST',{
    parent:'users',filters:[{field:'displayName',op:'>=',value:'آ'},{field:'displayName',op:'<=',value:'آ\uf8ff'}],limit:10,
  });
  assert.deepEqual(result.documents.map(doc=>doc.id),['unicode-name']);
}));
for(let i=0;i<users;i++)add('payments',`review${i}`,`user${i}`,{userId:`user${i}`,status:'pending',type:'tick_blue'});
await measure('paymentOwnerPage',users,()=>wave(users,async i=>{
  const result=await call(`user${i}`,'/v1/firestore/query','POST',{
    parent:'payments',filters:[{field:'userId',op:'==',value:`user${i}`},{field:'status',op:'==',value:'pending'}],limit:1,
  });
  assert.deepEqual(result.documents.map(doc=>doc.id),[`review${i}`]);
}));
await measure('unchangedProfileHeartbeat',users,()=>wave(users,i=>heartbeat(`user${i}`)));
await measure('sendMessageAndUpdateRoom',users/2,()=>wave(users/2,slot=>{
  const i=slot*2;
  return batch(`user${i}`,[
    {op:'set',path:messages(i),data:{senderId:`user${i}`,receiverId:`user${i+1}`,text:'offline workload'}},
    {op:'set',path:`chats/room${i}`,merge:true,data:{lastMessage:'offline workload',unreadCount:{[`user${i+1}`]:1}}},
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
// Exercise canonical-only mode after the mirror-retirement migration: source-1
// membership remains correct and no source-0 mirror rows are expected.

await measure('reverseDirectReply',users/2,()=>wave(users/2,async slot=>{
  const i=slot*2,actor=`user${i+1}`,other=`user${i}`;
  await batch(actor,[{op:'set',path:`chats/room${i}/messages/reply`,data:{senderId:actor,receiverId:other,text:'offline reply'}},
    {op:'set',path:`chats/room${i}`,merge:true,data:{participants:[actor,other],lastMessage:'offline reply',unreadCount:{[other]:1}}}]);
  const rows=sql.prepare('SELECT participant_uid,source FROM friendbook_cf_chat_participants WHERE chat_path=? ORDER BY source,participant_uid').all(`chats/room${i}`);
  assert.deepEqual(rows.map(row=>`${row.source}:${row.participant_uid}`),[`1:${other}`,`1:${actor}`].sort());
}));
for(const root of ['posts','reels']){
  for(let i=0;i<40;i++) add(root,`item${i}`,'user0',{authorId:'user0',createdAt:now+i,privacy:'Public'});
  add(root,'private','user0',{authorId:'user0',createdAt:now+100,privacy:'Only me'});
  await measure(`page-${root}`,users-1,()=>wave(users-1,async index=>{
    const page=await call(`user${index+1}`,'/v1/firestore/query','POST',{parent:root,limit:5,orderBy:[{field:'createdAt',direction:'DESCENDING'}]});
    assert.deepEqual(page.documents.map(doc=>doc.id),['item39','item38','item37','item36','item35']);
  }));
}
await measure('feedEngagementFive',users-1,()=>wave(users-1,async i=>{
  const page=await call(`user${i+1}`,'/v1/firestore/query','POST',{
    parent:'posts',limit:5,feedEngagement:true,orderBy:[{field:'createdAt',direction:'DESCENDING'}],
  });
  assert.deepEqual(page.documents.map(doc=>doc.id),['item39','item38','item37','item36','item35']);
  assert.ok(page.documents.every(doc=>doc.engagement && doc.engagement.comments===0));
}));
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
// Historical numeric notification cursors must preserve order and unread scope.
for(let i=0;i<users;i++)for(const time of [10,20,30]) add('notifications',`numeric${i}_${time}`,'user0',{
  fromUserId:'user0',toUserId:`user${i}`,isRead:false,createdAt:time,
});
for(const unread of [false,true]) await measure(`numericNotificationPage${unread?'Unread':'All'}`,users,()=>wave(users,async i=>{
  const filters=[{field:'toUserId',op:'==',value:`user${i}`},...(unread?[{field:'isRead',op:'==',value:false}]:[])];
  const page=await call(`user${i}`,'/v1/firestore/query','POST',{parent:'notifications',filters,
    orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:2,startAfter:[30,`numeric${i}_30`]});
  assert.deepEqual(page.documents.map(d=>d.id),[`numeric${i}_20`,`numeric${i}_10`]);
}));
assert.equal(report.workloads.numericNotificationPageAll.changedRowsIncludingTriggers,0);
assert.equal(report.workloads.numericNotificationPageUnread.changedRowsIncludingTriggers,0);
// A quiet live host sends the same audience every eight seconds. One fresh
// canonical fixture per host avoids conflating independent concurrent sockets.
for(let i=0;i<users;i++) add('lives',`quiet${i}`,`user${i}`,{hostId:`user${i}`,privacy:'public',status:'live',lastHeartbeatMs:Date.now()-8000,viewerIds:[],peakViewers:1});
await measure('quietLiveHeartbeat',users,()=>wave(users,async i=>{
  const response=await call(`user${i}`,`/v1/firestore/document?path=lives/quiet${i}`,'PATCH',
    {fields:{lastHeartbeatMs:Date.now(),viewerIds:[],peakViewers:1}});
  assert.equal(response.data.status,'live');assert.equal(response.data.peakViewers,1);
}));
assert.equal(report.workloads.quietLiveHeartbeat.changedRowsIncludingTriggers,process.env.QUOTA_EXPECT_LIVE_WRITE==='1'?users:0);
// Exercise private group projections and indexed tag search with synthetic data.
add('groups','private','user0',{name:'fixture',privacy:'Private',adminId:'user0',adminIds:['user0'],moderatorIds:[],members:['user0'],pendingMemberIds:['user1'],invitedUids:['user1'],bannedMemberIds:[]});
sql.prepare("INSERT INTO friendbook_cf_trusted_groups(group_path,owner_uid) VALUES ('groups/private','user0')").run();
await measure('privateGroupProjection',users-2,()=>wave(users-2,async i=>{
  const group=await call(`user${i+2}`,'/v1/firestore/document?path=groups/private');
  assert.deepEqual(group.data.pendingMemberIds,[]);assert.deepEqual(group.data.invitedUids,[]);assert.deepEqual(group.data.members,[]);
}));
add('posts','tagpublic','user0',{authorId:'user0',createdAt:now,privacy:'Public',content:'#quota_replay'});
add('posts','tagprivate','user0',{authorId:'user0',createdAt:now+1,privacy:'Only me',content:'#quota_replay'});
await measure('tagSearch',users-1,()=>wave(users-1,async i=>{
  const page=await call(`user${i+1}`,'/v1/firestore/query','POST',{parent:'posts',postTag:'#quota_replay',limit:5});
  assert.deepEqual(page.documents.map(d=>d.id),['tagpublic']);
}));
for(let i=1;i<users;i++) add('stories',`view${i}`,'user0',{authorId:'user0',privacy:'Public',expiresAt:timestamp(now+100000),viewedBy:[],reactions:{}});
const storyView=i=>call(`user${i+1}`,'/v1/firestore/story-engagement','POST',{id:`view${i+1}`,op:'view'});
await measure('firstStoryView',users-1,()=>wave(users-1,storyView));
await measure('repeatStoryView',users-1,()=>wave(users-1,storyView));
assert.equal(report.workloads.repeatStoryView.changedRowsIncludingTriggers,0,'repeat story views');
assert.equal(report.workloads.privateGroupProjection.changedRowsIncludingTriggers,0);
assert.equal(report.workloads.tagSearch.changedRowsIncludingTriggers,0);
// Actual guarded group-post edits from distinct members against a single
// unchanged trusted group. The verification marker starts out verified.
const members=Array.from({length:users},(_,i)=>`user${i}`);
add('groups','quota-group','user0',{name:'quota fixture',privacy:'Private',adminId:'user0',adminIds:['user0'],moderatorIds:[],members,bannedMemberIds:[],requirePostApproval:false});
sql.prepare("INSERT INTO friendbook_cf_trusted_groups(group_path,owner_uid) VALUES ('groups/quota-group','user0')").run();
for(let i=0;i<users;i++) {
  add('posts',`group${i}`,`user${i}`,{authorId:`user${i}`,groupId:'quota-group',privacy:'Public',groupApprovalStatus:'approved',content:'original'});
  sql.prepare("INSERT INTO friendbook_cf_interaction_resources(resource,state) VALUES (?, 'verified')").run(`posts/group${i}`);
}
await measure('groupPostEdit',users,()=>wave(users,async i=>{
  const result=await call(`user${i}`,`/v1/firestore/document?path=posts/group${i}`,'PATCH',{fields:{content:'edited'}});
  assert.equal(result.data.content,'edited');
}));
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_interaction_resources WHERE resource LIKE 'posts/group%' AND state='verified'").get().n,users);
// Mirror-backed, identical profile merges and actor-scoped saves are retries,
// not new user actions. The Worker must still authorize and verify both stores.
const addMirror=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
const canonicalDoc=sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?');
// A small follow list among many unrelated synthetic accounts.
for(let i=1;i<Math.min(20,users);i++) {
  const path=`users/user${i}`,data=JSON.parse(canonicalDoc.get(path).document_json);
  sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({...data,following:['user0']}),path);
}
for(let i=0;i<users;i++) {
  const uid=`user${i}`,data=JSON.parse(canonicalDoc.get(`users/${uid}`).document_json);
  addMirror.run(`users/${uid}`,'users',uid,uid,JSON.stringify({...data,id:uid}),now);
}
await measure('mirroredPeoplePage',users,()=>wave(users,async i=>{
  const page=await call(`user${i}`,'/v1/firestore/query','POST',{parent:'users',limit:30});
  assert.equal(page.documents.length,Math.min(users+1,30));
}));
await measure('followListMembership',users,()=>wave(users,async()=>{
  const page=await call('user0','/v1/firestore/query','POST',{parent:'users',filters:[{field:'following',op:'array-contains',value:'user0'}],limit:20});
  assert.equal(page.documents.length,Math.min(19,users-1));
}));
assert.equal(report.workloads.followListMembership.changedRowsIncludingTriggers,0);
await measure('identicalProfileMerge',users,()=>wave(users,i=>call(`user${i}`,`/v1/firestore/document?path=users/user${i}`,'PUT',{data:{displayName:`user${i}`},merge:true})));
assert.equal(report.workloads.identicalProfileMerge.changedRowsIncludingTriggers,0,'identical profile merge');
await measure('changedProfileMerge',users,()=>wave(users,async i=>{
  const result=await call(`user${i}`,`/v1/firestore/document?path=users/user${i}`,'PUT',{data:{bio:'changed profile'},merge:true});
  assert.equal(result.data.bio,'changed profile');
}));
for(let i=1;i<users;i++) {
  const id=`saved${i}`,uid=`user${i}`,doc={authorId:'user0',privacy:'Public',savedBy:[uid]};
  add('posts',id,'user0',doc);
  addMirror.run(`posts/${id}`,'posts',id,'user0',JSON.stringify({...doc,id}),now);
}
await measure('repeatSavedPost',users-1,()=>wave(users-1,i=>call(`user${i+1}`,`/v1/firestore/document?path=posts/saved${i+1}`,'PATCH',{fields:{savedBy:{__fieldValue:'arrayUnion',values:[`user${i+1}`]}}})));
assert.equal(report.workloads.repeatSavedPost.changedRowsIncludingTriggers,0,'already saved posts');
await measure('changedSavedPost',users-1,()=>wave(users-1,async i=>{
  const result=await call(`user${i+1}`,`/v1/firestore/document?path=posts/saved${i+1}`,'PATCH',{fields:{savedBy:{__fieldValue:'arrayRemove',values:[`user${i+1}`]}}});
  assert.deepEqual(result.data.savedBy,[]);
}));
await measure('firstFollow',users/2,()=>wave(users/2,async i=>{
  const actor=`user${2*i}`,target=`user${2*i+1}`;
  const operations=[{op:'set',path:`users/${actor}`,merge:true,data:{following:{__fieldValue:'arrayUnion',values:[target]}}},
    {op:'set',path:`users/${target}`,merge:true,data:{followers:{__fieldValue:'arrayUnion',values:[actor]}}}];
  assert.equal((await batch(actor,operations)).ok,true);
}));
// Guarded acceptance and removal preserve duplicate detection and both counters.
const friendshipPairs=Math.min(users/2,100);
for(let i=0;i<friendshipPairs;i++)add('friend_requests',`quota${i}`,`user${2*i}`,{fromUid:`user${2*i}`,toUid:`user${2*i+1}`,status:'pending'});
await measure('friendAcceptance',friendshipPairs,()=>wave(friendshipPairs,async i=>{
  const a=`user${2*i}`,b=`user${2*i+1}`;
  await batch(b,[{op:'update',path:`friend_requests/quota${i}`,updates:{status:'accepted'}},
    {op:'set',path:`friends/quota${i}`,data:{participants:[a,b],createdAt:{__fieldValue:'serverTimestamp'}}},
    ...[a,b].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:1}}}))]);
}));
await measure('unfriend',friendshipPairs,()=>wave(friendshipPairs,async i=>{
  const a=`user${2*i}`,b=`user${2*i+1}`;
  await batch(b,[{op:'delete',path:`friends/quota${i}`},
    ...[a,b].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:-1}}}))]);
}));
assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_trusted_friendships WHERE friend_path LIKE 'friends/quota%'").get().n,0);
const requestBody=i=>({data:{fromUid:`user${2*i}`,toUid:`user${2*i+1}`,status:'pending'}});
await measure('firstFriendRequest',friendshipPairs,()=>wave(friendshipPairs,async i=>{
  const result=await call(`user${2*i}`,'/v1/firestore/document?parent=friend_requests','POST',requestBody(i));
  assert.equal(result.data.status,'pending');
}));
await measure('duplicateFriendRequest',friendshipPairs,()=>wave(friendshipPairs,async i=>{
  const result=await app.fetch(new Request('https://offline.invalid/v1/firestore/document?parent=friend_requests',{
    method:'POST',headers:{authorization:`Bearer user${2*i}`,'content-type':'application/json'},body:JSON.stringify(requestBody(i))}),env);
  assert.equal(result.status,409);
}));
assert.equal(report.workloads.duplicateFriendRequest.changedRowsIncludingTriggers,0);
sql.prepare("INSERT INTO friendbook_cf_interaction_resources(resource,state) VALUES ('posts/item39','verified') ON CONFLICT(resource) DO NOTHING").run();
const engagement='/v1/engagement/posts/item39';
// A busy canonical comment route with a small mix of exception encodings.
// Mirror-only comments must not leak through the canonical-only read policy.
// This exercises the real comment handler and indexed D1 batch, not billing.
for(let i=0;i<188;i++) {
  const id=`legacy${String(i).padStart(4,'0')}`;
  add('comments',id,'user0',{postId:'item39',authorId:'user0',content:id,
    createdAt:i<180?now+i:'2020-01-01T00:00:00.000Z'});
}
addMirror.run('comments/mirror-only','comments','mirror-only','user0',JSON.stringify({postId:'item39',authorId:'user0',content:'must remain hidden',createdAt:now+999999}),now);
await measure('canonicalCommentPage',users,()=>wave(users,async i=>{
  const page=await call(`user${i}`,`${engagement}/comments`);
  assert.equal(page.comments.length,20);assert.equal(page.comments[0].id,'legacy0179');
}));
assert.equal(report.workloads.canonicalCommentPage.changedRowsIncludingTriggers,0);
const like=i=>call(`user${i}`,`${engagement}/reactions?response=reaction`,'PUT',{type:'like'});
const comment=i=>call(`user${i}`,`${engagement}/comments`,'POST',{id:`reply${i}`,text:'A synthetic quota replay comment'});
const register=i=>call(`user${i}`,'/functions/v1/send-fcm-notification','POST',{action:'register',device_id:`device${i}`,token:`synthetic-not-a-provider-token-${i}`});
const mark=i=>call(`user${i}`,`/v1/firestore/document?path=notifications/notice${i}`,'PATCH',{fields:{isRead:true}});
for(const [label,operation] of [['PostReaction',like],['Comment',comment],['DeviceRegistration',register],['NotificationOpen',mark]]){
  await measure(`first${label}`,users,()=>wave(users,operation));
  await measure(`repeat${label}`,users,()=>wave(users,operation));
  assert.equal(report.workloads[`repeat${label}`].changedRowsIncludingTriggers,0,`repeated ${label}`);
}
// Create-only notifications are durable and must never overwrite the recipient's
// read state. The replay creates no FCM pushes and contacts no external service.
const noticePath=i=>`/v1/firestore/document?path=notifications/synthetic_event${i}`;
const noticeBody=i=>({data:{fromUserId:'user0',toUserId:`user${i}`,type:'like',isRead:false,text:'offline notification'},createOnly:true});
await measure('firstNotificationCreate',users-1,()=>wave(users-1,i=>call('user0',noticePath(i+1),'PUT',noticeBody(i+1))));
await measure('duplicateNotificationCreate',users-1,()=>wave(users-1,async i=>{
  const res=await app.fetch(new Request(`https://offline.invalid${noticePath(i+1)}`,{method:'PUT',
    headers:{authorization:'Bearer user0','content-type':'application/json'},body:JSON.stringify(noticeBody(i+1))}),env);
  assert.equal(res.status,409);
}));
assert.equal(report.workloads.duplicateNotificationCreate.changedRowsIncludingTriggers,0,'duplicate notification creation');
// Exercise all reaction types and both reacting and non-reacting viewers.
add('posts','multi_reactions','user0',{authorId:'user0',privacy:'Public',createdAt:timestamp(now)});
sql.prepare("INSERT INTO friendbook_cf_interaction_resources VALUES('posts/multi_reactions','verified')").run();
const multiTypes=['like','love','haha','wow','sad','angry'];
for(const [i,type] of multiTypes.entries()) sql.prepare('INSERT INTO friendbook_cf_reactions VALUES (?,?,?,?)').run('posts/multi_reactions',`user${i}`,type,now);
await measure('multiTypeReactionSummary',users,()=>wave(users,async i=>{
  const result=await call(`user${i}`,'/v1/engagement/posts/multi_reactions/reactions');
  assert.deepEqual(result.types,[...multiTypes].sort().map(type=>({type,count:1,mine:multiTypes[i]===type?1:0})));
}));
assert.equal(report.workloads.multiTypeReactionSummary.changedRowsIncludingTriggers,0);
assert.equal(sql.prepare("SELECT total FROM friendbook_cf_reaction_totals WHERE resource='posts/item39' AND type='like'").get().total,users);
assert.equal(report.workloads.unchangedProfileHeartbeat.changedRowsIncludingTriggers,0);
assert.equal(report.workloads.duplicateReceiptAndUnreadReset.changedRowsIncludingTriggers,0);
assertReadOnlyWorkloads(report);
if (collectPlans) report.queryPlans=[...queryPlans.values()];
console.log(JSON.stringify(report,null,2));
sql.close();
