import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';
import {fixture} from './fixtures/interaction-db.js';
const migration=n=>readFileSync(new URL(`../migrations/${n}.sql`,import.meta.url),'utf8');

test('covering state lookups preserve ACL, staging, unknown state and old-schema behavior',async t=>{
 const f=fixture();t.after(f.close);
 for(const n of ['0029_feed_page_indexes','0051_feed_exception_indexes','0099_feed_high_rank_exceptions'])f.sql.exec(migration(n));
 const put=f.sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for(const id of ['user1','author'])put.run(`users/${id}`,'users','users',id,id,JSON.stringify({uid:id}),1,1);
 for(const id of ['p1','p2','p3'])put.run(`posts/${id}`,'posts','posts',id,'author',JSON.stringify({authorId:'author',privacy:'Public',likes:2,createdAt:{__type:'timestamp',millis:1},reactionsMap:{user1:'like'}}),1,1);
 f.sql.prepare('INSERT INTO friendbook_cf_reactions VALUES (?,?,?,?)').run('posts/p1','user1','love',1);
 let attempted=0,plain=0,fail=false;
 const original=f.env.DB,db={...original,withSession(){return this;},prepare(q){
  if(q.includes('INDEXED BY idx_cf_interaction_resource_state_cover')){attempted++;if(fail)throw Error('storage unavailable');}
  else if(q.includes('SELECT resource,state FROM friendbook_cf_interaction_resources'))plain++;
  return original.prepare(q);
 }};
 const app=createWorker(async token=>token),env={...f.env,DB:db,FIREBASE_PROJECT_ID:'test',D1_FEED_ENGAGEMENT_ENABLED:'true'};
 async function request(){
  const body={parent:'posts',filters:[],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:3,feedEngagement:true};
  const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer user1','content-type':'application/json'},body:JSON.stringify(body)}),env);
  assert.equal(r.status,200,await r.clone().text());return (await r.json()).documents;
 }
 const before=await request();assert.equal(attempted,1);assert.equal(plain,1,'old schema uses the same state lookup');
 f.sql.exec(migration('0100_interaction_state_cover'));
 const plan=f.sql.prepare('EXPLAIN QUERY PLAN SELECT resource,state FROM friendbook_cf_interaction_resources INDEXED BY idx_cf_interaction_resource_state_cover WHERE resource IN (?,?,?)').all('posts/p1','posts/p2','posts/p3').map(r=>r.detail).join('\n');
 assert.match(plan,/COVERING INDEX idx_cf_interaction_resource_state_cover \(resource=\?\)/);
 const after=await request();assert.deepEqual(after,before);assert.equal(attempted,2);assert.equal(plain,1);
 const p1=after.find(d=>d.id==='p1'),p2=after.find(d=>d.id==='p2'),p3=after.find(d=>d.id==='p3');
 assert.deepEqual(p1.engagement.types,[{type:'love',count:1,mine:1}]);assert.ok(!Object.hasOwn(p1.data,'reactionsMap'));
 assert.deepEqual(p2.engagement.types,[]);assert.ok(Object.hasOwn(p2.data,'reactionsMap'));
 assert.equal(p3.engagement.reactionStateUnknown,true);assert.ok(Object.hasOwn(p3.data,'reactionsMap'));
 f.sql.prepare("UPDATE friendbook_cf_interaction_resources SET state='staging' WHERE resource='posts/p1'").run();
 const changed=await request();assert.deepEqual(changed.find(d=>d.id==='p1').engagement.types,[{type:'love',count:1,mine:1}],'staging resource still has the same live summary as detail');
 assert.ok(Object.hasOwn(changed.find(d=>d.id==='p1').data,'reactionsMap'),'staging retains legacy document fields');
 fail=true;const unavailable=await request();assert.equal(plain,1,'a storage error must not be misclassified as missing schema');
 assert.ok(unavailable.every(d=>!Object.hasOwn(d,'engagement')),'summary failure leaves authorized documents intact');
});
