import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { generateKeyPairSync } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fcmGateway } from '../fcm-push.js';

test('FCM delivery uses durable atomic claims across retries and allows retry after provider errors', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0015_additive_fcm_devices.sql'])sql.exec(readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8'));
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};}};
  const add=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  add.run('users/alice','users','users','alice','alice',JSON.stringify({displayName:'Alice'}),1,1);
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('users/bob','users','bob','bob',JSON.stringify({fields:{displayName:{stringValue:'Bob'}}}),1);
  add.run('notifications/n1','notifications','notifications','n1','alice',JSON.stringify({fromUserId:'alice',toUserId:'bob',type:'friend_request',text:'request',createdAt:{__type:'timestamp',millis:Date.now()}}),1,1);
  sql.prepare('INSERT INTO friendbook_cf_fcm_devices VALUES (?,?,?,?)').run('dev1','bob','x'.repeat(30),1);
  const key=generateKeyPairSync('rsa',{modulusLength:2048}).privateKey.export({type:'pkcs8',format:'pem'});
  const env={DB:db,FIREBASE_ADMIN_SERVICE_ACCOUNT:JSON.stringify({project_id:'friendbook-17551',client_email:'fake@example.test',private_key:key})};
  let sends=0,fail=false,expire=false,oauth=0;const original=globalThis.fetch;
  globalThis.fetch=async url=>String(url).includes('oauth2.googleapis.com')?Response.json({access_token:`fake${++oauth}`,expires_in:3600}):(sends++,expire?(expire=false,Response.json({error:{}},{status:401})):fail?Response.json({error:{}},{status:503}):Response.json({name:'messages/success'}));
  const send=()=>fcmGateway(new Request('https://example.test/functions/v1/send-fcm-notification',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({target_user_id:'bob',data:{notificationId:'n1'}})}),env,{uid:'alice'});
  try {
    const pair=await Promise.all([send(),send()]);assert.deepEqual(pair.map(r=>r.status),[200,200]);
    assert.equal(sends,1);const bodies=await Promise.all(pair.map(r=>r.json()));assert.equal(bodies.reduce((n,b)=>n+b.accepted,0),1);assert.equal(bodies.reduce((n,b)=>n+b.duplicate,0),1);
    sql.prepare('DELETE FROM friendbook_cf_push_claims').run();fail=true;assert.equal((await send()).status,503);
    assert.equal(sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_push_claims').get().n,0);
    fail=false;assert.equal((await send()).status,200);assert.equal(sends,3);
    const selfTest=device=>fcmGateway(new Request('https://example.test/functions/v1/send-fcm-notification',{
      method:'POST',body:JSON.stringify({action:'self_test',device_id:device}),
    }),env,{uid:'bob'});
    assert.equal((await selfTest('wrong_device')).status,409);
    assert.equal((await selfTest('dev1')).status,200);
    assert.equal(sends,4);
    sql.prepare('DELETE FROM friendbook_cf_push_claims').run();expire=true;const oldOauth=oauth;
    assert.equal((await send()).status,200);
    assert.equal(oauth,oldOauth+1);assert.equal(sends,6);
  } finally {globalThis.fetch=original;sql.close();}
});
