import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {firestoreDocuments} from '../firestore-documents.js';

const uid='author';
const sql=new DatabaseSync(':memory:');
for(const file of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0027_friend_participant_indexes.sql'])
  sql.exec(readFileSync(new URL(`../migrations/${file}`,import.meta.url),'utf8'));
const insertFriend=sql.prepare("INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json) VALUES(?, 'friends','friends',?,'',?)");
const insertUser=sql.prepare("INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json) VALUES(?, 'users','users',?,'',?)");
for(let i=0;i<125;i++){
  const friend=`friend${String(i).padStart(3,'0')}`;
  const participants=i%2===0?[uid,friend]:[friend,uid];
  insertFriend.run(`friends/${friend}`,friend,JSON.stringify({participants}));
  insertUser.run(`users/${friend}`,friend,JSON.stringify({uid:friend,lastSeenMs:i}));
}
const db={prepare(query){const statement=sql.prepare(query);return{bind(...args){return{all:async()=>({results:statement.all(...args)})};}};}};

test('friend-post recipient endpoint returns at most 100 trusted friends, most recently active first, without exposing activity',async()=>{
  const response=await firestoreDocuments(new Request('https://example.test/v1/firestore/friend-post-recipients',{
    method:'POST',headers:{'content-type':'application/json'},body:'{}'
  }),{DB:db},{uid});
  assert.equal(response.status,200);
  const body=await response.json();
  assert.equal(body.recipients.length,100);
  assert.deepEqual(body.recipients.slice(0,3),['friend124','friend123','friend122']);
  assert.equal(body.recipients.at(-1),'friend025');
  assert.equal(JSON.stringify(body).includes('lastSeen'),false);
});

test('friend-post recipient endpoint rejects caller-selected identities',async()=>{
  const response=await firestoreDocuments(new Request('https://example.test/v1/firestore/friend-post-recipients',{
    method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({uid:'other'})
  }),{DB:db},{uid});
  assert.equal(response.status,400);
});
