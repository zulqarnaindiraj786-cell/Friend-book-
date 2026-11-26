import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { profileAudience } from '../profile-audience.js';

test('profile audience deduplicates followers, verified friends and pending senders', async t => {
  const sql=new DatabaseSync(':memory:'); t.after(()=>sql.close());
  sql.exec(`CREATE TABLE friendbook_cf_trusted_friendships(uid_low,uid_high);
    CREATE TABLE friendbook_cf_firestore_documents(path,parent_path,document_json);
    CREATE TABLE friendbook_cf_documents(path,collection,document_json);
    INSERT INTO friendbook_cf_trusted_friendships VALUES ('alice','bob'),('bob','dana');`);
  const current=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?)');
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?)');
  const add=(stmt,id,from,status)=>stmt.run(id,'friend_requests',JSON.stringify({fromUid:from,toUid:'bob',status}));
  add(current,'pending','alice','pending');
  add(current,'new','chris','pending');
  add(current,'stale','ignored','accepted');
  add(legacy,'stale','ignored','pending');
  add(legacy,'old','ellen','pending');
  const db={prepare:q=>({bind:(...args)=>({first:async()=>sql.prepare(q).get(...args)})})};
  assert.deepEqual(await profileAudience(db,'bob','chris',['alice','dana'],1),{profileAudienceCount:3,profileAudienceIncludesViewer:true});
  sql.exec("DELETE FROM friendbook_cf_firestore_documents WHERE path='new'");
  assert.deepEqual(await profileAudience(db,'bob','chris',['alice','dana'],2),{profileAudienceCount:2,profileAudienceIncludesViewer:false});
});

test('profile audience coalesces same-viewer repeats briefly and invalidates on profile revision',async()=>{
  let reads=0;
  const db={prepare(){return {bind(){return {first:async()=>{reads++;return {total:7,viewerIncluded:1};}}}}}};
  const first=await profileAudience(db,'profile','viewer',['follower'],10);
  const repeated=await profileAudience(db,'profile','viewer',['follower'],10);
  assert.deepEqual(repeated,first);
  assert.equal(reads,1,'a same-profile refresh during the short display-cache window avoids another graph scan');
  await profileAudience(db,'profile','viewer',['follower'],11);
  assert.equal(reads,2,'profile writes invalidate the cached aggregate');
  await profileAudience(db,'profile','other-viewer',['follower'],11);
  assert.equal(reads,3,'viewer membership is never shared across viewers');
});

test('audience works without a legacy table and seeks canonical recipient/status',async t=>{
  const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
  sql.exec(`CREATE TABLE friendbook_cf_trusted_friendships(uid_low,uid_high);
    CREATE INDEX friendship_low ON friendbook_cf_trusted_friendships(uid_low,uid_high);
    CREATE INDEX friendship_high ON friendbook_cf_trusted_friendships(uid_high,uid_low);
    CREATE TABLE friendbook_cf_firestore_documents(path,parent_path,document_json,doc_id);
    CREATE INDEX idx_cf_request_canonical_in_status ON friendbook_cf_firestore_documents(
      parent_path,json_extract(document_json,'$.toUid'),json_extract(document_json,'$.status'),json_extract(document_json,'$.fromUid'),doc_id)
      WHERE parent_path='friend_requests';
    WITH RECURSIVE seq(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM seq WHERE i<5000)
    INSERT INTO friendbook_cf_firestore_documents
      SELECT 'friend_requests/'||i,'friend_requests',json_object('fromUid','sender'||i,'toUid','other'||i,'status','pending'),i FROM seq;
    INSERT INTO friendbook_cf_firestore_documents VALUES
      ('friend_requests/p','friend_requests','{"fromUid":"sender","toUid":"bob","status":"pending"}','p');`);
  let statement='',args=[];
  const db={prepare:q=>({bind:(...values)=>({first:async()=>{statement=q;args=values;return sql.prepare(q).get(...values);}})})};
  assert.deepEqual(await profileAudience(db,'bob','sender',[],1),{profileAudienceCount:1,profileAudienceIncludesViewer:true});
  const plan=sql.prepare('EXPLAIN QUERY PLAN '+statement).all(...args).map(row=>row.detail).join('\n');
  assert.match(plan,/SEARCH friendbook_cf_firestore_documents USING (?:COVERING )?INDEX idx_cf_request_canonical_in_status/);
  assert.doesNotMatch(statement,/friendbook_cf_documents\b/);
  // A canonical cancellation/deletion cannot be undone by a stale mirror.
  sql.exec("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.status','accepted') WHERE doc_id='p'");
  assert.deepEqual(await profileAudience(db,'bob','sender',[],2),{profileAudienceCount:0,profileAudienceIncludesViewer:false});
});
