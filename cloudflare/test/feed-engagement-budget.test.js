import test from 'node:test';
import assert from 'node:assert/strict';
import {createWorker} from '../../worker.js';
import {fixture} from './fixtures/interaction-db.js';

test('feed engagement enrichment is off by default to protect the read target',async t=>{
  const f=fixture();t.after(f.close);
  const put=f.sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(const id of ['viewer','author'])put.run(`users/${id}`,'users','users',id,id,JSON.stringify({uid:id}),1,1);
  put.run('posts/p1','posts','posts','p1','author',JSON.stringify({authorId:'author',privacy:'Public',createdAt:1,likes:4}),1,1);
  let engagementReads=0;
  const original=f.env.DB;
  const db={...original,prepare(query){
    if(query.includes('friendbook_cf_interaction_resources') || query.includes('friendbook_cf_comments') || query.includes('friendbook_cf_reactions'))engagementReads++;
    return original.prepare(query);
  }};
  const worker=createWorker(async token=>token);
  const response=await worker.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer viewer','content-type':'application/json'},body:JSON.stringify({
    parent:'posts',filters:[],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:10,feedEngagement:true,
  })}),{...f.env,DB:db,FIREBASE_PROJECT_ID:'test'});
  assert.equal(response.status,200,await response.clone().text());
  const docs=(await response.json()).documents;
  assert.equal(docs.length,1);
  assert.equal(docs[0].engagement,undefined);
  assert.equal(docs[0].data.likes,4);
  assert.equal(engagementReads,0);
});
