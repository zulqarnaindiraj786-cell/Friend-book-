import test from 'node:test';
import assert from 'node:assert/strict';
import { loadChat, chatReadAllowed } from '../chat-access.js';

test('missing canonical chat never falls back to a stale mirror or incurs a second lookup', async () => {
  const queries=[];
  const db={prepare(sql){queries.push(sql);assert.ok(sql.includes('friendbook_cf_firestore_documents'));
    return {bind(path){assert.equal(path,'chats/room');return {first:async()=>null};}};}};
  assert.equal(await loadChat(db,'room'),null);
  assert.equal(queries.length,1);
  queries.length=0;
  assert.equal(await chatReadAllowed(db,'chats/room/messages/message','alice'),false);
  assert.equal(queries.length,1);
});

test('canonical participants authorize each fresh request without cross-request caching', async () => {
  let participants=['alice','bob'],reads=0;
  const db={prepare(){return {bind(){return {first:async()=>{reads++;return {document_json:JSON.stringify({participants})};}};}};}};
  assert.equal(await chatReadAllowed(db,'chats/room','alice'),true);
  participants=['bob'];
  assert.equal(await chatReadAllowed(db,'chats/room','alice'),false);
  assert.equal(reads,2);
});
