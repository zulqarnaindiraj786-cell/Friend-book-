import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';
const migration=n=>readFileSync(new URL(`../migrations/${n}.sql`,import.meta.url),'utf8');

test('author story pages seek ID and apply fresh ACL before the limit',async t=>{
 const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
 for(const n of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0018_social_guards','0038_document_and_reply_indexes','0098_story_author_page'])sql.exec(migration(n));
 const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for(const id of ['viewer','author','other'])put.run(`users/${id}`,'users','users',id,id,JSON.stringify({uid:id}),1,1);
 const add=(id,author,privacy='Public',path=`stories/${id}`)=>put.run(path,'stories','stories',id,author,JSON.stringify({authorId:author,privacy,viewedBy:['viewer'],reactions:{viewer:'love'}}),1,1);
 for(let i=0;i<5000;i++)add(`o${String(i).padStart(5,'0')}`,'other');
 for(let i=0;i<500;i++)add(`s${String(i).padStart(4,'0')}`,'author',i%7===0?'Only me':'Public');
 // An unusual ID belonging to a different author must not disable this path.
 add('字','other');
 let fetched=0,plans=[],failed=false,generic=0;
 const db={withSession(){return this;},prepare(q){
  if(failed && q.includes('INDEXED BY idx_cf_story_author_page'))throw Error('storage unavailable');
  if(q.includes("json_extract(document_json,'$.authorId')=?") && !q.includes('INDEXED BY'))generic++;
  const stmt=sql.prepare(q);return {bind(...args){return{
   first:async()=>stmt.get(...args)||null,
   all:async()=>{
    const results=stmt.all(...args);
    if(q.includes('INDEXED BY idx_cf_story_author_page')){
     fetched+=results.length;plans.push(sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(r=>r.detail).join('\n'));
    }
    if(q.includes('INDEXED BY idx_cf_story_author_exceptions'))plans.push(sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(r=>r.detail).join('\n'));
    return {results};
   }
  };}};
 }};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 async function request(actor,cursor,size,reference=false,author='author'){
  const f={field:'authorId',op:'==',value:author};
  const body={parent:'stories',filters:reference?[f,f]:[f],orderBy:[],limit:size,...(cursor?{startAfter:[cursor]}:{})};
  const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:`Bearer ${actor}`,'content-type':'application/json'},body:JSON.stringify(body)}),env);
  assert.equal(r.status,200,await r.clone().text());return (await r.json()).documents;
 }
 for(const actor of ['viewer','author'])for(const cursor of [null,'s0000','s0084','s0480','s9999'])for(const size of [1,3,10]){
  fetched=0;const actual=await request(actor,cursor,size);const used=fetched;
  assert.deepEqual(actual,await request(actor,cursor,size,true),JSON.stringify({actor,cursor,size}));
  assert.ok(used<=size+10,`unnecessarily materialized ${used} for ${size}`);
 }
 assert.ok(plans.some(p=>/SEARCH.*idx_cf_story_author_page.*<expr>=\?.*doc_id>\?/.test(p)),'later pages seek author and ID');
 assert.ok(plans.some(p=>/SEARCH.*idx_cf_story_author_exceptions.*<expr>=\?/.test(p)),'exceptions are scoped to author');
 assert.ok(plans.every(p=>!p.includes('TEMP B-TREE')),'no ID-order sort');
 // SQLite renders JSON arrays/objects as text. An author whose string UID
 // equals that rendering must not get the non-string author's stories.
 put.run('stories/array_author','stories','stories','array_author','other',JSON.stringify({authorId:['same'],privacy:'Public'}),1,1);
 put.run('users/["same"]','users','users','["same"]','["same"]',JSON.stringify({uid:'["same"]'}),1,1);
 add('string_author','["same"]');
 const jsonAuthor=await request('["same"]',null,10,false,'["same"]');
 assert.deepEqual(jsonAuthor,await request('["same"]',null,10,true,'["same"]'));
 assert.deepEqual(jsonAuthor.map(d=>d.id),['string_author']);
 const viewer=await request('viewer',null,10),owner=await request('author',null,10);
 assert.ok(viewer.every(d=>d.data.privacy!=='Only me' && !Object.hasOwn(d.data,'viewedBy') && !Object.hasOwn(d.data,'reactions')));
 assert.ok(owner.some(d=>Object.hasOwn(d.data,'viewedBy')),'owner projection retained');
 failed=true;
 const body={parent:'stories',filters:[{field:'authorId',op:'==',value:'author'}],orderBy:[],limit:10};
 const res=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer viewer','content-type':'application/json'},body:JSON.stringify(body)}),env);
 assert.equal(res.status,503);failed=false;
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/author'").run(JSON.stringify({uid:'author',blocked:['viewer']}));
 assert.deepEqual(await request('viewer',null,10),[],'revocation is checked on the next request');
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/author'").run(JSON.stringify({uid:'author'}));
 for(const odd of ['é','nul\0id','bad/slash','x'.repeat(513)]){
  add(odd,'author');fetched=0;generic=0;
  assert.deepEqual(await request('viewer',null,10),await request('viewer',null,10,true));
  assert.equal(fetched,0,`unexpected seek for ${odd}`);assert.ok(generic>0);
  sql.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').run(`stories/${odd}`);
 }
 sql.exec('DROP INDEX idx_cf_story_author_page; DROP INDEX idx_cf_story_author_exceptions');
 assert.deepEqual(await request('viewer',null,10),await request('viewer',null,10,true),'old schema remains correct');
});
