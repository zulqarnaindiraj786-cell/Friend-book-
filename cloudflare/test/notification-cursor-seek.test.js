import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';
const migration=n=>readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8');

test('numeric and timestamp notification cursors seek without losing privacy, unread state or legacy rows',async()=>{
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0055_notification_page_indexes.sql'])sql.exec(migration(n));
  const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const l=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  for(const uid of ['alice','bob','eve'])f.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid}),1,1);
  for(let i=0;i<160;i++){
    const id=`n${String(i).padStart(3,'0')}`,time=Math.floor((i%80)/4)-15;
    const doc={fromUserId:'alice',toUserId:'bob',isRead:i%3===0,createdAt:i<80?time:{__type:'timestamp',millis:time}};
    if(i%3!==0)f.run(`notifications/${id}`,'notifications','notifications',id,'alice',JSON.stringify(doc),1,1);
    if(i%3!==1)l.run(`notifications/${id}`,'notifications',id,'alice',JSON.stringify(doc),1);
  }
  // Matching legacy copies must stay hidden if canonical ownership/read state changed.
  for(const [id,canonical] of [['wrong-user',{toUserId:'eve',isRead:false}],['now-read',{toUserId:'bob',isRead:true}]]){
    const old={fromUserId:'alice',toUserId:'bob',isRead:false,createdAt:-5};
    l.run(`notifications/${id}`,'notifications',id,'alice',JSON.stringify(old),1);
    f.run(`notifications/${id}`,'notifications','notifications',id,'alice',JSON.stringify({...old,...canonical}),1,1);
  }
  let fail=false;const plans=[];
  const db={prepare(q){
    const seek=q.includes('INDEXED BY idx_cf_') && q.includes('quota_notif_rank');
    if(fail && seek)throw Error('storage unavailable');
    const s=sql.prepare(q);return{bind(...a){return{
      all:async()=>{if(seek)plans.push(sql.prepare('EXPLAIN QUERY PLAN '+q).all(...a).map(r=>r.detail));return{results:s.all(...a)};},
      first:async()=>s.get(...a)||null,
    };}};
  }};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const query=async(spec,reference=false)=>{
    const filters=[{field:'toUserId',op:'==',value:'bob'},...(spec.unread?[{field:'isRead',op:'==',value:false}]:[]),...(reference?[{field:'fromUserId',op:'==',value:'alice'}]:[])];
    const {unread,...other}=spec;
    const body={parent:'notifications',filters,orderBy:[{field:'createdAt',direction:'DESCENDING'}],...other};
    const res=await app.fetch(new Request('https://local.invalid/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify(body)}),env);
    assert.equal(res.status,200,await res.clone().text());return (await res.json()).documents.map(x=>x.id);
  };
  for(const unread of [false,true])for(const cursor of [[99,'z'],[4,'n070'],[0,'n060'],[-15,'n003'],[{__type:'timestamp',millis:99},'z'],[{__type:'timestamp',millis:4},'n159'],[{__type:'timestamp',millis:-15},'n080']])for(const limit of [1,7,20,100]){
    const spec={unread,limit,startAfter:cursor};assert.deepEqual(await query(spec),await query(spec,true),JSON.stringify(spec));
  }
  assert.ok(plans.length>0);
  for(const p of plans){const plan=p.join('\n');assert.match(plan,/idx_cf_(?:legacy_)?notif_(?:page|unread)/);assert.match(plan,/<expr>=\?/);assert.match(plan,/<expr><\?/);}
  // Delta path retains its original ordering and seek; it is not this shortcut.
  const before=plans.length;const delta={limit:20,limitToLast:true,endBefore:[0,'n060']};assert.deepEqual(await query(delta),await query(delta,true));assert.equal(plans.length,before);
  fail=true;
  const request=new Request('https://local.invalid/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify({parent:'notifications',filters:[{field:'toUserId',op:'==',value:'bob'}],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:7,startAfter:[0,'n060']})});
  assert.equal((await app.fetch(request,env)).status,503,'unrelated storage failures must not become empty pages');fail=false;
  // Rolling back schema indexes must retain the original unhinted query.
  for(const name of ['idx_cf_notif_page','idx_cf_notif_unread','idx_cf_legacy_notif_page','idx_cf_legacy_notif_unread'])sql.exec(`DROP INDEX ${name}`);
  for(const unread of [false,true])for(const cursor of [[0,'n060'],[{__type:'timestamp',millis:4},'n159']]){const spec={unread,limit:20,startAfter:cursor};assert.deepEqual(await query(spec),await query(spec,true));}
  // Wrapped historical data takes the existing complete-merge fallback.
  l.run('notifications/wrapped','notifications','wrapped','alice',JSON.stringify({fields:{fromUserId:{stringValue:'alice'},toUserId:{stringValue:'bob'},isRead:{booleanValue:false},createdAt:{integerValue:'-2'}}}),1);
  const wrapped={limit:20,startAfter:[0,'n060']};assert.deepEqual(await query(wrapped),await query(wrapped,true));
  sql.close();
});
