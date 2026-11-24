import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';
const migration=n=>readFileSync(new URL(`../migrations/${n}.sql`,import.meta.url),'utf8');

test('story expiry pages seek current rows, retain canonical ACL and match generic ordering',async t=>{
 const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
 for(const n of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0018_social_guards','0062_story_timestamp_expiry_lookup','0097_story_expiry_exceptions'])sql.exec(migration(n));
 const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for(const id of ['viewer','author'])put.run(`users/${id}`,'users','users',id,id,JSON.stringify({uid:id}),1,1);
 const add=(id,expiry,privacy='Public')=>put.run(`stories/${id}`,'stories','stories',id,'author',JSON.stringify({authorId:'author',privacy,expiresAt:expiry,viewedBy:['viewer'],reactions:{viewer:'love'}}),1,1);
 for(let i=0;i<5000;i++)add(`expired${i}`,{__type:'timestamp',millis:i-6000});
 add('boundary-zero',{__type:'timestamp',millis:0});
 for(let i=0;i<800;i++)add(`s${String(i).padStart(4,'0')}`,{__type:'timestamp',millis:Math.floor(i/4)+10},i%13===0?'Only me':'Public');
 add('odd-fraction',{__type:'timestamp',millis:10.75});
 add('odd-array',['value']);add('odd-map',{x:'a'});add('字',{__type:'timestamp',millis:12});
 let hits=0,plans=[],fail=false,genericScans=0;
 const db={withSession(){return this;},prepare(query){
  if(fail && query.includes('idx_cf_story_expiry_page'))throw Error('storage failure');
  if(query.includes('idx_cf_story_timestamp_expiry_lookup') && !query.includes('ORDER BY'))genericScans++;
  const stmt=sql.prepare(query);
  const bound=(args)=>({first:async()=>stmt.get(...args)||null,all:async()=>{
   const results=stmt.all(...args);
   if(query.includes('INDEXED BY idx_cf_story_expiry_page')){
    hits+=results.length;plans.push(sql.prepare('EXPLAIN QUERY PLAN '+query).all(...args).map(x=>x.detail).join('\n'));
   }
   return {results};
  }});
  return {...bound([]),bind:(...args)=>bound(args)};
 }};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 const timestamp=millis=>({__type:'timestamp',millis});
 async function request(actor,cursor,limit,reference=false){
  const f={field:'expiresAt',op:'>',value:timestamp(0)};
  const body={parent:'stories',filters:reference?[f,f]:[f],orderBy:[{field:'expiresAt',direction:'ASCENDING'}],limit,...(cursor?{startAfter:cursor}:{})};
  const res=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:`Bearer ${actor}`,'content-type':'application/json'},body:JSON.stringify(body)}),env);
  assert.equal(res.status,200,await res.clone().text());return (await res.json()).documents;
 }
 for(const actor of ['viewer','author'])for(const cursor of [null,[timestamp(-1),'s0001'],[timestamp(0),'s0001'],[timestamp(10),'s0001'],[timestamp(50),'s0161'],[timestamp(199),'s0759']])for(const size of [1,3,10]){
  hits=0;const actual=await request(actor,cursor,size);const sampled=hits;
  assert.deepEqual(actual,await request(actor,cursor,size,true),JSON.stringify({actor,cursor,size}));
  assert.ok(sampled<=size+22,`too many materialized standard rows: ${sampled}`);
 }
 assert.ok(plans.some(p=>/SEARCH.*idx_cf_story_expiry_page.*<expr>>/.test(p)),'expiry uses range search');
 assert.ok(plans.some(p=>/idx_cf_story_expiry_page \(<expr>=\?/.test(p) && /doc_id>\?/.test(p)),'same-time cursor seeks document ID');
 assert.ok(plans.every(p=>!p.includes('TEMP B-TREE')),'ties use index ordering rather than full tie-group sort');
 const page=await request('viewer',null,10);
 assert.ok(page.every(doc=>doc.data.privacy!=='Only me'));
 assert.ok(page.every(doc=>!Object.hasOwn(doc.data,'viewedBy') && !Object.hasOwn(doc.data,'reactions')));
 fail=true;
 const body={parent:'stories',filters:[{field:'expiresAt',op:'>',value:timestamp(0)}],orderBy:[{field:'expiresAt',direction:'ASCENDING'}],limit:3};
 const failed=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer viewer','content-type':'application/json'},body:JSON.stringify(body)}),env);
 assert.equal(failed.status,503);fail=false;
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.blocked',json('[\"viewer\"]')) WHERE path='users/author'").run();
 assert.deepEqual(await request('viewer',null,3),[],'next request sees changed author blocks');
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_remove(document_json,'$.blocked') WHERE path='users/author'").run();
 // More than the bounded exception budget must use the original evaluator.
 for(let i=0;i<70;i++)add(`odd-extra-${i}`,{unknown:i});
 genericScans=0;const overflow=await request('viewer',null,3);assert.ok(genericScans>0);
 assert.deepEqual(overflow,await request('viewer',null,3,true),'exception overflow remains exact');
 sql.exec('DROP INDEX idx_cf_story_expiry_exceptions');
 assert.deepEqual(await request('viewer',null,3),await request('viewer',null,3,true),'older schema keeps generic parity');
});
