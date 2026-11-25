import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {fixture} from './fixtures/interaction-db.js';
import {canonicalSeed} from './fixtures/canonical.js';
import {legacyCommentCount,legacyCommentCountsWithCandidatesStatement} from '../comment-api.js';
import {batchEngagementReads} from '../interactions.js';
const migration=n=>readFileSync(new URL(`../migrations/${n}.sql`,import.meta.url),'utf8');

function setup(t){
 const f=fixture();t.after(f.close);f.sql.exec(migration('0032_legacy_comment_lookup'));f.sql.exec(migration('0087_canonical_comment_page_indexes'));f.sql.exec(migration('0101_legacy_comment_epoch'));
 return f;
}

test('candidate-aware counts exactly match the legacy evaluator for all route and privacy forms',async t=>{
 const f=setup(t),add=canonicalSeed(f.sql);
 const row=(id,data)=>add.run(`comments/${id}`,'comments',id,'',JSON.stringify(data),1);
 row('one',{postId:'p0',authorId:'alice'});row('two',{post_id:'p0',uid:'bob'});
 row('blocked',{resource:'posts/p0',userId:'blocked'});row('deleted',{postId:'p0',authorId:'alice',deleted:true});
 row('shadow',{postId:'p1',authorId:'alice'});row('wrapped',{fields:{postId:{stringValue:'p1'},authorId:{stringValue:'alice'}}});
 row('same',{postId:'p2',post_id:'p2',resource:'posts/p2',authorId:'alice'});
 row('array',{postId:['str'],authorId:'alice'});row('anonymous',{postId:'empty_count',authorId:''});
 f.sql.prepare('INSERT INTO friendbook_cf_comments VALUES (?,?,?,?,?,?)').run('posts/p1','shadow','alice','text',null,1);
 const resources=Array.from({length:20},(_,i)=>`posts/p${i}`);resources.push('posts/empty_count','posts/["str"]','reels/p0');
 let plan='';const planDb={prepare(query){return {bind(...args){plan=f.sql.prepare('EXPLAIN QUERY PLAN '+query).all(...args).map(row=>row.detail).join('\n');return f.env.DB.prepare(query).bind(...args);}};}};
 await legacyCommentCountsWithCandidatesStatement(planDb,['posts/p0'],[]).all();
 assert.match(plan,/MATERIALIZE quota_comment_candidates_0/);
 for(const index of ['idx_cf_canonical_comment_page_post','idx_cf_canonical_comment_page_post_legacy','idx_cf_canonical_comment_page_resource'])
  assert.match(plan,new RegExp('SEARCH a USING INDEX '+index),'each historical route must seek its matching partial index');
 assert.doesNotMatch(plan,/SCAN a USING INDEX idx_friendbook_cf_firestore_parent/,'candidate lookup must not scan the comments partition');
 for(const blocked of [[],['blocked','blocked']])for(let offset=0;offset<resources.length;offset+=10){
  const page=resources.slice(offset,offset+10),expected=await Promise.all(page.map(r=>legacyCommentCount(f.env.DB,r,blocked)));
  const rows=(await legacyCommentCountsWithCandidatesStatement(f.env.DB,page,blocked).all()).results;
  assert.deepEqual(rows.map(r=>r.n),expected);
  assert.equal(rows.find(r=>page[r.slot]==='posts/empty_count')?.has_candidates,offset===20?1:undefined);
 }
});

test('fresh epoch validates isolate-local negative hints and keeps exact blocked/shadow semantics',async t=>{
 const f=setup(t),add=canonicalSeed(f.sql);let candidateQueries=0,legacyQueries=0,epochReads=0,batches=0,inject=null,epochFail=false;
 const base=f.env.DB,db={...base,withSession(mode){assert.equal(mode,'first-primary');return this;},prepare(q){
  if(q.includes('FROM friendbook_cf_legacy_comment_epoch WHERE id=1')){epochReads++;if(epochFail)throw Error('storage unavailable');}
  if(q.includes('quota_comment_candidates_'))candidateQueries++;
  else if(q.includes('COUNT(*) AS n FROM friendbook_cf_firestore_documents'))legacyQueries++;
  return base.prepare(q);
 },async batch(statements){batches++;if(inject){const callback=inject;inject=null;callback();}return base.batch(statements);}};
 const scope=db,res=['posts/empty1','posts/empty2','posts/nonempty'];
 add.run('comments/x','comments','x','a',JSON.stringify({postId:'nonempty',authorId:'blocked'}),1);
 const first=await batchEngagementReads(db,res,'user1',['blocked'],scope);
 assert.deepEqual(res.map(r=>first.get(r).count),[0,0,0]);assert.equal(candidateQueries,1);assert.equal(batches,1);
 const second=await batchEngagementReads(db,res,'user1',[],scope);
 assert.deepEqual(res.map(r=>second.get(r).count),[0,0,1],'blocked-only zero is never memoized');
 assert.equal(candidateQueries,2);assert.equal(batches,2);
 const onlyEmpty=res.slice(0,2),before=candidateQueries;
 assert.deepEqual(onlyEmpty.map(r=>(second.get(r)).count),[0,0]);
 const cached=await batchEngagementReads(db,onlyEmpty,'user1',[],scope);
 assert.deepEqual(onlyEmpty.map(r=>cached.get(r).count),[0,0]);assert.equal(candidateQueries,before,'no legacy candidate query for valid hints');
 assert.ok(epochReads>=4,'epoch is checked before and after a hinted batch');
 // Another isolate writes a formerly absent route; next page must see it.
 add.run('comments/new','comments','new','a',JSON.stringify({postId:'empty1',authorId:'alice'}),1);
 const changed=await batchEngagementReads(db,onlyEmpty,'user1',[],scope);
 assert.deepEqual(onlyEmpty.map(r=>changed.get(r).count),[1,0]);
 f.sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='comments/new'").run();
 const deleted=await batchEngagementReads(db,onlyEmpty,'user1',[],scope);
 assert.deepEqual(onlyEmpty.map(r=>deleted.get(r).count),[0,0]);
 // Root write between the first epoch check and batch must trigger a complete
 // retry, never return the stale hinted zero from a mixed snapshot.
 inject=()=>add.run('comments/racing','comments','racing','a',JSON.stringify({postId:'empty2',authorId:'alice'}),1);
 const race=await batchEngagementReads(db,onlyEmpty,'user1',[],scope);
 assert.deepEqual(onlyEmpty.map(r=>race.get(r).count),[0,1]);assert.ok(legacyQueries>0,'epoch race retried the old grouped count');
 // An epoch read failure never authorizes a previously cached negative.
 f.sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='comments/racing'").run();
 await batchEngagementReads(db,onlyEmpty,'user1',[],scope);
 add.run('comments/unavailable','comments','unavailable','a',JSON.stringify({resource:'posts/empty1',authorId:'alice'}),1);
 epochFail=true;
 const unavailable=await batchEngagementReads(db,onlyEmpty,'user1',[],scope);
 assert.deepEqual(onlyEmpty.map(r=>unavailable.get(r).count),[1,0]);
});

test('old schema, rollback and epoch rollover preserve invalidation',async t=>{
 const f=setup(t),scope=f.env.DB;const res=['posts/a','posts/b'];
 const original=f.sql.prepare('SELECT generation,revision FROM friendbook_cf_legacy_comment_epoch WHERE id=1').get();
 f.sql.exec('BEGIN');canonicalSeed(f.sql).run('comments/a','comments','a','a',JSON.stringify({postId:'a'}),1);f.sql.exec('ROLLBACK');
 assert.deepEqual(f.sql.prepare('SELECT generation,revision FROM friendbook_cf_legacy_comment_epoch WHERE id=1').get(),original);
 f.sql.prepare('UPDATE friendbook_cf_legacy_comment_epoch SET revision=9007199254740990 WHERE id=1').run();
 canonicalSeed(f.sql).run('comments/a','comments','a','a',JSON.stringify({postId:'a',authorId:'a'}),1);
 const rollover=f.sql.prepare('SELECT generation,revision FROM friendbook_cf_legacy_comment_epoch WHERE id=1').get();
 assert.equal(rollover.revision,0);assert.notEqual(rollover.generation,original.generation);
 const withEpoch=await batchEngagementReads(f.env.DB,res,'user1',[],scope);
 f.sql.exec('DROP TRIGGER cf_legacy_comment_epoch_insert; DROP TRIGGER cf_legacy_comment_epoch_update; DROP TRIGGER cf_legacy_comment_epoch_delete; DROP TABLE friendbook_cf_legacy_comment_epoch');
 const oldSchema=await batchEngagementReads(f.env.DB,res,'user1',[],scope);
 assert.deepEqual(res.map(r=>oldSchema.get(r).count),res.map(r=>withEpoch.get(r).count));
});

test('negative hints never cross database binding scopes, even with equal epochs',async t=>{
 const a=setup(t),b=setup(t),res=['posts/a','posts/b'];
 const stamp='a'.repeat(32);
 a.sql.prepare('UPDATE friendbook_cf_legacy_comment_epoch SET generation=?,revision=7 WHERE id=1').run(stamp);
 canonicalSeed(b.sql).run('comments/x','comments','x','author',JSON.stringify({postId:'a',authorId:'author'}),1);
 b.sql.prepare('UPDATE friendbook_cf_legacy_comment_epoch SET generation=?,revision=7 WHERE id=1').run(stamp);
 const first=await batchEngagementReads(a.env.DB,res,'user1',[],a.env.DB);
 assert.deepEqual(res.map(r=>first.get(r).count),[0,0]);
 const other=await batchEngagementReads(b.env.DB,res,'user1',[],b.env.DB);
 assert.deepEqual(res.map(r=>other.get(r).count),[1,0]);
});

test('legacy epoch changes transactionally on insert, edit, move and delete only',async t=>{
 const f=setup(t),add=canonicalSeed(f.sql);
 const epoch=()=>f.sql.prepare('SELECT revision FROM friendbook_cf_legacy_comment_epoch WHERE id=1').get().revision;
 add.run('posts/p','posts','p','a',JSON.stringify({authorId:'a'}),1);assert.equal(epoch(),0);
 add.run('comments/x','comments','x','a',JSON.stringify({postId:'p',authorId:'a'}),1);assert.equal(epoch(),1);
 f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='comments/x'").run(JSON.stringify({post_id:'q',authorId:'a'}));assert.equal(epoch(),2);
 f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET parent_path='elsewhere' WHERE path='comments/x'").run();assert.equal(epoch(),3);
 f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET parent_path='comments' WHERE path='comments/x'").run();assert.equal(epoch(),4);
 f.sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='comments/x'").run();assert.equal(epoch(),5);
 f.sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='posts/p'").run();assert.equal(epoch(),5);
});

test('actual authorized feed query reuses absent-route hints and observes later historical inserts',async t=>{
 const {createWorker}=await import('../../worker.js');
 const f=setup(t);
 for(const n of ['0029_feed_page_indexes','0051_feed_exception_indexes','0099_feed_high_rank_exceptions','0100_interaction_state_cover'])f.sql.exec(migration(n));
 const add=canonicalSeed(f.sql);
 for(const uid of ['user1','author'])add.run(`users/${uid}`,'users',uid,uid,JSON.stringify({uid}),1);
 for(const id of ['p1','p2']){
  add.run(`posts/${id}`,'posts',id,'author',JSON.stringify({authorId:'author',privacy:'Public',createdAt:{__type:'timestamp',millis:5}}),1);
  f.sql.prepare("INSERT INTO friendbook_cf_interaction_resources VALUES (?, 'verified') ON CONFLICT(resource) DO UPDATE SET state='verified'").run(`posts/${id}`);
 }
 let candidateQueries=0,primarySessions=0;
 const base=f.env.DB,db={...base,withSession(mode){assert.equal(mode,'first-primary');primarySessions++;return this;},prepare(q){if(q.includes('quota_comment_candidates_'))candidateQueries++;return base.prepare(q);}};
 const app=createWorker(async token=>token),env={...f.env,DB:db,FIREBASE_PROJECT_ID:'test'};
 const body={parent:'posts',filters:[],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:2,feedEngagement:true};
 async function request(){const response=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer user1','content-type':'application/json'},body:JSON.stringify(body)}),env);assert.equal(response.status,200,await response.clone().text());return (await response.json()).documents;}
 const first=await request();assert.equal(first.length,2);assert.equal(candidateQueries,1);
 assert.deepEqual(await request(),first);assert.equal(candidateQueries,1,'unchanged second feed skips all three legacy route probes per post');
 add.run('comments/new','comments','new','author',JSON.stringify({postId:'p1',authorId:'author',content:'hello',createdAt:10}),1);
 const updated=await request();assert.equal(candidateQueries,2);assert.equal(updated.find(d=>d.id==='p1').engagement.comments,1);
 assert.ok(primarySessions>=3,'epoch validation uses primary-first sessions');
});

test('twenty-resource pages split legacy SQL into D1-safe groups in one batch',async t=>{
 const f=setup(t),base=f.env.DB,add=canonicalSeed(f.sql),resources=Array.from({length:20},(_,i)=>`posts/p${i}`);
 add.run('comments/x','comments','x','a',JSON.stringify({postId:'p19',authorId:'alice'}),1);
 let grouped=0,batches=0,epochError=false;
 const db={...base,withSession(){return this;},prepare(q){
  if(epochError && q.includes('FROM friendbook_cf_legacy_comment_epoch WHERE id=1'))throw Error('unavailable');
  if(q.includes('AS slot')){grouped++;assert.ok((q.match(/AS slot/g)||[]).length<=5,'D1 compound SELECT limit');}
  return base.prepare(q);
 },async batch(statements){batches++;return base.batch(statements);}};
 let result=await batchEngagementReads(db,resources,'user1',[],db);
 assert.deepEqual(resources.map(r=>result.get(r).count),resources.map((_,i)=>i===19?1:0));
 assert.equal(grouped,4);assert.equal(batches,1);
 result=await batchEngagementReads(db,resources,'user1',[],db);
 assert.equal(result.get('posts/p19').count,1);assert.equal(grouped,5);assert.equal(batches,2);
 epochError=true;result=await batchEngagementReads(db,resources,'user1',[],db);
 assert.equal(result.get('posts/p19').count,1);assert.equal(grouped,9);assert.equal(batches,3);
});
