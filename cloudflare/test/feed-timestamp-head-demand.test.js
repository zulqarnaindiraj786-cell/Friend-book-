import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';
const migration=n=>readFileSync(new URL(`../migrations/${n}.sql`,import.meta.url),'utf8');

test('timestamp Home heads skip lower-ranked exceptions only when the visible page is provably full',async t=>{
 const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
 for(const n of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0029_feed_page_indexes','0051_feed_exception_indexes','0099_feed_high_rank_exceptions'])sql.exec(migration(n));
 const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for(const id of ['viewer','author'])put.run(`users/${id}`,'users','users',id,id,JSON.stringify({uid:id}),1,1);
 const add=(id,time,privacy='Public',path=`posts/${id}`)=>put.run(path,'posts','posts',id,'author',JSON.stringify({authorId:'author',privacy,createdAt:time}),1,1);
 for(let i=0;i<400;i++)add(`p${String(i).padStart(4,'0')}`,{__type:'timestamp',millis:Math.floor(i/4)},i%11===0?'Only me':'Public');
 for(let i=0;i<40;i++)add(`text${i}`,'2020-01-01');
 for(let i=0;i<15;i++)add(`null${i}`,null);
 add('oldnum',50);
 let fast=0,exceptions=0,plans=[],fail=false,inject=false;
 const db={withSession(){return this;},prepare(q){
  if(fail && q.includes('idx_cf_feed_high_rank_exceptions'))throw Error('storage unavailable');
  const stmt=sql.prepare(q);return {bind(...args){return{
   first:async()=>stmt.get(...args)||null,
   all:async()=>{
    const results=stmt.all(...args);
    if(q.includes('idx_cf_feed_high_rank_exceptions')){
     fast+=results.length;plans.push(sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(r=>r.detail).join('\n'));
     if(inject){inject=false;add('racing-high',{new:'map'});}
    }
    if(q.includes('INDEXED BY idx_cf_feed_exceptions'))exceptions+=results.length;
    return {results};
   },
  };}};
 }};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 async function request(actor,cursor,size,reference=false){
  const body={parent:'posts',filters:reference?[{field:'privacy',op:'!=',value:'Never'}]:[],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:size,...(cursor?{startAfter:cursor}:{})};
  const res=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:`Bearer ${actor}`,'content-type':'application/json'},body:JSON.stringify(body)}),env);
  assert.equal(res.status,200,await res.clone().text());return (await res.json()).documents;
 }
 const ts=n=>({__type:'timestamp',millis:n});
 for(const actor of ['author','viewer'])for(const cursor of [null,[ts(99),'p0397'],[ts(80),'p0321'],[ts(15),'p0061']])for(const size of [1,3,10]){
  fast=0;exceptions=0;const actual=await request(actor,cursor,size);const examined=fast,oldExceptions=exceptions;
  assert.deepEqual(actual,await request(actor,cursor,size,true),JSON.stringify({actor,cursor,size}));
  assert.ok(examined<=size+10,`too many materialized timestamp candidates ${examined}`);
  assert.equal(oldExceptions,0,'no lower-ranked exceptions loaded for a full timestamp page');
 }
 assert.ok(plans.some(p=>/idx_cf_posts_feed_page.*<expr>=\?.*<expr>=\?.*doc_id<\?/.test(p)),'same-timestamp cursor uses ID seek');
 assert.ok(plans.some(p=>/idx_cf_posts_feed_page.*<expr>=\?.*<expr>>\?/.test(p)),'timestamp range skips the null-time lower-rank bucket');
 assert.ok(plans.every(p=>p.includes('idx_cf_feed_high_rank_exceptions')),'every candidate SELECT checks high-rank exceptions');
 assert.ok(plans.every(p=>!p.includes('TEMP B-TREE')),'indexed ordering without a tie-group sort');
 // A competing higher-rank write between ACL refill batches must prevent a
 // mixed-snapshot fast result; the next SELECT's in-statement guard sees it.
 inject=true;exceptions=0;
 assert.deepEqual(await request('viewer',null,10),await request('viewer',null,10,true));
 assert.ok(exceptions>0,'concurrent high-rank insertion forces the complete merge');
 sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='posts/racing-high'").run();
 // Fewer than ten remaining timestamps cannot skip strings/numbers/nulls.
 fast=0;exceptions=0;
 assert.deepEqual(await request('viewer',[ts(0),'p0003'],10),await request('viewer',[ts(0),'p0003'],10,true));
 assert.ok(exceptions>0,'partially filled timestamp page falls back to the complete exception merge');
 // A competing exceptional timestamp/array/map invalidates the speculative head.
 for(const [id,value] of [['weird-fraction',ts(2.5)],['weird-object',{x:'secret'}],['weird-array',[1]],['字',ts(25)],['nul\0id',ts(33)]]){
  add(id,value);fast=0;exceptions=0;
  assert.deepEqual(await request('viewer',null,10),await request('viewer',null,10,true));
  assert.equal(fast,0,'exception guard must prevent speculative candidates');assert.ok(exceptions>0);
  sql.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').run(`posts/${id}`);
 }
 fail=true;
 const body={parent:'posts',filters:[],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:10};
 const failed=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer viewer','content-type':'application/json'},body:JSON.stringify(body)}),env);
 assert.equal(failed.status,503);fail=false;
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/author'").run(JSON.stringify({uid:'author',blocked:['viewer']}));
 assert.deepEqual(await request('viewer',null,10),[],'fresh author blocks remain enforced');
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/author'").run(JSON.stringify({uid:'author'}));
 // The SQL feed rank expression also buckets string/null dates as rank 4;
 // they are lower-rank in JS. Never treat them as timestamp candidates.
 sql.exec("DELETE FROM friendbook_cf_firestore_documents WHERE parent_path='posts' AND doc_id GLOB 'p[0-9]*' AND doc_id NOT IN ('p0395','p0396','p0397','p0398','p0399')");
 fast=0;exceptions=0;
 assert.deepEqual(await request('viewer',null,10),await request('viewer',null,10,true));
 assert.ok(exceptions>0,'a short timestamp head falls back before returning lower-ranked strings');
 sql.exec('DROP INDEX idx_cf_feed_high_rank_exceptions');
 assert.deepEqual(await request('viewer',null,10),await request('viewer',null,10,true),'older schema uses complete evaluator');
});

test('timestamp Home head still bundles authorized per-viewer engagement',async t=>{
 const {fixture}=await import('./fixtures/interaction-db.js');const f=fixture();t.after(f.close);
 for(const n of ['0029_feed_page_indexes','0051_feed_exception_indexes','0099_feed_high_rank_exceptions'])f.sql.exec(migration(n));
 const put=f.sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for(const id of ['user1','author'])put.run(`users/${id}`,'users','users',id,id,JSON.stringify({uid:id}),1,1);
 put.run('posts/p1','posts','posts','p1','author',JSON.stringify({authorId:'author',privacy:'Public',createdAt:{__type:'timestamp',millis:10}}),1,1);
 f.sql.prepare('INSERT INTO friendbook_cf_reactions VALUES (?,?,?,?)').run('posts/p1','user1','love',1);
 f.sql.prepare('INSERT INTO friendbook_cf_comments(resource,id,uid,text,created_at) VALUES (?,?,?,?,?)').run('posts/p1','c1','author','hello',1);
 const app=createWorker(async token=>token),body={parent:'posts',filters:[],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:1,feedEngagement:true};
 const response=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer user1','content-type':'application/json'},body:JSON.stringify(body)}),{...f.env,FIREBASE_PROJECT_ID:'test',D1_FEED_ENGAGEMENT_ENABLED:'true'});
 assert.equal(response.status,200,await response.clone().text());const docs=(await response.json()).documents;
 assert.equal(docs.length,1);assert.deepEqual(docs[0].engagement.types,[{type:'love',count:1,mine:1}]);
 assert.equal(docs[0].engagement.comments,1);assert.equal(docs[0].engagement.preview[0].text,'hello');
});
