import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {fixture} from './fixtures/interaction-db.js';
import {canonicalSeed} from './fixtures/canonical.js';
import {createWorker} from '../../worker.js';

test('people picker next pages use the ID index, preserve privacy, and fall back safely',async t=>{
 const f=fixture();t.after(f.close);
 f.sql.exec(readFileSync(new URL('../migrations/0059_people_default_page_indexes.sql',import.meta.url),'utf8'));
 const add=canonicalSeed(f.sql),up=f.sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?');
 add.run('users/viewer','users','viewer','viewer',JSON.stringify({uid:'viewer'}),1);
 for(let i=0;i<1500;i++){
  const id=`p${String(i).padStart(4,'0')}`;
  add.run(`users/${id}`,'users',id,id,JSON.stringify({uid:id,displayName:id,email:'private@example.test',...(i%41===0?{banned:true}:{})}),1);
 }
 const base=f.env.DB;let force=false,scans=0,candidateRows=0,plans=[];
 const db={...base,prepare(q){
  if(force && (q.includes('WITH candidates AS') || q.includes('quota_people_head')))throw Error('no such index: idx_cf_people_default_page');
  const stmt=f.sql.prepare(q);
  const bind=(...args)=>({first:async()=>stmt.get(...args)||null,all:async()=>{
   const rows=stmt.all(...args);
   if(q.includes('quota_root_users_page'))scans+=rows.length;
   if(q.includes('WITH candidates AS')){candidateRows+=rows.length;plans.push(...f.sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(r=>r.detail));}
   return {results:rows};
  },run:async()=>/^\s*(SELECT|WITH)\b/i.test(q)?{results:stmt.all(...args)}:stmt.run(...args)});
  return {...bind(),bind};
 }};
 const worker=createWorker(async token=>token),env={...f.env,DB:db,FIREBASE_PROJECT_ID:'test',PAYMENT_ADMIN_UIDS:'admin'};
 async function query(cursor,actor='viewer',extra={}){
  const body={parent:'users',limit:10,startAfter:cursor,...extra};
  const r=await worker.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:`Bearer ${actor}`,'content-type':'application/json'},body:JSON.stringify(body)}),env);
  assert.equal(r.status,200,await r.clone().text());return (await r.json()).documents;
 }
 async function compare(cursor,actor='viewer'){
  force=false;scans=0;candidateRows=0;const actual=await query(cursor,actor),stats={scans,candidateRows};
  force=true;const expected=await query(cursor,actor);force=false;
  assert.deepEqual(actual,expected);return {actual,...stats};
 }
 let result=await compare(['p0680']);
 assert.equal(result.scans,0);assert.equal(result.candidateRows,11);
 assert.equal(result.actual.length,10);assert.ok(result.actual.every(x=>!x.data.email));
 assert.ok(plans.some(x=>/SEARCH.*idx_cf_people_default_page.*doc_id>/.test(x)),plans.join('\n'));
 // Hidden profiles are removed before page sizing; changing them is visible next request.
 for(let i=691;i<705;i++)up.run(JSON.stringify({uid:`p${String(i).padStart(4,'0')}`,banned:true}),`users/p${String(i).padStart(4,'0')}`);
 result=await compare(['p0690']);assert.equal(result.scans,0);assert.equal(result.actual[0].id,'p0705');assert.ok(result.candidateRows<=28);
 up.run(JSON.stringify({uid:'p0695',displayName:'Unbanned'}),'users/p0695');
 result=await compare(['p0690']);assert.equal(result.actual[0].id,'p0695');
 // The cursor is an ordered boundary, not a required live anchor.
 f.sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='users/p0690'").run();
 await compare(['p0690']);
 add.run('users/admin','users','admin','admin',JSON.stringify({uid:'admin'}),1);
 result=await compare(['p0690'],'admin');assert.equal(result.actual[0].id,'p0691','admin sees banned accounts');
 result=await compare(['zzzz']);assert.equal(result.actual.length,0);assert.equal(result.scans,0);
 // More hidden rows than the bounded refill budget must fall back, not truncate.
 for(let i=800;i<890;i++)up.run(JSON.stringify({banned:true}),`users/p${String(i).padStart(4,'0')}`);
 result=await compare(['p0799']);assert.ok(result.scans>1000);assert.equal(result.actual[0].id,'p0890');
 // Non-ASCII and noncanonical results trigger the complete evaluator.
 add.run('users/p0705é','users','p0705é','x',JSON.stringify({uid:'p0705é'}),1);
 result=await compare(['p0690']);assert.ok(result.scans>1000);
 f.sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='users/p0705é'").run();
 f.sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('users/odd','users','users','p0696','odd','{}',1,1);
 result=await compare(['p0690']);assert.ok(result.scans>1000);
 // Non-ASCII and object cursor input remain on the compatibility path.
 result=await compare(['é']);assert.ok(result.scans>1000);
 result=await compare({id:'deleted'});assert.ok(result.scans>1000);
});

test('short people head returns the complete projected page without duplicate collection reads',async t=>{
 const f=fixture();t.after(f.close);
 f.sql.exec(readFileSync(new URL('../migrations/0059_people_default_page_indexes.sql',import.meta.url),'utf8'));
 const add=canonicalSeed(f.sql);
 add.run('users/viewer','users','viewer','viewer',JSON.stringify({uid:'viewer'}),1);
 for(let i=0;i<45;i++){
  const id=`p${String(i).padStart(3,'0')}`;
  add.run(`users/${id}`,'users',id,id,JSON.stringify({uid:id,email:'secret',...(i%4===0?{banned:true}:{})}),1);
 }
 let force=false,heads=0,refills=0,headRows=0;
 const db={...f.env.DB,prepare(q){
  if(force && (q.includes('quota_people_head') || q.includes('WITH candidates AS')))throw Error('no such index: idx_cf_people_default_page');
  const stmt=f.sql.prepare(q),bind=(...args)=>({first:async()=>stmt.get(...args)||null,all:async()=>{
   const results=stmt.all(...args);
   if(q.includes('quota_people_head')){heads++;headRows+=results.length;}
   if(q.includes('WITH candidates AS'))refills++;
   return {results};
  }});
  return {...bind(),bind};
 }};
 const worker=createWorker(async token=>token),env={...f.env,DB:db,FIREBASE_PROJECT_ID:'test'};
 async function query(limit=50){const r=await worker.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer viewer','content-type':'application/json'},body:JSON.stringify({parent:'users',limit})}),env);assert.equal(r.status,200,await r.clone().text());return (await r.json()).documents;}
 const actual=await query();assert.equal(heads,1);assert.equal(headRows,46);assert.equal(refills,0);
 assert.ok(actual.length<46);assert.ok(actual.every(row=>row.data.email===undefined));
 force=true;assert.deepEqual(actual,await query());force=false;
 heads=0;refills=0;const exact=await query(46);assert.equal(heads,1);assert.equal(refills,0);
 force=true;assert.deepEqual(exact,await query(46));force=false;
 // A full raw head with hidden profiles is not a completeness proof.
 heads=0;refills=0;const insufficient=await query(45);assert.ok(refills>0);
 force=true;assert.deepEqual(insufficient,await query(45));force=false;
 // Own account can still see itself when all other accounts are hidden.
 f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json='{"+'"banned":true'+"}' WHERE doc_id<>'viewer' AND parent_path='users'").run();
 heads=0;headRows=0;refills=0;const onlySelf=await query();assert.deepEqual(onlySelf.map(row=>row.id),['viewer']);assert.equal(refills,0);
 force=true;assert.deepEqual(onlySelf,await query());
});
