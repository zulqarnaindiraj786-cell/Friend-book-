import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';

const migration=name=>readFileSync(new URL(`../migrations/${name}.sql`,import.meta.url),'utf8');

test('Reels video-post pages seek without scanning unrelated posts, preserve ACL and compatibility',async t=>{
  const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
  for(const name of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0095_video_post_page'])sql.exec(migration(name));
  const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(const id of ['viewer','author'])put.run(`users/${id}`,'users','users',id,id,JSON.stringify({uid:id}),1,1);
  const post=(id,mediaType,privacy='Public')=>put.run(`posts/${id}`,'posts','posts',id,'author',JSON.stringify({authorId:'author',privacy,mediaType}),1,1);
  for(let i=0;i<5000;i++)post(`photo${String(i).padStart(4,'0')}`,'photo');
  for(let i=0;i<120;i++)post(`video${String(i).padStart(3,'0')}`,'video',i%7===0?'Only me':'Public');
  let rows=0,plans=[],failSeek=false;
  const db={withSession(){return this;},prepare(q){
    if(failSeek && q.includes('INDEXED BY idx_cf_video_post_page'))throw Error('storage unavailable');
    const stmt=sql.prepare(q);return{bind(...args){return{
      first:async()=>stmt.get(...args)||null,
      all:async()=>{
        const result=stmt.all(...args);
        if(q.includes('INDEXED BY idx_cf_video_post_page')){
          rows+=result.length;
          plans.push(sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(row=>row.detail).join('\n'));
        }
        return {results:result};
      },
    };}};
  }};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  async function query(cursor,limit,reference=false) {
    const filter={field:'mediaType',op:'==',value:'video'};
    const body={parent:'posts',filters:reference?[filter,filter]:[filter],orderBy:[],limit,...(cursor?{startAfter:[cursor]}:{})};
    const response=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer viewer','content-type':'application/json'},body:JSON.stringify(body)}),env);
    assert.equal(response.status,200,await response.clone().text());
    return (await response.json()).documents;
  }
  for(const cursor of [null,'video000','video050','video110','video999'])for(const limit of [1,5,10]) {
    rows=0;
    const optimized=await query(cursor,limit);
    const fetched=rows;
    assert.deepEqual(optimized,await query(cursor,limit,true));
    assert.ok(fetched<=limit+20,`unexpected video candidates ${fetched} for ${limit}`);
  }
  assert.ok(plans.length);
  assert.ok(plans.every(plan=>plan.includes('idx_cf_video_post_page')));
  assert.ok(plans.some(plan=>/doc_id>\?/.test(plan)),'later pages must seek, not restart at the beginning');
  const first=await query(null,5);assert.equal(first.length,5);assert.ok(!first.some(doc=>doc.id==='video000'));
  failSeek=true;
  const failed=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer viewer','content-type':'application/json'},body:JSON.stringify({parent:'posts',filters:[{field:'mediaType',op:'==',value:'video'}],orderBy:[],limit:5})}),env);
  assert.equal(failed.status,503);
  failSeek=false;
  // A fresh request observes profile revocation; no cross-request ACL cache.
  sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/author'").run(JSON.stringify({uid:'author',blocked:['viewer']}));
  assert.deepEqual(await query(null,5),[]);
  sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/author'").run(JSON.stringify({uid:'author'}));
  // Invalid IDs/paths use the legacy evaluator, not an assumed root/ordering.
  put.run('posts/a/b','posts','posts','a/b','author',JSON.stringify({authorId:'author',mediaType:'video',privacy:'Public'}),1,1);
  rows=0;assert.deepEqual(await query(null,10),await query(null,10,true));assert.equal(rows,0);
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='posts/a/b'").run();
  post('é','video');rows=0;
  assert.deepEqual(await query(null,10),await query(null,10,true));
  assert.equal(rows,0,'non-ASCII IDs use the compatible evaluator');
  sql.exec('DROP INDEX idx_cf_video_post_page; DROP INDEX idx_cf_video_post_exceptions');
  assert.deepEqual(await query(null,5),await query(null,5,true),'old schema stays correct');
});
