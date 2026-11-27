import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const migration=name=>readFileSync(new URL(`../migrations/${name}`,import.meta.url),'utf8');

test('author-scoped post pages seek safely across ranks, mirrors, exceptions, ACL and old schemas',async()=>{
  const sql=new DatabaseSync(':memory:');
  for(const name of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0029_feed_page_indexes.sql','0044_profile_post_page_indexes.sql','0051_feed_exception_indexes.sql'])sql.exec(migration(name));
  const canonical=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  for(const uid of ['alice','bob','other'])canonical.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid}),1,1);
  for(let i=0;i<220;i++){
    const id=`p${String(i).padStart(3,'0')}`,author=i%5===0?'other':'alice', t=Math.floor((i%110)/3)-20;
    const data={authorId:author,privacy:i%17===0?'Only me':'Public',createdAt:i<110?t:{__type:'timestamp',millis:t}};
    if(i%3!==0)canonical.run(`posts/${id}`,'posts','posts',id,author,JSON.stringify(data),1,1);
    if(i%3!==1)legacy.run(`posts/${id}`,'posts',id,author,JSON.stringify(data),1);
  }
  // Newer legacy copy cannot disclose a canonical post whose privacy or author changed.
  canonical.run('posts/secret','posts','posts','secret','alice',JSON.stringify({authorId:'alice',privacy:'Only me',createdAt:1}),1,1);
  legacy.run('posts/secret','posts','secret','alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:90}),1);
  canonical.run('posts/changed','posts','posts','changed','other',JSON.stringify({authorId:'other',privacy:'Public',createdAt:1}),1,1);
  legacy.run('posts/changed','posts','changed','alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:90}),1);
  legacy.run('posts/wrapped','posts','wrapped','alice',JSON.stringify({fields:{authorId:{stringValue:'alice'},privacy:{stringValue:'Public'},createdAt:{integerValue:'3'}}}),1);
  canonical.run('posts/odd','posts','posts','odd','alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:2.5}}),1,1);
  let plans=[],failSeek=false;
  const db={withSession(){return this;},prepare(q){
    if(failSeek && q.includes('quota_feed_rank') && q.includes('posts_author_page'))throw Error('storage unavailable');
    const stmt=sql.prepare(q);return{bind(...args){return{
    first:async()=>stmt.get(...args)||null,
    all:async()=>{
      if(q.includes('quota_feed_rank'))plans.push(sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(r=>r.detail));
      return {results:stmt.all(...args)};
    },
  };}};}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  async function query(cursor,limit,reference=false){
    const filters=[{field:'authorId',op:'==',value:'alice'}];
    if(reference)filters.push({...filters[0]}); // same predicate forces generic evaluator
    const body={parent:'posts',filters,orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit,...(cursor?{startAfter:cursor}:{})};
    const r=await app.fetch(new Request('https://local.invalid/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify(body)}),env);
    assert.equal(r.status,200,await r.clone().text());
    // The bounded path can expose its pre-existing false shadow marker; the
    // generic evaluator does not. Compare all actual document fields instead.
    return (await r.json()).documents.map(({shadowed,...document})=>document);
  }
  const cursors=[undefined,[{__type:'timestamp',millis:100},'z'],[{__type:'timestamp',millis:8},'p200'],[{__type:'timestamp',millis:0},'p130'],[{__type:'timestamp',millis:-20},'p110'],[100,'z'],[8,'p100'],[0,'p060'],[-20,'p003']];
  for(const cursor of cursors)for(const limit of [1,3,12,100]){
    assert.deepEqual(await query(cursor,limit),await query(cursor,limit,true),JSON.stringify({cursor,limit}));
  }
  const all=(await query(null,100)).map(doc=>doc.id);assert.ok(!all.includes('secret'));assert.ok(!all.includes('changed'));
  assert.ok(plans.length>0);
  for(const plan of plans){
    const p=plan.join('\n');
    assert.match(p,/idx_cf_(?:legacy_)?posts_author_page/);
    assert.match(p,/<expr>=\?/,'author and rank seek');
    assert.match(p,/<expr><\?/,'time seek avoids traversing newer posts');
  }
  failSeek=true;
  const failed=await app.fetch(new Request('https://local.invalid/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify({parent:'posts',filters:[{field:'authorId',op:'==',value:'alice'}],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:12,startAfter:cursors[2]})}),env);
  assert.equal(failed.status,503,'storage errors must fail closed, not produce empty pages');
  failSeek=false;
  // Old schema compatibility: keep bounded pre-existing path if either author index is absent.
  sql.exec('DROP INDEX idx_cf_legacy_posts_author_page');plans=[];
  for(const cursor of [cursors[2],cursors[5]])assert.deepEqual(await query(cursor,12),await query(cursor,12,true));
  sql.exec('DROP INDEX idx_cf_posts_author_page');
  assert.deepEqual(await query(cursors[3],12),await query(cursors[3],12,true));
  sql.close();
});
