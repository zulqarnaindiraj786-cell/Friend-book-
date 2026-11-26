import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';
import {scheduled} from '../scheduled.js';

test('real-schema maintenance ends stale lives, preserves fresh rooms and historical view rows',async()=>{
  const sql=new DatabaseSync(':memory:');
  try {
    const dir=new URL('../migrations/',import.meta.url);
    for(const f of readdirSync(dir).filter(f=>f.endsWith('.sql')&&f!=='0105_retire_legacy_document_mirror.sql'&&f!=='0106_enforce_document_mirror_retirement.sql'&&f!=='0109_archive_reappeared_document_mirror.sql').sort())sql.exec(readFileSync(new URL(f,dir),'utf8'));
    const now=Date.now();
    for(const [id,heartbeat] of [['stale',now-240000],['fresh',now]])
      sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run(`lives/${id}`,'lives','lives',id,'host',JSON.stringify({status:'live',lastHeartbeatMs:heartbeat}),1,1);
    sql.exec("INSERT INTO friendbook_cf_view_dedupe VALUES ('reels/old','viewer','historical')");
    const queries=[];
    const db={prepare(q){queries.push(q);const stmt=sql.prepare(q);return {bind(...a){return {async run(){return stmt.run(...a);},async all(){return {results:stmt.all(...a)};}};}};}};
    await scheduled({}, {DB:db});
    const status=id=>JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`lives/${id}`).document_json).status;
    assert.equal(status('stale'),'ended');assert.equal(status('fresh'),'live');
    assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_view_dedupe').get().n,1);
    assert.equal(queries.length,3);assert.ok(queries.some(q=>q.includes('friendbook_cf_broadcast_push')));
    assert.ok(queries.some(q=>q.includes('idx_cf_notification_retention')));
  } finally {sql.close();}
});

test('scheduled retention removes at most 200 expired notifications per tick using the retention index',async()=>{
 const sql=new DatabaseSync(':memory:');
 try {
  const dir=new URL('../migrations/',import.meta.url);
  for(const f of readdirSync(dir).filter(f=>f.endsWith('.sql')&&f!=='0105_retire_legacy_document_mirror.sql'&&f!=='0106_enforce_document_mirror_retirement.sql'&&f!=='0109_archive_reappeared_document_mirror.sql').sort())sql.exec(readFileSync(new URL(f,dir),'utf8'));
  const now=Date.now(),put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(let i=0;i<225;i++){
   for(const [kind,read,age] of [['read',true,8*86400000],['unread',false,31*86400000]]){
    const id=`${kind}${i}`,createdAt=now-age;
    put.run(`notifications/${id}`,'notifications','notifications',id,'owner',JSON.stringify({toUserId:'owner',isRead:read,createdAt}),createdAt,createdAt);
   }
  }
  for(const [id,row] of [
   ['fresh-read',{toUserId:'owner',isRead:true,createdAt:now-86400000}],
   ['fresh-unread',{toUserId:'owner',isRead:false,createdAt:now-20*86400000}],
   ['unknown-read',{toUserId:'owner',createdAt:now-90*86400000}],
   ['wrapped-time',{toUserId:'owner',isRead:true,createdAt:{__type:'timestamp',millis:now-90*86400000}}],
   ['malformed-time',{toUserId:'owner',isRead:true,createdAt:{__type:'timestamp',millis:'bad'}}],
  ])put.run(`notifications/${id}`,'notifications','notifications',id,'owner',JSON.stringify(row),now,now);
  const plans=[],failures=[],deleteChanges=[];
  const db={prepare(q){let stmt;try{stmt=sql.prepare(q);}catch(e){failures.push(e.message);throw e;}return{bind(...args){
   if(q.startsWith('DELETE FROM friendbook_cf_firestore_documents'))plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(r=>r.detail));
   return{async run(){try{const result=stmt.run(...args);if(q.startsWith('DELETE FROM friendbook_cf_firestore_documents'))deleteChanges.push(result.changes);return result;}catch(e){failures.push(e.message);throw e;}},async all(){return{results:stmt.all(...args)};}};
  }};}};
  const expired=()=>sql.prepare("SELECT count(*) n FROM friendbook_cf_firestore_documents WHERE parent_path='notifications' AND ((json_type(document_json,'$.isRead')='true' AND (CASE WHEN json_type(document_json,'$.createdAt')='object' THEN json_extract(document_json,'$.createdAt.millis') ELSE json_extract(document_json,'$.createdAt') END)<?) OR (json_type(document_json,'$.isRead')='false' AND (CASE WHEN json_type(document_json,'$.createdAt')='object' THEN json_extract(document_json,'$.createdAt.millis') ELSE json_extract(document_json,'$.createdAt') END)<?))").get(now-7*86400000,now-30*86400000).n;
  assert.equal(expired(),451);
  await scheduled({}, {DB:db});assert.deepEqual(failures,[]);assert.deepEqual(deleteChanges,[200]);assert.equal(expired(),251,'first cron tick deletes at most 200, including timestamp envelopes');
  await scheduled({}, {DB:db});assert.equal(expired(),51,'second cron tick deletes at most 200');
  await scheduled({}, {DB:db});assert.equal(expired(),0,'third tick drains the remaining fifty-one');
  assert.ok(plans.some(p=>p.includes('idx_cf_notification_retention')),plans.join('\\n'));
  for(const id of ['fresh-read','fresh-unread','unknown-read','malformed-time'])assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_firestore_documents WHERE path=?').get(`notifications/${id}`).n,1);
  assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_firestore_documents WHERE path=?').get('notifications/wrapped-time').n,0);
 } finally {sql.close();}
});

test('synchronous live-task prepare failure does not suppress outbox delivery attempts',async()=>{
  const queries=[];
  await scheduled({}, {DB:{prepare(q){queries.push(q);if(q.startsWith('UPDATE'))throw Error('temporary prepare failure');
    return {bind(){return {async all(){return {results:[]};}};}};
  }}});
  assert.equal(queries.length,3);assert.ok(queries.some(q=>q.includes('friendbook_cf_broadcast_push')));
  assert.ok(queries.some(q=>q.includes('idx_cf_notification_retention')));
});

test('outbox rejection cannot suppress independent live expiry or reject the whole cron',async()=>{
  let ran=false;
  await scheduled({}, {DB:{prepare(q){return {bind(){return {
    async run(){ran=true;},async all(){throw Error('outbox unavailable');},
  };}};}}});
  assert.equal(ran,true);
  await scheduled({},{});
});

test('maintenance seeks active status even with thousands of ended lives and expires at most twenty',async()=>{
 const sql=new DatabaseSync(':memory:');
 try {
   const dir=new URL('../migrations/',import.meta.url);
   for(const f of readdirSync(dir).filter(f=>f.endsWith('.sql')&&f!=='0105_retire_legacy_document_mirror.sql'&&f!=='0106_enforce_document_mirror_retirement.sql'&&f!=='0109_archive_reappeared_document_mirror.sql').sort())sql.exec(readFileSync(new URL(f,dir),'utf8'));
   const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
   const now=Date.now();
   for(let i=0;i<2500;i++)put.run(`lives/ended${i}`,'lives','lives',`ended${i}`,'host',JSON.stringify({status:'ended',lastHeartbeatMs:1}),1,1);
   for(let i=0;i<25;i++)put.run(`lives/stale${i}`,'lives','lives',`stale${i}`,'host',JSON.stringify({status:'live',lastHeartbeatMs:now-240000}),1,1);
   put.run('lives/fresh','lives','lives','fresh','host',JSON.stringify({status:'live',lastHeartbeatMs:now}),1,1);
   const plans=[];
   const db={prepare(q){const stmt=sql.prepare(q);return{bind(...a){
     if(q.startsWith('UPDATE'))plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...a).map(r=>r.detail));
     return{async run(){return stmt.run(...a);},async all(){return{results:stmt.all(...a)};}};
   }};}};
   await scheduled({}, {DB:db});
   assert.ok(plans.some(p=>p.includes('SEARCH') && p.includes('idx_cf_lives_status')),plans.join('\n'));
   assert.ok(!plans.some(p=>p.includes('SCAN friendbook_cf_firestore_documents')),plans.join('\n'));
   const staleRemaining=()=>sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_firestore_documents WHERE parent_path='lives' AND json_extract(document_json,'$.status')='live' AND path<>'lives/fresh'").get().n;
   assert.equal(staleRemaining(),5,'only twenty of twenty-five expired lives in the first pass');
   await scheduled({}, {DB:db});assert.equal(staleRemaining(),0);
   assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='lives/fresh'").get().document_json).status,'live');
   assert.equal(sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_firestore_documents WHERE path LIKE 'lives/ended%'").get().n,2500,'historical lives are preserved');
 } finally {sql.close();}
});
