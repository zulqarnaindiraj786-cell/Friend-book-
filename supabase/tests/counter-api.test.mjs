import {before,test} from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPair,exportJWK,createLocalJWKSet,SignJWT} from 'jose';
const env={SUPABASE_URL:'https://test.invalid',SUPABASE_SERVICE_ROLE_KEY:'private-test-only'};
globalThis.Deno={env:{get:key=>env[key]}};
const {handleRequest}=await import('../.test-build/counter-api.mjs');
const {firebaseVerifier}=await import('../.test-build/firebase-auth.mjs');
let verify,privateKey,calls;
const issuer='https://securetoken.google.com/friendbook-17551';
before(async()=>{
  const keys=await generateKeyPair('RS256');privateKey=keys.privateKey;
  verify=firebaseVerifier(createLocalJWKSet({keys:[{...await exportJWK(keys.publicKey),kid:'local'}]}));
});
const token=(sub='alice',aud='friendbook-17551',iss=issuer,exp='10m')=>new SignJWT({}).setProtectedHeader({alg:'RS256',kid:'local'}).setSubject(sub).setAudience(aud).setIssuer(iss).setIssuedAt().setExpirationTime(exp).sign(privateKey);
const request=(body,auth='Bearer test')=>new Request('https://test.invalid/functions/v1/counter-state',{method:'POST',headers:{Authorization:auth},body:typeof body==='string'?body:JSON.stringify(body)});
const valid={action:'snapshot',device_id:'phone'};
function mock(result={ready:false,counters:[]},status=200){calls=[];globalThis.fetch=async(url,init)=>{calls.push({url,init,body:JSON.parse(init.body)});return Response.json(result,{status});};}
test('real JWT verification enforces project, issuer, algorithm, expiry and subject',async()=>{
  assert.equal(await verify(await token()),'alice');
  for(const bad of [await token('alice','other-project'),await token('alice',undefined,'https://evil.invalid'),await token('alice',undefined,undefined,0),await token('')])await assert.rejects(()=>verify(bad));
  const hs=await new SignJWT({sub:'alice',aud:'friendbook-17551',iss:issuer}).setProtectedHeader({alg:'HS256'}).sign(new TextEncoder().encode('not-a-firebase-signature-123456789'));
  await assert.rejects(()=>verify(hs));
  const noExpiry=await new SignJWT({sub:'alice',aud:'friendbook-17551',iss:issuer,iat:Math.floor(Date.now()/1000)}).setProtectedHeader({alg:'RS256',kid:'local'}).sign(privateKey);
  await assert.rejects(()=>verify(noExpiry));
});
test('method and missing/invalid authentication are rejected without database access',async()=>{
  mock();assert.equal((await handleRequest(new Request('https://test.invalid'),verify)).status,405);
  assert.equal((await handleRequest(request(valid,''),verify)).status,401);
  assert.equal((await handleRequest(request(valid),verify)).status,401);assert.equal(calls.length,0);
});
test('verified owner overrides spoofed body UID and only one private RPC is called',async()=>{
  mock();const response=await handleRequest(request({...valid,uid:'victim',p_uid:'victim'},'Bearer '+await token()),verify);
  assert.equal(response.status,200);assert.equal(response.headers.get('Cache-Control'),'no-store');
  assert.equal(calls.length,1);assert.equal(calls[0].body.p_uid,'alice');assert.equal(calls[0].body.p_device,'phone');
  assert.equal(calls[0].url,'https://test.invalid/rest/v1/rpc/counter_v1_access');
  assert.equal((await response.text()).includes('private-test-only'),false);
});
test('ack accepts exact decimal strings only, never client event application/seed',async()=>{
  for(const bad of [{action:'apply'},{action:'seed'},{action:'ack',kind:'messages',observed:1},{action:'ack',kind:'messages',observed:'-1'},{action:'ack',kind:'messages',observed:'9223372036854775808'}]){
    mock();assert.equal((await handleRequest(request({...valid,...bad}),async()=>'alice')).status,400);assert.equal(calls.length,0);
  }
  mock();assert.equal((await handleRequest(request({...valid,action:'ack',kind:'messages',observed:'9223372036854775807'}),async()=>'alice')).status,200);
  assert.equal(calls[0].body.p_observed,'9223372036854775807');
});
test('malformed/oversized bodies rejected before database; size limit ignores Content-Length claims',async()=>{
  mock();assert.equal((await handleRequest(request('{bad'),async()=>'alice')).status,400);
  const oversized=request('x'.repeat(2049));oversized.headers.set('Content-Length','1');
  assert.equal((await handleRequest(oversized,async()=>'alice')).status,413);assert.equal(calls.length,0);
});
test('device/rate/not-ready errors stay explicit; database failures hide internals and never return zero success',async()=>{
  for(const [error,status]of[['DEVICE_NOT_REGISTERED',403],['RATE_LIMITED',429],['COUNTER_NOT_READY',409]]){
    mock({error});assert.equal((await handleRequest(request(valid),async()=>'alice')).status,status);
  }
  for(const result of [{error:'secret internal stack'},null,{ready:true}]){mock(result);const r=await handleRequest(request(valid),async()=>'alice');assert.equal(r.status,503);assert.equal((await r.text()).includes('secret'),false);}
  mock({message:'sensitive sql'},500);assert.equal((await handleRequest(request(valid),async()=>'alice')).status,503);
  globalThis.fetch=async()=>{throw Error('private-test-only');};const r=await handleRequest(request(valid),async()=>'alice');assert.equal(r.status,503);assert.equal((await r.text()).includes('private-test-only'),false);
});
