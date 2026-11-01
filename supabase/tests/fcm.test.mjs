import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPair, exportPKCS8 } from 'jose';
const env = { SUPABASE_URL: 'https://project.supabase.co', SUPABASE_SERVICE_ROLE_KEY: 'local-test-only' };
globalThis.Deno = { env: { get: name => env[name] } };
const { handleRequest } = await import('../.test-build/gateway.mjs');
before(async () => {
  const { privateKey } = await generateKeyPair('RS256');
  env.FCM_SERVICE_ACCOUNT_JSON = JSON.stringify({ project_id:'friendbook-17551', client_email:'test@example.invalid', private_key:await exportPKCS8(privateKey) });
});
const ok = data => Response.json(data);
const request = body => new Request('https://project.supabase.co/functions/v1/send-fcm-notification', {
  method:'POST', headers:{Authorization:'Bearer test-session'}, body:JSON.stringify(body)
});
const verify = async () => 'alice';
const fields = values => ({fields:Object.fromEntries(Object.entries(values).map(([k,v]) => [k,
  Array.isArray(v) ? {arrayValue:{values:v.map(stringValue => ({stringValue}))}} :
  typeof v === 'boolean' ? {booleanValue:v} : k === 'createdAt' ? {timestampValue:v} : {stringValue:v}]))});
let calls;
function mock({devices=[{device_id:'phone',token:'test-token-value'}], fcmStatus=200, eventActor='alice'} = {}) {
  calls=[];
  globalThis.fetch = async (url, init={}) => {
    url=String(url); calls.push({url,init,body:init.body && typeof init.body === 'string' ? JSON.parse(init.body) : null});
    if (url.includes('oauth2.googleapis.com')) return ok({access_token:'test-access',expires_in:3600});
    if (url.includes('fcm.googleapis.com')) return fcmStatus === 200 ? ok({name:'projects/friendbook-17551/messages/test'}) :
      Response.json({error:{status:'UNAVAILABLE',details:[{errorCode:fcmStatus===404?'UNREGISTERED':'UNAVAILABLE'}]}},{status:fcmStatus});
    if (url.includes('/documents/users/')) return ok(fields({displayName:'Alice'}));
    if (url.includes('/documents/notifications/')) return ok(fields({fromUserId:eventActor,toUserId:'bob',type:'like',postId:'post123',text:'reacted to your post',createdAt:new Date().toISOString()}));
    if (url.includes('/documents/chats/')) return ok(fields(url.includes('/messages/') ? {senderId:'alice',text:'Hello',createdAt:new Date().toISOString()} : {participants:['alice','bob'],isGroup:true}));
    if (url.includes('/rpc/claim_push')) return ok(true);
    if (url.includes('push_delivery_claims')) return ok([]);
    if (url.includes('fcm_device_tokens')) return ok(init.method === 'POST' ? [] : devices);
    throw Error('Unexpected test URL');
  };
}
test('GET does not expose secrets', async () => {
  const r=await handleRequest(new Request('https://example.invalid'),verify);
  assert.equal(r.status,405); assert.equal((await r.json()).project,'friendbook-17551');
});
test('missing and invalid Firebase sessions are rejected', async () => {
  assert.equal((await handleRequest(new Request('https://example.invalid',{method:'POST'}),verify)).status,401);
  assert.equal((await handleRequest(request({}),async()=>{throw Error('bad')})).status,401);
});
test('registration derives owner from authenticated UID, never body UID', async () => {
  mock(); const r=await handleRequest(request({action:'register',device_id:'device1',user_id:'victim',token:'long-enough-test-token-value'}),verify);
  assert.equal(r.status,200); assert.equal(calls.find(x=>x.init.method==='POST').body.user_id,'alice');
});
test('no registered recipient is a visible retryable 409, not success', async () => {
  mock({devices:[]}); const r=await handleRequest(request({target_user_id:'bob',data:{notificationId:'notice'}}),verify);
  assert.equal(r.status,409); assert.equal((await r.json()).error,'RECIPIENT_NOT_REGISTERED');
});
test('persisted event must match authenticated sender', async () => {
  mock({eventActor:'mallory'}); const r=await handleRequest(request({target_user_id:'bob',data:{notificationId:'notice'}}),verify);
  assert.equal(r.status,403); assert.ok(!calls.some(x=>x.url.includes('fcm.googleapis.com')));
});
test('self test is self-only and uses direct FCM data with exact routing', async () => {
  mock(); const r=await handleRequest(request({action:'self_test',target_user_id:'victim'}),verify);
  assert.equal(r.status,200); assert.equal((await r.json()).accepted,1);
  const push=calls.find(x=>x.url.includes('fcm.googleapis.com'));
  assert.equal(push.body.message.data.toUserId,'alice');
  assert.equal(push.body.message.data.type,'push_test');
  assert.equal(push.body.message.android.priority,'HIGH');
  assert.equal(push.body.message.notification,undefined);
});
test('chat pushes preserve exact chat and group destination', async () => {
  mock(); const r=await handleRequest(request({target_user_id:'bob',data:{type:'message',chatId:'chat1',messageId:'msg1'}}),verify);
  assert.equal(r.status,200);
  const d=calls.find(x=>x.url.includes('fcm.googleapis.com')).body.message.data;
  assert.equal(d.chatId,'chat1'); assert.equal(d.isGroup,'true'); assert.equal(d.body,'Hello');
});
test('transient FCM rejection releases claim and returns 503 for retry', async () => {
  mock({fcmStatus:503}); const r=await handleRequest(request({action:'self_test'}),verify);
  assert.equal(r.status,503); assert.ok(calls.some(x=>x.url.includes('push_delivery_claims')&&x.init.method==='DELETE'));
});
test('UNREGISTERED removes only the exact stale token', async () => {
  mock({fcmStatus:404}); const r=await handleRequest(request({action:'self_test'}),verify);
  assert.equal(r.status,409); assert.ok(calls.some(x=>x.url.includes('token=eq.test-token-value')&&x.init.method==='DELETE'));
});
