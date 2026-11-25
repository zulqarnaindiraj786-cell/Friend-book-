import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';
import {canonicalDocumentWriteDb,canonicalDocumentWriteEnv} from '../canonical-document-writes.js';
import {createWorker} from '../../worker.js';

function fixture(){
 const sql=new DatabaseSync(':memory:');
 sql.exec(`CREATE TABLE friendbook_cf_documents(path TEXT PRIMARY KEY,document_json TEXT);
 CREATE TABLE friendbook_cf_firestore_documents(path TEXT PRIMARY KEY,document_json TEXT);
 CREATE TABLE friendbook_cf_documents_archive(path TEXT PRIMARY KEY,document_json TEXT);`);
 let batches=0;
 const wrap=()=>({
  prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{
   run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null,raw:async()=>stmt.raw(...args),
  };}};},
  async batch(statements){batches++;const out=[];for(const s of statements)out.push(await s.run());return out;},
  withSession(){return wrap();},
  get batches(){return batches;},
 });
 return {sql,db:wrap()};
}
function fullMigrationFixture(){
 const sql=new DatabaseSync(':memory:');
 for(const name of readdirSync(new URL('../migrations/',import.meta.url)).filter(n=>n.endsWith('.sql')).sort())
  sql.exec(readFileSync(new URL(`../migrations/${name}`,import.meta.url),'utf8'));
 const retirement=readFileSync(new URL('../migrations/0104_retire_legacy_derived_index_triggers.sql',import.meta.url),'utf8');
 sql.exec(retirement);
 const queries=[];
 const db={
  prepare(query){queries.push(query);const stmt=sql.prepare(query);return{bind(...args){return{
   run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null,
  };}};},
  async batch(statements){sql.exec('BEGIN');try{const results=[];for(const statement of statements)results.push(await statement.run());sql.exec('COMMIT');return results;}catch(error){sql.exec('ROLLBACK');throw error;}},
 };
 return {sql,db,queries};
}

test('canonical mode skips mirror insert/upsert/update, but keeps canonical writes and reads',async()=>{
 const f=fixture(),db=canonicalDocumentWriteDb(f.db);
 try{
  await db.batch([
   db.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?)').bind('users/a','canonical'),
   db.prepare('INSERT INTO friendbook_cf_documents(path,document_json) VALUES (?,?)').bind('users/a','legacy'),
  ]);
  assert.equal(f.sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents').get().document_json,'canonical');
  assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_documents').get().n,0);
  await db.prepare('INSERT INTO friendbook_cf_documents(path,document_json) VALUES (?,?)').bind('users/b','legacy').run();
  await db.prepare('INSERT OR REPLACE INTO friendbook_cf_documents(path,document_json) VALUES (?,?)').bind('users/c','legacy').run();
  await db.prepare('REPLACE INTO friendbook_cf_documents(path,document_json) VALUES (?,?)').bind('users/d','legacy').run();
  await db.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').bind('changed','users/a').run();
  await db.prepare('UPDATE OR ABORT friendbook_cf_documents AS old SET document_json=? WHERE path=?').bind('changed','users/a').run();
  assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_documents').get().n,0);
  assert.equal((await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind('users/a').first()).document_json,'canonical');
  assert.equal(f.db.batches,1,'the mixed batch sends only canonical DML to the underlying binding');
 }finally{f.sql.close();}
});

test('delete cleanup is preserved and mode is explicitly opt-in',async()=>{
 const f=fixture();
 try{
  f.sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?)').run('users/a','legacy');
  const defaultEnv={DB:f.db};assert.equal(canonicalDocumentWriteEnv(defaultEnv),defaultEnv);
  const env=canonicalDocumentWriteEnv({DB:f.db,DOCUMENT_MIRROR_MODE:'canonical'});
  await env.DB.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind('users/a').run();
  assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_documents').get().n,0);
  await env.DB.withSession('first-primary').prepare('INSERT INTO friendbook_cf_documents VALUES (?,?)').bind('users/b','legacy').run();
  assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_documents').get().n,0);
 }finally{f.sql.close();}
});

test('statement matching does not suppress similarly named tables',async()=>{
 const f=fixture(),db=canonicalDocumentWriteDb(f.db);
 try{
  await db.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?)').bind('x','y').run();
  await db.prepare('INSERT INTO friendbook_cf_documents_archive VALUES (?,?)').bind('x','y').run();
  assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_firestore_documents').get().n,1);
  assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_documents_archive').get().n,1);
  assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_documents').get().n,0);
 }finally{f.sql.close();}
});

test('canonical chat, follow and post-search derived indexes update without source-0 mirror rows',async()=>{
 const f=fullMigrationFixture(),db=canonicalDocumentWriteDb(f.db);
 const canonical=`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?)`;
 const mirror=`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?)`;
 try{
  const retired=f.sql.prepare("SELECT count(*) n FROM sqlite_master WHERE type='trigger' AND name GLOB 'cf_*_0_*'").get().n;
  assert.equal(retired,0,'migration 0104 removes only source-0 mirror triggers');
  const retained=f.sql.prepare("SELECT count(*) n FROM sqlite_master WHERE type='trigger' AND name GLOB 'cf_*_1_*'").get().n;
  assert.ok(retained>=9,'canonical source-1 chat, follow and post-search trigger families remain installed');
  await db.batch([
   db.prepare(canonical).bind('users/alice','users','users','alice','alice',JSON.stringify({followers:['bob'],following:['carol']}),1,1),
   db.prepare(mirror).bind('users/alice','users','alice','alice',JSON.stringify({followers:['bob'],following:['carol']}),1),
   db.prepare(canonical).bind('chats/c1','chats','chats','c1','alice',JSON.stringify({participants:['alice','bob']}),1,1),
   db.prepare(mirror).bind('chats/c1','chats','c1','alice',JSON.stringify({participants:['alice','bob']}),1),
   db.prepare(canonical).bind('posts/p1','posts','posts','p1','alice',JSON.stringify({content:'canonical-search-term'}),1,1),
   db.prepare(mirror).bind('posts/p1','posts','p1','alice',JSON.stringify({content:'canonical-search-term'}),1),
  ]);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_documents").get().n,0);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_user_follow_membership WHERE path='users/alice' AND source=1 AND member='bob' AND kind='followers'").get().n,1);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_user_follow_membership WHERE path='users/alice' AND source=0").get().n,0);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_chat_participants WHERE chat_path='chats/c1' AND source=1 AND participant_uid='bob'").get().n,1);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_chat_participants WHERE chat_path='chats/c1' AND source=0").get().n,0);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_post_search WHERE path='posts/p1' AND source=1").get().n,1);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_post_search WHERE path='posts/p1' AND source=0").get().n,0);
  await db.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').bind(JSON.stringify({followers:['dave'],following:['carol']}),'users/alice').run();
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_user_follow_membership WHERE path='users/alice' AND source=1 AND member='bob'").get().n,0);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_user_follow_membership WHERE path='users/alice' AND source=1 AND member='dave'").get().n,1);
 }finally{f.sql.close();}
});

test('canonical-mode literal user retries never probe legacy document mirror',async()=>{
 const f=fullMigrationFixture();
 try{
  f.sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('users/alice','users','users','alice','alice',JSON.stringify({uid:'alice',displayName:'Alice'}),10,10);
  const app=createWorker(async token=>token);
  const response=await app.fetch(new Request('https://example.test/v1/firestore/document?path=users/alice',{
   method:'PATCH',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({fields:{displayName:'Alice'}}),
  }),{DB:f.db,FIREBASE_PROJECT_ID:'test',DOCUMENT_MIRROR_MODE:'canonical'});
  assert.equal(response.status,200,JSON.stringify(await response.clone().json()));
  const mirrorReads=f.queries.filter(query=>/^\s*(?:SELECT|WITH)\b/i.test(query)&&/friendbook_cf_documents\b/i.test(query));
  assert.deepEqual(mirrorReads,[],'canonical no-op state checks must not read mirror table');
 }finally{f.sql.close();}
});

test('actual follow and unfollow routes work with canonical writes and maintain source-1 membership',async()=>{
 const f=fullMigrationFixture();
 try{
  const put=f.sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(const uid of ['alice','bob'])put.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid,blocked:[],blockedUsers:[]}),1,1);
  const app=createWorker(async token=>token),env={DB:f.db,FIREBASE_PROJECT_ID:'test',DOCUMENT_MIRROR_MODE:'canonical'};
  const call=value=>app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({operations:[
   {op:'set',path:'users/alice',merge:true,data:{following:{__fieldValue:value,values:['bob']}}},
   {op:'set',path:'users/bob',merge:true,data:{followers:{__fieldValue:value,values:['alice']}}},
  ]})}),env);
  assert.equal((await call('arrayUnion')).status,200);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_user_follow_membership WHERE path='users/alice' AND source=1 AND member='bob' AND kind='following'").get().n,1);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_user_follow_membership WHERE path='users/bob' AND source=1 AND member='alice' AND kind='followers'").get().n,1);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_documents").get().n,0);
  assert.equal((await call('arrayRemove')).status,200);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_user_follow_membership WHERE path IN ('users/alice','users/bob') AND source=1 AND member IN ('alice','bob')").get().n,0);
  const chat=await app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({operations:[
   {op:'set',path:'chats/room',data:{isGroup:true,createdBy:'alice',adminIds:['alice'],participants:['alice','bob'],onlyAdminsCanSend:false,groupName:'Room'}},
   {op:'set',path:'chats/room/messages/welcome',data:{senderId:'alice',type:'system',text:'Welcome'}},
  ]})}),env);
  assert.equal(chat.status,200,JSON.stringify(await chat.clone().json()));
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_chat_participants WHERE chat_path='chats/room' AND source=1").get().n,2);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_chat_participants WHERE chat_path='chats/room' AND source=0").get().n,0);
  assert.equal(f.sql.prepare("SELECT count(*) n FROM friendbook_cf_documents WHERE path LIKE 'chats/room%'").get().n,0);
 }finally{f.sql.close();}
});
