import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const migration=name=>readFileSync(new URL(`../migrations/${name}.sql`,import.meta.url),'utf8');

test('Watch/Reels mediaType pages read only matching post candidates and retain ACL/page behavior',async t=>{
  const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
  for(const name of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0102_posts_media_type_index'])sql.exec(migration(name));
  const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(const uid of ['alice','bob'])put.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid}),1,1);
  for(let i=0;i<1200;i++){
    const id=`post${String(i).padStart(4,'0')}`,video=i%60===0;
    put.run(`posts/${id}`,'posts','posts',id,'alice',JSON.stringify({
      authorId:'alice',privacy:'Public',mediaType:video?'video':'photo',createdAt:i
    }),1,1);
  }
  put.run('posts/private-video','posts','posts','private-video','alice',JSON.stringify({
    authorId:'alice',privacy:'Only me',mediaType:'video',createdAt:2000
  }),1,1);

  let indexedRows=0,fullRows=0,plans=[];
  const db={
    withSession(){return this;},
    prepare(query){
      const stmt=sql.prepare(query);
      return {bind(...args){return {
        first:async()=>stmt.get(...args)||null,
        all:async()=>{
          const rows=stmt.all(...args);
          if(query.includes('INDEXED BY idx_cf_posts_media_type')){
            indexedRows+=rows.length;
            plans.push(sql.prepare('EXPLAIN QUERY PLAN '+query).all(...args).map(row=>row.detail).join('\\n'));
          }
          if(query.includes('quota_root_posts'))fullRows+=rows.length;
          return {results:rows};
        },
        run:async()=>stmt.run(...args),
      };}};
    }
  };
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const query=async()=>{
    const response=await app.fetch(new Request('https://local.invalid/v1/firestore/query',{
      method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},
      body:JSON.stringify({parent:'posts',filters:[{field:'mediaType',op:'==',value:'video'}],limit:5})
    }),env);
    assert.equal(response.status,200,await response.clone().text());
    return (await response.json()).documents.map(doc=>doc.id);
  };

  const optimized=await query();
  assert.deepEqual(optimized,['post0000','post0060','post0120','post0180','post0240']);
  assert.equal(indexedRows,21,'only 20 public video candidates plus one private candidate are read');
  assert.equal(fullRows,0,'the 1,200-row posts collection is not materialized');
  assert.ok(plans.some(plan=>/SEARCH .*idx_cf_posts_media_type/.test(plan)),plans.join('\\n'));

  // A rolling old schema still uses the complete evaluator; no partial page or
  // privacy bypass is allowed while the additive index is not yet installed.
  sql.exec('DROP INDEX idx_cf_posts_media_type');
  indexedRows=0;fullRows=0;
  const compatible=await query();
  assert.deepEqual(compatible,optimized);
  assert.equal(indexedRows,0);
  assert.equal(fullRows,1201);
});
