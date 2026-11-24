import { canonicalSeed } from './fixtures/canonical.js';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';
import {createWorker} from '../../worker.js';

test('unread page index handles compatibility fallback without reading another inbox',async t=>{
 const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
 const dir=new URL('../migrations/',import.meta.url),removal='0081_remove_redundant_unread_index.sql';
 for(const file of readdirSync(dir).filter(f=>f.endsWith('.sql')&&f!==removal&&f!=='0093_remove_redundant_notification_recipient.sql'&&f!=='0102_remove_legacy_notification_recipient.sql').sort())sql.exec(readFileSync(new URL(file,dir),'utf8'));
 const c=sql.prepare('INSERT OR REPLACE INTO friendbook_cf_firestore_documents VALUES(?,?,?,?,?,?,?,?)');
 const a=canonicalSeed(sql);
 for(const uid of ['alice','bob'])c.run('users/'+uid,'users','users',uid,uid,JSON.stringify({uid}),1,1);
 for(let i=0;i<1000;i++){
  const id='n'+String(i).padStart(4,'0'),to=i%10?'bob':'alice';
  const data={fromUserId:'alice',toUserId:to,isRead:i%3===0,createdAt:i+1};
  if(i%5===0)a.run('notifications/'+id,'notifications',id,'alice',JSON.stringify({...data,id}),i);
  else {c.run('notifications/'+id,'notifications','notifications',id,'alice',JSON.stringify(data),1,i);
    if(i%2===0)a.run('notifications/'+id,'notifications',id,'alice',JSON.stringify({...data,id}),i);}
 }
 a.run('notifications/wrapped','notifications','wrapped','alice',JSON.stringify({fields:{fromUserId:{stringValue:'alice'},toUserId:{stringValue:'bob'},isRead:{booleanValue:false},createdAt:{integerValue:'9'}}}),1);
 let plans=[],fallbacks=0;
 const db={withSession(){return this;},prepare(q){let p;try{p=sql.prepare(q);}catch(error){if(q.includes('INDEXED BY idx_cf_notif_unread'))fallbacks++;throw error;}
  return{bind(...args){if(/^\s*(SELECT|WITH)\b/i.test(q))plans.push({q,plan:sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(x=>x.detail)});
   return{async first(){return p.get(...args)||null},async all(){return{results:p.all(...args)}},async run(){return p.run(...args)}};}};
 }};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 const filters=[{field:'toUserId',op:'==',value:'bob'},{field:'isRead',op:'==',value:false}],orderBy=[{field:'createdAt',direction:'DESCENDING'}];
 async function suite(){
  plans=[];const specs=[{parent:'notifications',filters},{parent:'notifications',filters,orderBy,limit:20},
   {parent:'notifications',filters,orderBy,limit:20,startAfter:[900,'n0899']},
   {parent:'notifications',filters,orderBy,limit:20,limitToLast:true,endBefore:[900,'n0899']},
   {parent:'notifications',filters:[filters[0]],orderBy,limit:20},
   {parent:'notifications',filters:[{field:'toUserId',op:'==',value:'alice'}],orderBy,limit:20}];
  const results=[];for(const spec of specs){const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{
   method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify(spec)}),env);
   results.push({status:r.status,body:await r.json()});}
  for(const uid of ['bob','alice']){const u='https://example.test/v1/firestore/count?parent=notifications&filters='+encodeURIComponent(JSON.stringify([{field:'isRead',op:'==',value:false}]));
   const r=await app.fetch(new Request(u,{headers:{authorization:`Bearer ${uid}`}}),env);results.push({status:r.status,body:await r.json()});}
  return results;
 }
 const expected=await suite();assert.deepEqual(expected.map(x=>x.status),Array(8).fill(200));
 assert.deepEqual(expected.map(x=>x.body.documents?.length??x.body.count),[601,20,20,20,20,0,601,66]);
 // Non-unread compatibility queries must seek the same recipient even if the
 // older prefix indexes are absent. Preserve canonical shadowing and wrapped
 // legacy data; this is an offline experiment, not a production migration.
 const generic=async(uid)=>{const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{
  method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify({parent:'notifications'})}),env);
  assert.equal(r.status,200);return r.json();};
 const genericExpected=await Promise.all(['alice','bob'].map(generic));
 assert.ok(plans.some(x=>x.q.includes('INDEXED BY idx_cf_notif_page') && x.plan.some(p=>p.includes('idx_cf_notif_page')&&p.includes('<expr>=?'))));
 sql.exec('DROP INDEX idx_cf_firestore_inbox_recipient; DROP INDEX idx_cf_legacy_inbox_recipient');
 assert.deepEqual(await Promise.all(['alice','bob'].map(generic)),genericExpected);
 assert.deepEqual(await suite(),expected);
 assert.ok(plans.some(x=>x.q.includes('INDEXED BY idx_cf_notif_page') && x.plan.some(p=>p.includes('idx_cf_notif_page')&&p.includes('<expr>=?'))));
 assert.ok(!plans.some(x=>x.q.includes('FROM friendbook_cf_documents')),'inbox reads are canonical-only');
 sql.exec(readFileSync(new URL('0025_inbox_recipient_indexes.sql',dir),'utf8'));
 assert.ok(expected.slice(0,5).every(x=>x.body.documents.every(d=>d.data.toUserId==='bob')));
 const forced=()=>plans.filter(x=>x.q.includes('INDEXED BY idx_cf_notif_unread'));
 assert.ok(forced().length>=3);assert.ok(forced().every(x=>x.plan.some(p=>p.includes('idx_cf_notif_unread')&&p.includes('<expr>=?'))));
 sql.exec("DELETE FROM friendbook_cf_firestore_documents WHERE path='notifications/wrapped'");
 const standard=await suite();assert.deepEqual(standard.map(x=>x.body.documents?.length??x.body.count),[600,20,20,20,20,0,600,66]);
 const migration=readFileSync(new URL(removal,dir),'utf8');sql.exec(migration);sql.exec(migration);
 assert.equal(sql.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_cf_inbox_unread'").get(),undefined);
 assert.deepEqual(await suite(),standard);
 assert.ok(forced().every(x=>x.plan.some(p=>p.includes('idx_cf_notif_unread')&&p.includes('<expr>=?'))));
 assert.ok(plans.some(x=>x.plan.some(p=>p.includes('idx_cf_notif_unread')&&p.includes('<expr>'))));
 a.run('notifications/wrapped','notifications','wrapped','alice',JSON.stringify({fields:{fromUserId:{stringValue:'alice'},toUserId:{stringValue:'bob'},isRead:{booleanValue:false},createdAt:{integerValue:'9'}}}),1);
 assert.deepEqual(await suite(),expected);
 // On older/incomplete schemas, fall back to the original recipient-scoped path.
 sql.exec(readFileSync(new URL('0036_inbox_unread_indexes.sql',dir),'utf8'));
 sql.exec('DROP INDEX idx_cf_notif_unread');fallbacks=0;
 assert.deepEqual(await suite(),expected);assert.ok(fallbacks>=3);
 assert.ok(plans.some(x=>x.plan.some(p=>p.includes('idx_cf_inbox_unread'))));
 sql.exec('DROP INDEX idx_cf_notif_page');
 assert.deepEqual(await Promise.all(['alice','bob'].map(generic)),genericExpected);
 assert.ok(plans.some(x=>x.plan.some(p=>p.includes('idx_cf_firestore_inbox_recipient'))));
});
