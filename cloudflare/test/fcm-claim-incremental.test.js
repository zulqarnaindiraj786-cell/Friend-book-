import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { generateKeyPairSync } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { claimFcmDevice, fcmGateway } from '../fcm-push.js';

const migration=name=>readFileSync(new URL(`../migrations/${name}`,import.meta.url),'utf8');
function dbFor(sql){return {prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{
  run:async()=>({success:true,meta:{changes:Number(stmt.run(...args).changes)}}),
  first:async()=>stmt.get(...args)||null,all:async()=>({results:stmt.all(...args)}),
};}};}};}
const oldSql='INSERT INTO friendbook_cf_fcm_device_claims(event_key,created_at) VALUES (?,?) ON CONFLICT(event_key) DO NOTHING';

test('new and old Workers share atomic claims with no backfill, including rollback',async()=>{
  const sql=new DatabaseSync(':memory:');sql.exec(migration('0056_fcm_device_claims.sql'));
  const old=(key)=>Number(sql.prepare(oldSql).run(key,1).changes);
  old('existing');old('keep-other');
  const before=sql.prepare('SELECT event_key,created_at FROM friendbook_cf_fcm_device_claims ORDER BY event_key').all();
  sql.exec(migration('0085_fcm_claims_incremental.sql'));sql.exec(migration('0085_fcm_claims_incremental.sql'));
  assert.deepEqual(sql.prepare('SELECT event_key,created_at FROM friendbook_cf_fcm_device_claims ORDER BY event_key').all(),before);
  assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_fcm_device_claims_v2').get().n,0);
  const db=dbFor(sql),fresh=key=>claimFcmDevice(db,key,2);
  assert.equal((await fresh('existing')).acquired,false,'new version respects old claims');
  assert.equal((await fresh('new-first')).acquired,true);
  assert.equal(old('new-first'),0,'old version and rollback respect new claims');
  assert.equal(old('old-first'),1);
  assert.equal((await fresh('old-first')).acquired,false);
  assert.equal((await fresh('new-first')).acquired,false);
  const results=await Promise.all(Array.from({length:25},(_,i)=>i%2?fresh('race').then(r=>+r.acquired):Promise.resolve(old('race'))));
  assert.equal(results.reduce((a,b)=>a+b,0),1,'only one old/new claimant wins');
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_fcm_device_claims WHERE event_key='race'").get().n
    +sql.prepare("SELECT count(*) n FROM friendbook_cf_fcm_device_claims_v2 WHERE event_key='race'").get().n,1);
  assert.equal((await fresh('same-event:other-device')).acquired,true,'distinct devices retain separate claims');
  sql.close();
});

test('pre-migration fallback and unexpected storage errors fail closed',async()=>{
  const sql=new DatabaseSync(':memory:');sql.exec(migration('0056_fcm_device_claims.sql'));
  const db=dbFor(sql);
  assert.deepEqual(await claimFcmDevice(db,'pre-migration',1),{acquired:true,table:'friendbook_cf_fcm_device_claims'});
  assert.equal((await claimFcmDevice(db,'pre-migration',2)).acquired,false);
  sql.exec(migration('0085_fcm_claims_incremental.sql'));
  sql.exec("CREATE TRIGGER reject_new_claim BEFORE INSERT ON friendbook_cf_fcm_device_claims_v2 BEGIN SELECT RAISE(ABORT,'simulated error'); END");
  await assert.rejects(claimFcmDevice(db,'error',3),/simulated error/);
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_fcm_device_claims WHERE event_key='error'").get().n,0);
  sql.close();
});

test('FCM failure releases only new claim, then a retry succeeds without duplicate delivery',async()=>{
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0015_additive_fcm_devices.sql','0056_fcm_device_claims.sql','0085_fcm_claims_incremental.sql'])sql.exec(migration(n));
  const add=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(const uid of ['alice','bob'])add.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({displayName:uid}),1,1);
  for(const id of ['n1','n2'])add.run(`notifications/${id}`,'notifications','notifications',id,'alice',JSON.stringify({fromUserId:'alice',toUserId:'bob',type:'friend_request',text:'request',createdAt:{__type:'timestamp',millis:Date.now()}}),1,1);
  sql.prepare('INSERT INTO friendbook_cf_fcm_devices VALUES (?,?,?,?)').run('dev1','bob','x'.repeat(30),1);
  sql.prepare(oldSql).run('unrelated-retained',1);
  const key=generateKeyPairSync('rsa',{modulusLength:2048}).privateKey.export({type:'pkcs8',format:'pem'});
  const env={DB:dbFor(sql),FIREBASE_ADMIN_SERVICE_ACCOUNT:JSON.stringify({project_id:'friendbook-17551',client_email:'fake@example.test',private_key:key})};
  const original=globalThis.fetch;let fail=true,unregistered=false,sends=0;
  globalThis.fetch=async url=>String(url).includes('oauth2.googleapis.com')?Response.json({access_token:'fake',expires_in:3600}):(sends++,unregistered?Response.json({error:{details:[{errorCode:'UNREGISTERED'}]}},{status:404}):fail?Response.json({error:{}},{status:503}):Response.json({name:'messages/success'}));
  const send=(id='n1')=>fcmGateway(new Request('https://example.test/functions/v1/send-fcm-notification',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({target_user_id:'bob',data:{notificationId:id}})}),env,{uid:'alice'});
  try {
    assert.equal((await send()).status,503);
    assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_fcm_device_claims_v2').get().n,0);
    assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_fcm_device_claims').get().n,1,'unrelated old claim retained');
    fail=false;const pair=await Promise.all([send(),send()]);assert.deepEqual(pair.map(r=>r.status),[200,200]);
    const bodies=await Promise.all(pair.map(r=>r.json()));assert.equal(bodies.reduce((n,b)=>n+b.accepted,0),1);assert.equal(bodies.reduce((n,b)=>n+b.duplicate,0),1);
    assert.equal(sends,2);
    unregistered=true;const invalid=await send('n2');assert.equal(invalid.status,200);
    assert.equal((await invalid.json()).skipped,'NO_VALID_RECIPIENT_DEVICE');
    assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_fcm_device_claims_v2').get().n,1,'failed device claim removed; successful claim retained');
    assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_fcm_devices').get().n,0);
    assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_fcm_device_claims').get().n,1,'historical claim retained');
  } finally {globalThis.fetch=original;sql.close();}
});
