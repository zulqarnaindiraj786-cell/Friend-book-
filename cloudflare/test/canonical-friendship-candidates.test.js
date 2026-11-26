import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {canonicalFullFriendCandidates,canonicalIndexedFriendCandidates,friendshipCandidates,friendshipDuplicateGuard,malformedCanonicalFriendProbe} from '../friendship-candidates.js';

const migration=name=>readFileSync(new URL(`../migrations/${name}`,import.meta.url),'utf8');
const wrap=sql=>({prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{all:async()=>({results:stmt.all(...args)})};}};}});
const setup=()=>{
 const sql=new DatabaseSync(':memory:');
 for(const name of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0027_friend_participant_indexes.sql','0084_friend_guard_exceptions.sql','0105_canonical_wrapped_friend_indexes.sql'])sql.exec(migration(name));
 const canonical=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const mirror=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 return{sql,canonical,mirror};
};
const guard=(sql,selection)=>{const spec=friendshipDuplicateGuard(selection,'alice','bob');return sql.prepare(`SELECT ${spec.sql} allowed`).get(...spec.args).allowed;};

test('canonical-only friend candidates ignore stale mirror-only rows but retain duplicate guard and canonical shadows',async()=>{
 const {sql,canonical,mirror}=setup();
 try{
  canonical.run('friends/current','friends','friends','current','',JSON.stringify({participants:['alice','bob']}),1,1);
  mirror.run('friends/current','friends','current','',JSON.stringify({participants:['eve','mallory']}),1);
  mirror.run('friends/old','friends','old','',JSON.stringify({participants:['alice','bob']}),1);
  const selection=await friendshipCandidates(wrap(sql),'alice',true,true);
  assert.equal(selection.sql,canonicalIndexedFriendCandidates);
  assert.deepEqual(selection.args,['alice','alice','alice','alice']);
  assert.equal(selection.canonicalOnly,true);
  assert.doesNotMatch(selection.sql,/friendbook_cf_documents|idx_cf_legacy/);
  assert.equal(guard(sql,selection),0,'canonical duplicate remains denied');
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='friends/current'").run();
  const fresh=await friendshipCandidates(wrap(sql),'alice',true,true);
  assert.equal(guard(sql,fresh),1,'stale and legacy-only mirrors cannot block a new request');
  assert.equal(sql.prepare(`SELECT count(*) n FROM (${canonicalFullFriendCandidates})`).get().n,0);
 }finally{sql.close();}
});

test('normal Firestore-wrapped friendship pairs seek both participant indexes',async()=>{
 const {sql,canonical}=setup();
 try{
  const wrapped=participants=>({fields:{participants:{arrayValue:{values:participants.map(stringValue=>({stringValue}))}}}});
  canonical.run('friends/wrapped','friends','friends','wrapped','',JSON.stringify(wrapped(['alice','bob'])),1,1);
  canonical.run('friends/other','friends','friends','other','',JSON.stringify(wrapped(['eve','mallory'])),1,1);
  const selection=await friendshipCandidates(wrap(sql),'alice',true,true);
  assert.equal(guard(sql,selection),0,'wrapped duplicate must remain protected');
  const plan=sql.prepare(`EXPLAIN QUERY PLAN SELECT path FROM (${canonicalIndexedFriendCandidates})`).all(...selection.args).map(row=>row.detail).join('\\n');
  assert.match(plan,/idx_cf_friend_wrapped_participant_0/);
  assert.match(plan,/idx_cf_friend_wrapped_participant_1/);
  assert.match(plan,/idx_cf_friend_guard_exceptions_v2/);
  assert.equal(sql.prepare(`SELECT ${malformedCanonicalFriendProbe} dangerous`).get().dangerous,0,
    'ordinary wrapped pairs must not enter the malformed exception probe');
 }finally{sql.close();}
});

test('canonical-only malformed envelope fallback preserves the original fail-closed result',async()=>{
 const {sql,canonical}=setup();
 try{
  canonical.run('friends/bad','friends','friends','bad','',JSON.stringify({fields:{participants:{arrayValue:{values:['not-json']}}}}),1,1);
  const selection=await friendshipCandidates(wrap(sql),'alice',true,true);
  assert.equal(sql.prepare(`SELECT ${malformedCanonicalFriendProbe} dangerous`).get().dangerous,1);
  const spec=friendshipDuplicateGuard(selection,'alice','bob');
  assert.throws(()=>sql.prepare(`SELECT ${spec.sql} allowed`).get(...spec.args),/malformed JSON/);
 }finally{sql.close();}
});

test('canonical-only missing indexes fall back without restoring mirror reads',async()=>{
 const sql=new DatabaseSync(':memory:');
 for(const name of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql'])sql.exec(migration(name));
 try{
  const selected=await friendshipCandidates(wrap(sql),'alice',true,true);
  assert.equal(selected.sql,canonicalFullFriendCandidates);
  assert.equal(selected.canonicalOnly,true);
  assert.doesNotMatch(selected.sql,/friendbook_cf_documents/);
  const full=await friendshipCandidates(wrap(sql),'alice',false,true);
  assert.equal(full.sql,canonicalFullFriendCandidates);
 }finally{sql.close();}
});
