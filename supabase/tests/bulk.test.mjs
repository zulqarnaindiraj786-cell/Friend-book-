import { test } from 'node:test';
import assert from 'node:assert/strict';
const env={SUPABASE_URL:'https://project.supabase.co',SUPABASE_SERVICE_ROLE_KEY:'test-only'};
globalThis.Deno={env:{get:key=>env[key]}};
const {handleRequest}=await import('../.test-build/bulk.mjs');
const encode=v=>typeof v==='string'?{stringValue:v}:typeof v==='boolean'?{booleanValue:v}:typeof v==='number'?{integerValue:String(v)}:Array.isArray(v)?{arrayValue:{values:v.map(encode)}}:{mapValue:{fields:Object.fromEntries(Object.entries(v).map(([k,v])=>[k,encode(v)]))}};
const document=v=>Response.json({fields:Object.fromEntries(Object.entries(v).map(([k,v])=>[k,encode(v)]))});
const request=(body={action:'bulk_mention',post_id:'post1'})=>new Request('https://project.supabase.co/functions/v1/bulk-mentions',{method:'POST',headers:{Authorization:'Bearer test-session'},body:JSON.stringify(body)});
const verify=async()=> 'alice';
function mock(options={}) {
 const label=options.highlight?'@highlight':'@followers';
 const post={authorId:'alice',privacy:'Public',content:label,mentions:[{uid:options.highlight?'bulk:highlight':'bulk:followers',label,start:0,end:label.length}],createdAt:Date.now(),...options.post};
 const calls=[]; const saved=new Map();
 globalThis.fetch=async(url,init={})=>{
  url=String(url);const body=init.body?JSON.parse(init.body):null;calls.push({url,init,body});
  if(url.includes('.workers.dev/v1/engagement/'))return options.d1 ? Response.json({comment:options.d1}) : new Response('',{status:503});
  if(url.endsWith('/posts/post1'))return document(post);
  if(url.endsWith('/posts/post1/comments/comment1'))return options.comment ? document(options.comment) : new Response('',{status:404});
  if(url.endsWith('/users/alice'))return document({displayName:'Alice',followers:options.followers||['bob'],...options.sender});
  if(url.includes('/users/'))return document({following:['alice'],...(options.recipient||{})});
  if(url.endsWith(':runQuery'))return Response.json([{document:{fields:{participants:encode(['alice','bob'])}}}]);
  if(url.includes('/rpc/reserve_bulk_mention'))return Response.json(options.limited?{limited:true}:{audience:body.p_audience});
  if(url.includes('/notifications/')){
   if(init.method==='PATCH') { saved.set(url.split('?')[0],body.fields); return Response.json({}); }
   return Response.json({fields:saved.get(url)});
  }
  if(url.endsWith('/functions/v1/send-fcm-notification'))return Response.json({accepted:1});
  throw Error('Unexpected URL '+url);
 };return calls;
}
test('bulk requires Firebase authentication and POST',async()=>{
 assert.equal((await handleRequest(new Request('https://example.invalid'))).status,405);
 assert.equal((await handleRequest(new Request('https://example.invalid',{method:'POST'}))).status,401);
 assert.equal((await handleRequest(request(),async()=>{throw Error('bad')})).status,401);
});
test('followers derives reciprocal recipients, writes mention inbox and calls existing gateway',async()=>{
 const calls=mock();const r=await handleRequest(request(),verify);assert.equal(r.status,200);assert.equal((await r.json()).inbox,1);
 assert.equal(calls.filter(c=>c.init.method==='PATCH').length,1);
 assert.equal(calls.find(c=>c.url.endsWith('/functions/v1/send-fcm-notification')).body.target_user_id,'bob');
});
test('highlight derives friends from persisted participants query',async()=>{
 const calls=mock({highlight:true,recipient:{following:[]}});const r=await handleRequest(request(),verify);assert.equal(r.status,200);
 assert.ok(calls.some(c=>c.url.endsWith(':runQuery')));assert.equal((await r.json()).inbox,1);
});
test('private/group sources, wrong author, missing selected tag and stale source cannot fan out',async()=>{
 for(const post of [{privacy:'Friends'},{groupId:'group1'},{authorId:'mallory'},{mentions:[]},{createdAt:Date.now()-86400001}]) {
  const calls=mock({post});const r=await handleRequest(request(),verify);assert.ok(r.status>=400);
  assert.equal(calls.filter(c=>c.init.method==='PATCH').length,0);
 }
});
test('client target list and spoofed title cannot choose recipients',async()=>{
 const calls=mock();const r=await handleRequest(request({action:'bulk_mention',post_id:'post1',target_user_id:'victim',recipients:['victim'],title:'spoofed'}),verify);
 assert.equal(r.status,200);const push=calls.find(c=>c.url.endsWith('/functions/v1/send-fcm-notification'));assert.equal(push.body.target_user_id,'bob');assert.equal(push.body.title,undefined);
});
test('blocked, opted-out, banned and non-reciprocal recipients are excluded',async()=>{
 for(const recipient of [{blockedUsers:['alice']},{bulkMentionsEnabled:false},{banned:true},{following:[]}]) {
  const calls=mock({recipient});const r=await handleRequest(request(),verify);assert.equal(r.status,200);assert.equal((await r.json()).inbox,0);
  assert.equal(calls.filter(c=>c.init.method==='PATCH').length,0);
 }
});
test('daily reservation denial writes no notifications',async()=>{
 const calls=mock({limited:true});const r=await handleRequest(request(),verify);assert.equal(r.status,429);
 assert.equal(calls.filter(c=>c.init.method==='PATCH').length,0);
});
test('bounded audience never exceeds twenty even for a large follower list',async()=>{
 const calls=mock({followers:Array.from({length:100},(_,i)=>'user'+i)});const r=await handleRequest(request(),verify);assert.equal(r.status,200);
 assert.equal(calls.filter(c=>c.init.method==='PATCH').length,20);
 assert.equal(calls.find(c=>c.url.includes('/rpc/reserve_bulk_mention')).body.p_audience.length,20);
});

test('canonical-only comments preserve bulk authorization and legacy fallback',async()=>{
 const source={authorId:'alice',content:'@followers',mentions:[{uid:'bulk:followers',label:'@followers',start:0,end:10}],createdAt:Date.now()};
 for(const options of [{comment:source},{post:{commentsMap:{comment1:source}}}]) {
  const calls=mock(options);const r=await handleRequest(request({action:'bulk_mention',post_id:'post1',comment_id:'comment1'}),verify);
  assert.equal(r.status,200);assert.equal((await r.json()).inbox,1);
  assert.ok(calls.some(c=>c.url.endsWith('/comments/comment1')));
 }
 const calls=mock({comment:{...source,authorId:'mallory'},post:{commentsMap:{comment1:source}}});
 const rejected=await handleRequest(request({action:'bulk_mention',post_id:'post1',comment_id:'comment1'}),verify);
 assert.equal(rejected.status,403);assert.equal(calls.filter(c=>c.init.method==='PATCH').length,0);
});

test('D1 bulk mentions validate persisted Worker source and never fall back to Firestore',async()=>{
 const source={authorId:'alice',content:'@followers',mentions:[{uid:'bulk:followers',label:'@followers',start:0,end:10}],createdAt:Date.now()};
 for(const [d1,status] of [[source,200],[{...source,authorId:'mallory'},403],[null,503]]){
  const calls=mock({d1,comment:source});const r=await handleRequest(request({action:'bulk_mention',post_id:'post1',comment_id:'comment1',comment_backend:'d1'}),verify);
  assert.equal(r.status,status);assert.ok(calls.some(c=>c.url.includes('.workers.dev/v1/engagement/')));
  assert.equal(calls.filter(c=>c.url.endsWith('/posts/post1/comments/comment1')).length,0);
 }
});
