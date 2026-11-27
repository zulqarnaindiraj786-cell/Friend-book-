import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';
const migration=name=>readFileSync(new URL(`../migrations/${name}.sql`,import.meta.url),'utf8');

test('group feed and moderation pages seek by group with fresh ACL and evaluator parity',async t=>{
 const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
 for(const n of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0018_social_guards','0020_trusted_groups','0038_document_and_reply_indexes','0096_group_post_page'])sql.exec(migration(n));
 const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for(const id of ['author','member','outsider'])put.run(`users/${id}`,'users','users',id,id,JSON.stringify({uid:id}),1,1);
 const group={adminId:'author',privacy:'private',members:['author','member'],bannedMemberIds:[]};
 put.run('groups/room','groups','groups','room','author',JSON.stringify(group),1,1);
 sql.prepare('INSERT INTO friendbook_cf_trusted_groups(group_path,owner_uid) VALUES (?,?)').run('groups/room','author');
 const post=(id,groupId,data)=>put.run(`posts/${id}`,'posts','posts',id,'author',JSON.stringify({authorId:'author',privacy:'Public',groupId,...data}),1,1);
 for(let i=0;i<5000;i++)post(`other${i}`,'other',{createdAt:i});
 for(let i=0;i<800;i++)post(`p${String(i).padStart(4,'0')}`,'room',{
  createdAt:i%2===0?Math.floor(i/4):{__type:'timestamp',millis:Math.floor(i/4)},
  groupApprovalStatus:i%37===0?'pending':'approved',...(i%31===0?{privacy:'Only me'}:{})
 });
 post('odd','room',{createdAt:{__type:'timestamp',millis:2.5},groupApprovalStatus:'pending'});
 let plans=[],hits=0,fail=false;
 const db={withSession(){return this;},prepare(query){
  if(fail && query.includes('idx_cf_group_posts_page'))throw Error('storage unavailable');
  const stmt=sql.prepare(query);return {bind(...args){return{
   first:async()=>stmt.get(...args)||null,
   all:async()=>{
    const result=stmt.all(...args);
    if(query.includes('INDEXED BY idx_cf_group_posts_page')||query.includes('INDEXED BY idx_cf_group_pending_page')){
     hits+=result.length;plans.push(sql.prepare('EXPLAIN QUERY PLAN '+query).all(...args).map(row=>row.detail).join('\n'));
    }
    return {results:result};
   },
  };}};
 }};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 async function query(actor,cursor,limit,pending=false,reference=false){
  const filter={field:'groupId',op:'==',value:'room'},filters=[filter];
  if(pending)filters.push({field:'groupApprovalStatus',op:'==',value:'pending'});
  if(reference)filters.push(filter); // same predicate forces generic evaluator
  const body={parent:'posts',filters,orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit,...(cursor?{startAfter:cursor}:{})};
  const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:`Bearer ${actor}`,'content-type':'application/json'},body:JSON.stringify(body)}),env);
  assert.equal(r.status,200,await r.clone().text());
  return (await r.json()).documents.map(({shadowed,...doc})=>doc);
 }
 const cursors=[null,[{__type:'timestamp',millis:150},'p0601'],[{__type:'timestamp',millis:0},'p0001'],[150,'p0600'],[0,'p0000']];
 for(const actor of ['author','member'])for(const pending of [false,true])for(const cursor of cursors)for(const limit of [1,5,10]){
  hits=0;const actual=await query(actor,cursor,limit,pending);const read=hits;
  assert.deepEqual(actual,await query(actor,cursor,limit,pending,true),JSON.stringify({actor,pending,cursor,limit}));
  if(actor==='author')assert.ok(read<=limit+40,`unnecessarily large candidate page: ${read}`);
 }
 assert.ok(plans.some(p=>p.includes('idx_cf_group_posts_page')));
 assert.ok(plans.some(p=>p.includes('idx_cf_group_pending_page')));
 for(const p of plans)assert.match(p,/SEARCH a USING INDEX idx_cf_group_(?:posts|pending)_page \(<expr>=\?/,
  'group key must constrain each index branch');
 assert.ok(plans.some(p=>p.includes('idx_cf_group_posts_page') && /<expr><\?/.test(p)),'later pages seek the time key');
 assert.deepEqual(await query('outsider',null,10),[],'private group is not disclosed');
 fail=true;
 const body={parent:'posts',filters:[{field:'groupId',op:'==',value:'room'}],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:10};
 const unavailable=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer member','content-type':'application/json'},body:JSON.stringify(body)}),env);
 assert.equal(unavailable.status,503);fail=false;
 group.members=['author'];sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='groups/room'").run(JSON.stringify(group));
 assert.deepEqual(await query('member',null,10),[],'next request sees revoked membership');
 group.members=['author','member'];sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='groups/room'").run(JSON.stringify(group));
 sql.exec('DROP INDEX idx_cf_group_posts_page; DROP INDEX idx_cf_group_pending_page; DROP INDEX idx_cf_group_posts_exceptions');
 assert.deepEqual(await query('member',null,10),await query('member',null,10,false,true),'rolling old schema remains correct');
});

test('bounded group pages retain bundled per-viewer engagement',async t=>{
 const {fixture}=await import('./fixtures/interaction-db.js');
 const f=fixture();t.after(f.close);
 for(const n of ['0020_trusted_groups','0096_group_post_page'])f.sql.exec(migration(n));
 const put=f.sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for(const id of ['user1','author'])put.run(`users/${id}`,'users','users',id,id,JSON.stringify({uid:id}),1,1);
 put.run('groups/room','groups','groups','room','author',JSON.stringify({adminId:'author',privacy:'private',members:['user1','author']}),1,1);
 f.sql.prepare('INSERT INTO friendbook_cf_trusted_groups VALUES (?,?)').run('groups/room','author');
 for(const id of ['p1','p3']){
  put.run(`posts/${id}`,'posts','posts',id,'author',JSON.stringify({authorId:'author',privacy:'Public',groupId:'room',createdAt:1,groupApprovalStatus:'approved'}),1,1);
  f.sql.prepare("INSERT INTO friendbook_cf_interaction_resources VALUES (?, 'verified') ON CONFLICT(resource) DO UPDATE SET state='verified'").run(`posts/${id}`);
 }
 f.sql.prepare('INSERT INTO friendbook_cf_reactions VALUES (?,?,?,?)').run('posts/p1','user1','love',1);
 f.sql.prepare('INSERT INTO friendbook_cf_comments(resource,id,uid,text,created_at) VALUES (?,?,?,?,?)').run('posts/p1','c1','author','hello',1);
 const app=createWorker(async token=>token);
 const body={parent:'posts',filters:[{field:'groupId',op:'==',value:'room'}],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:10,feedEngagement:true};
 const response=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer user1','content-type':'application/json'},body:JSON.stringify(body)}),{...f.env,FIREBASE_PROJECT_ID:'test',D1_FEED_ENGAGEMENT_ENABLED:'true'});
 assert.equal(response.status,200,await response.clone().text());
 const docs=(await response.json()).documents;assert.equal(docs.length,2);
 const p1=docs.find(d=>d.id==='p1');assert.ok(p1.engagement);
 assert.deepEqual(p1.engagement.types,[{type:'love',count:1,mine:1}]);
 assert.equal(p1.engagement.comments,1);assert.equal(p1.engagement.preview[0].text,'hello');
});
