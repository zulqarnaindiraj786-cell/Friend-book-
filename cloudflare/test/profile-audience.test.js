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
  assert.deepEqual(await profileAudience(db,'bob','chris',['alice','dana']),{profileAudienceCount:4,profileAudienceIncludesViewer:true});
  sql.exec("DELETE FROM friendbook_cf_firestore_documents WHERE path='new'");
  assert.deepEqual(await profileAudience(db,'bob','chris',['alice','dana']),{profileAudienceCount:3,profileAudienceIncludesViewer:false});
});
