import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';

const migration=name=>readFileSync(new URL(`../migrations/${name}`,import.meta.url),'utf8');
test('canonical-write Worker acceptance ignores stale mirror friendship but commits canonical request, edge and counters atomically',async()=>{
 const sql=new DatabaseSync(':memory:');
 for(const name of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0018_social_guards.sql','0019_trusted_friendships.sql','0027_friend_participant_indexes.sql','0084_friend_guard_exceptions.sql'])sql.exec(migration(name));
 const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const putLegacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 const user=(uid)=>put.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid,friendsCount:0}),1,1);
 user('alice');user('bob');
 put.run('friend_requests/r1','friend_requests','friend_requests','r1','alice',JSON.stringify({fromUid:'alice',toUid:'bob',status:'pending'}),1,1);
 putLegacy.run('friends/stale','friends','stale','alice',JSON.stringify({participants:['alice','bob']}),1);
 const db={
  prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{
   run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null,
  };}};},
  async batch(statements){sql.exec('BEGIN');try{const out=[];for(const statement of statements)out.push(await statement.run());sql.exec('COMMIT');return out;}catch(error){sql.exec('ROLLBACK');throw error;}},
 };
 try{
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test',FRIEND_GUARD_INDEXED:'true',DOCUMENT_MIRROR_MODE:'canonical'};
  const operations=[
   {op:'update',path:'friend_requests/r1',updates:{status:'accepted'}},
   {op:'set',path:'friends/new',data:{participants:['alice','bob'],createdAt:{__fieldValue:'serverTimestamp'}}},
   ...['alice','bob'].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:1}}})),
  ];
  const response=await app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify({operations})}),env);
  assert.equal(response.status,200,JSON.stringify(await response.clone().json()));
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='friend_requests/r1'").get().document_json).status,'accepted');
  assert.deepEqual(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='friends/new'").get().document_json).participants.slice().sort(),['alice','bob']);
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_documents WHERE path='friends/new'").get().n,0);
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_documents WHERE path='friends/stale'").get().n,1,'legacy history is not destructively purged');
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_trusted_friendships WHERE uid_low='alice' AND uid_high='bob'").get().n,1);
  assert.equal(sql.prepare("SELECT sum(json_extract(document_json,'$.friendsCount')) n FROM friendbook_cf_firestore_documents WHERE parent_path='users'").get().n,2);
 }finally{sql.close();}
});
