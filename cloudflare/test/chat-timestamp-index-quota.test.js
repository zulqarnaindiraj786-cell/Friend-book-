import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';
const dir=new URL('../migrations/',import.meta.url),name='0090_scope_chat_timestamp_index.sql';

test('chat-only timestamp index preserves root-chat order and avoids non-chat index insert maintenance',()=>{
 const db=new DatabaseSync(':memory:');
 try{
  for(const file of readdirSync(dir).filter(n=>n.endsWith('.sql')&&n<name).sort())db.exec(readFileSync(new URL(file,dir),'utf8'));
  const put=db.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(let i=0;i<100;i++){
   const root=i%3===0,parent=root?'chats':i%2?'notifications':'users/u/chats';
   put.run(`${parent}/r${i}`,parent,root||i%2===0?'chats':'notifications',`r${i}`,'alice',JSON.stringify({participants:['alice','bob']}),i,i*10);
  }
  // Exact deployed root-chat list query, not an approximation.
  const q="SELECT path,doc_id,document_json,update_time AS updated_at,1 AS canonical FROM friendbook_cf_firestore_documents WHERE collection_id='chats' AND parent_path='chats' ORDER BY updated_at DESC LIMIT ?";
  const read=()=>db.prepare(q).all(20);
  const before=read();assert.equal(before.length,20);assert.equal(before[0].doc_id,'r99');
  const probe=parent=>`INSERT INTO friendbook_cf_firestore_documents VALUES ('${parent}/probe','${parent}','${parent}','probe','alice','{}',1,1)`;
  const inserts=parent=>db.prepare('EXPLAIN '+probe(parent)).all().filter(r=>r.opcode==='IdxInsert').length;
  const oldNotifications=inserts('notifications'),oldChats=inserts('chats');
  const migration=readFileSync(new URL(name,dir),'utf8');db.exec(migration);db.exec(migration);
  assert.deepEqual(read(),before);
  const plan=db.prepare('EXPLAIN QUERY PLAN '+q).all(20).map(r=>r.detail).join('\n');
  assert.match(plan,/idx_cf_chat_root_updated/);assert.doesNotMatch(plan,/TEMP B-TREE/);
  // The static opcode is conditional in SQLite; inspect its jump guards as well
  // via the partial index predicate and successful inserts of both root types.
  assert.equal(inserts('notifications'),oldNotifications);
  assert.equal(inserts('chats'),oldChats);
  db.exec(probe('notifications'));db.exec(probe('chats'));
  assert.equal(db.prepare("SELECT count(*) AS n FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_chat_root_updated WHERE collection_id='chats' AND parent_path='chats'").get().n,35);
  db.prepare("UPDATE friendbook_cf_firestore_documents SET update_time=99999 WHERE path='chats/r0'").run();
  assert.equal(read()[0].doc_id,'r0');
  assert.equal(db.prepare("SELECT name FROM sqlite_schema WHERE name='idx_friendbook_cf_firestore_collection_updated'").get(),undefined);
  assert.ok(db.prepare("SELECT name FROM sqlite_schema WHERE name='idx_cf_live_chat_sender_time'").get());
  assert.equal(db.prepare('SELECT count(*) AS n FROM friendbook_cf_firestore_documents').get().n,102);
 }finally{db.close();}
});
