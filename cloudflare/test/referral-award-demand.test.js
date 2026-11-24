import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';
const migration=name=>readFileSync(new URL(`../migrations/${name}.sql`,import.meta.url),'utf8');

test('referral awards use historical counts, are monotonic and never write without an upgrade',async t=>{
 const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
 for(const name of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0018_social_guards','0046_referral_attribution'])sql.exec(migration(name));
 const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');const now=Date.now();
 const user=(id,data={},created=1)=>put.run(`users/${id}`,'users','users',id,id,JSON.stringify(data),created,created);
 let historySequence=0;const history=(owner,n)=>{for(let i=0;i<n;i++){const id=`${owner}_${historySequence++}`;put.run(`referrals/${id}`,'referrals','referrals',id,owner,JSON.stringify({refBy:owner,refTo:`historic_${id}`}),1,1);}};
 user('alice',{tickLevel:'none'});user('paid',{tickLevel:'diamond',isVerified:true});user('legacy',{isVerified:true});user('unknown',{tickLevel:'platinum',isVerified:true});
 user('wrapped',{fields:{tickLevel:{stringValue:'none'},isVerified:{booleanValue:false}}});
 history('alice',4);history('paid',5);history('legacy',20);history('unknown',50);history('wrapped',50);
 let beforeBatch=null,probes=0;const plans=[];sql.function('quota_probe',path=>{probes++;return 1;});
 const db={prepare(q){
  const instrumented=q.includes('idx_cf_referrals_from')?q.replace("AND json_extract(document_json,'$.refBy')=? LIMIT 50", "AND json_extract(document_json,'$.refBy')=? AND quota_probe(path) LIMIT 50"):q;
  const stmt=sql.prepare(instrumented);return{bind(...args){return{
  run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null
 };}};},async batch(stmts){if(beforeBatch){const f=beforeBatch;beforeBatch=null;f();}sql.exec('BEGIN');try{const out=[];for(const s of stmts)out.push(await s.run());sql.exec('COMMIT');return out;}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 const call=(uid,path,body={})=>app.fetch(new Request(`https://example.test${path}`,{method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify(body)}),env);
 const status=uid=>call(uid,'/v1/firestore/referral-status');
 const profile=uid=>JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`users/${uid}`).document_json);
 const mirror=uid=>JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').get(`users/${uid}`).document_json);
 const changes=()=>sql.prepare('SELECT total_changes() AS n').get().n;
 let response=await status('alice');assert.equal(response.status,200,await response.clone().text());assert.deepEqual(await response.json(),{count:4,tickLevel:'none',isVerified:false,awarded:false});
 let before=changes();response=await status('paid');assert.deepEqual(await response.json(),{count:5,tickLevel:'diamond',isVerified:true,awarded:false});assert.equal(changes(),before,'paid diamond must not be written or downgraded');
 response=await status('legacy');assert.deepEqual(await response.json(),{count:20,tickLevel:'gold',isVerified:true,awarded:true});assert.equal(mirror('legacy').tickLevel,'gold');
 before=changes();await status('legacy');assert.equal(changes(),before,'repeated refresh must make zero writes');
 response=await status('unknown');assert.deepEqual(await response.json(),{count:50,tickLevel:'platinum',isVerified:true,awarded:false});assert.equal(profile('unknown').tickLevel,'platinum');
 before=changes();response=await status('wrapped');assert.equal((await response.json()).awarded,false);
 assert.equal(changes(),before,'wrapped profile must not be modified without a safe migration');
 history('paid',1500);probes=0;response=await status('paid');assert.equal((await response.json()).count,50);
 assert.ok(probes<=100,`threshold reconciliation scanned too many referrals: ${probes}`);
 user('new1',{},now);response=await call('new1','/v1/firestore/claim-referral',{inviter:'alice'});assert.equal(response.status,201,await response.clone().text());
 assert.equal(profile('alice').tickLevel,'blue');assert.equal(mirror('alice').tickLevel,'blue');
 before=changes();assert.equal((await call('new1','/v1/firestore/claim-referral',{inviter:'alice'})).status,200);assert.equal(changes(),before,'replayed claim makes no writes');
 history('alice',15);response=await status('alice');assert.deepEqual(await response.json(),{count:20,tickLevel:'gold',isVerified:true,awarded:true});
 history('alice',30);response=await status('alice');assert.deepEqual(await response.json(),{count:50,tickLevel:'diamond',isVerified:true,awarded:true});
 // An admin/paid update arriving immediately before the reconciliation batch wins.
 user('race',{tickLevel:'none'});history('race',5);
 beforeBatch=()=>sql.prepare(`UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.tickLevel','diamond','$.isVerified',json('true')) WHERE path='users/race'`).run();
 response=await status('race');assert.deepEqual(await response.json(),{count:5,tickLevel:'diamond',isVerified:true,awarded:false});assert.equal(profile('race').tickLevel,'diamond');
 // A stale claim guard rejects the whole claim when another authority changes the inviter.
 user('racer',{tickLevel:'none'});history('racer',4);user('new2',{},now);
 beforeBatch=()=>sql.prepare(`UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.tickLevel','diamond','$.isVerified',json('true')) WHERE path='users/racer'`).run();
 response=await call('new2','/v1/firestore/claim-referral',{inviter:'racer'});assert.equal(response.status,409);assert.equal(profile('racer').tickLevel,'diamond');
 assert.equal(sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_referral_claims WHERE referred_uid='new2'").get().n,0);
 // A mirror failure rolls back the entire signup claim and its badge update.
 user('atomic',{tickLevel:'none'});history('atomic',4);user('new3',{},now);
 sql.exec(`CREATE TRIGGER test_reject_referral_badge BEFORE INSERT ON friendbook_cf_documents WHEN NEW.path='users/atomic' BEGIN SELECT RAISE(ABORT,'test mirror failure'); END`);
 response=await call('new3','/v1/firestore/claim-referral',{inviter:'atomic'});assert.equal(response.status,503);
 assert.equal(profile('atomic').tickLevel,'none');assert.equal(sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_referral_claims WHERE referred_uid='new3'").get().n,0);
 sql.exec('DROP TRIGGER test_reject_referral_badge');
 // Caller cannot choose another account or forge a count.
 response=await call('alice','/v1/firestore/referral-status',{uid:'paid',count:999});assert.equal((await response.json()).tickLevel,'diamond');
});
