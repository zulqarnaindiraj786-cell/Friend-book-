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


test('parallel message ACLs share room reads only within one request and isolate rooms',async()=>{
  let reads=0,participants=['alice','bob'];
  const db={prepare(){return {bind(path){return {first:async()=>{reads++;await Promise.resolve();return path==='chats/missing'?null:{document_json:JSON.stringify({participants})};}};}};}};
  const withinRequest=new Map();
  const allowed=await Promise.all(Array.from({length:20},(_,i)=>chatReadAllowed(db,'messages/m'+i,'alice',{chatId:'room'},withinRequest)));
  assert.ok(allowed.every(Boolean));assert.equal(reads,1);
  assert.equal(await chatReadAllowed(db,'messages/other','alice',{chatId:'missing'},withinRequest),false);
  assert.equal(reads,2,'different room never shares membership');
  participants=['bob'];
  assert.equal(await chatReadAllowed(db,'messages/next','alice',{chatId:'room'},new Map()),false);
  assert.equal(reads,3,'next request sees revocation');
});

test('coalesced room errors remain errors and a new request retries storage',async()=>{
  let reads=0;
  const db={prepare(){return {bind(){return {first:async()=>{reads++;throw Error('storage unavailable');}};}};}};
  const withinRequest=new Map();
  const outcomes=await Promise.allSettled(Array.from({length:8},()=>chatReadAllowed(db,'messages/m','alice',{chatId:'room'},withinRequest)));
  assert.ok(outcomes.every(result=>result.status==='rejected'));
  assert.equal(reads,1);
  await assert.rejects(chatReadAllowed(db,'messages/m','alice',{chatId:'room'},new Map()),/storage unavailable/);
  assert.equal(reads,2);
});
