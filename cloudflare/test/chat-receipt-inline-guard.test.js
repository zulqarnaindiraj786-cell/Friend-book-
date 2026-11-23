import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

function fixture({legacyRoom=false,group=false}={}) {
  const sql=new DatabaseSync(':memory:');
  for(const file of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0018_social_guards.sql','0019_trusted_friendships.sql','0021_trusted_group_chats.sql'])
    sql.exec(readFileSync(new URL(`../migrations/${file}`,import.meta.url),'utf8'));
  const canonical=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES(?,?,?,?,?,?,?,?)');
  for(const uid of ['alice','bob','eve'])canonical.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid}),1,1);
  const room={participants:['alice','bob'],unreadCount:{bob:2},...(group?{isGroup:true,createdBy:'alice',adminIds:['alice'],creatorId:'alice'}:{})};
  if(legacyRoom)sql.prepare('INSERT INTO friendbook_cf_documents VALUES(?,?,?,?,?,?)').run('chats/room','chats','room','alice',JSON.stringify(room),1);
  else canonical.run('chats/room','chats','chats','room','alice',JSON.stringify(room),1,1);
  if(group)sql.prepare('INSERT INTO friendbook_cf_trusted_group_chats VALUES (?,?)').run('chats/room','alice');
  canonical.run('chats/room/messages/m','chats/room/messages','messages','m','alice',JSON.stringify({senderId:'alice',receiverId:'bob',text:'hello',isSeen:false,isDelivered:false}),1,1);
  let race,guardWrites=0;
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return {
    run:async()=>{if(q.includes('INSERT INTO friendbook_cf_social_guards'))guardWrites++;return stmt.run(...args);},
    first:async()=>stmt.get(...args)||null,all:async()=>({results:stmt.all(...args)}),
  };}}},async batch(stmts){if(race){const run=race;race=null;run();}sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const receive=(uid='bob',updates={isSeen:true,isDelivered:true},messagePath='chats/room/messages/m')=>app.fetch(new Request('https://example.test/v1/firestore/batch',{
    method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify({operations:[
      {op:'update',path:messagePath,updates},{op:'update',path:'chats/room',updates:{[`unreadCount.${uid}`]:0}}
    ]})}),env);
  const get=path=>{const row=sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(path);return row?JSON.parse(row.document_json):null;};
  return {sql,receive,get,race:f=>{race=f},guards:()=>guardWrites};
}

test('direct receipt commits atomically without temporary guard writes; retries are no-ops',async()=>{
  const f=fixture();const changes=()=>f.sql.prepare('SELECT total_changes() AS n').get().n;
  assert.equal((await f.receive('eve')).status,403);
  const before=changes();assert.equal((await f.receive()).status,200);
  assert.equal(changes()-before,2);assert.equal(f.guards(),0);
  assert.equal(f.get('chats/room/messages/m').isSeen,true);assert.equal(f.get('chats/room').unreadCount.bob,0);
  const after=changes();assert.equal((await f.receive()).status,200);assert.equal(changes(),after);
  f.sql.close();
});

test('direct receipt rejects changed or removed message and membership without partial writes',async()=>{
  for(const kind of ['membership','message','message-deleted','room-deleted','room-update-failure']){
    const f=fixture();f.race(()=>{
      if(kind==='membership')f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.participants',json('[\"alice\",\"eve\"]')) WHERE path='chats/room'").run();
      if(kind==='message')f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.text','changed') WHERE path='chats/room/messages/m'").run();
      if(kind==='message-deleted')f.sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='chats/room/messages/m'").run();
      if(kind==='room-deleted')f.sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='chats/room'").run();
      if(kind==='room-update-failure')f.sql.exec("CREATE TRIGGER reject_reset BEFORE UPDATE ON friendbook_cf_firestore_documents WHEN NEW.path='chats/room' BEGIN SELECT RAISE(ABORT,'failure'); END");
    });
    assert.notEqual((await f.receive()).status,200,kind);
    const message=f.get('chats/room/messages/m'),room=f.get('chats/room');
    if(message)assert.equal(message.isSeen,false,kind);
    if(room)assert.equal(room.unreadCount.bob,2,kind);
    assert.equal(f.sql.prepare('SELECT count(*) n FROM friendbook_cf_social_guards').get().n,0);
    f.sql.close();
  }
});

test('legacy rooms keep the existing guarded receipt path and other message changes stay forbidden',async()=>{
  const f=fixture({legacyRoom:true});
  assert.equal((await f.receive('bob',{isSeen:true,text:'tampered'})).status,404);
  assert.equal((await f.receive()).status,404);assert.equal(f.guards(),0);
  assert.equal(f.get('chats/room/messages/m').isSeen,false);
  f.sql.close();
});

test('group rooms retain their trusted-ledger guard',async()=>{
  const f=fixture({group:true});
  assert.equal((await f.receive()).status,200);
  assert.ok(f.guards()>0);
  assert.equal(f.get('chats/room/messages/m').isSeen,true);
  f.sql.close();
});

test('historical root messages keep guarded dual-store writes',async()=>{
  const f=fixture();const data=JSON.stringify({chatId:'room',senderId:'alice',receiverId:'bob',isSeen:false,isDelivered:false});
  f.sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES(?,?,?,?,?,?,?,?)').run('messages/root','messages','messages','root','alice',data,1,1);
  f.sql.prepare('INSERT INTO friendbook_cf_documents VALUES(?,?,?,?,?,?)').run('messages/root','messages','root','alice',data,1);
  assert.equal((await f.receive('bob',{isSeen:true,isDelivered:true},'messages/root')).status,200);
  assert.ok(f.guards()>0);
  assert.equal(f.get('messages/root').isSeen,true);
  assert.equal(JSON.parse(f.sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='messages/root'").get().document_json).isSeen,true);
  f.sql.close();
});
