import test from 'node:test';
import assert from 'node:assert/strict';
import {createWorker} from '../../worker.js';
import {pruneExpiredRealtime} from '../maintenance.js';

const app=createWorker(async token=>token==='valid'?'alice':Promise.reject(Error('bad token')));

test('legacy typing clients see an inert response with zero D1 access and no chat disclosure',async()=>{
 let accesses=0;
 const env={DB:{prepare(){accesses++;throw Error('D1 must not be accessed');}},FIREBASE_PROJECT_ID:'test',TYPING_DISABLED:'true'};
 const call=(path,method='GET',body,token='valid')=>app.fetch(new Request(`https://example.test${path}`,{method,headers:{authorization:`Bearer ${token}`,'content-type':'application/json'},...(body===undefined?{}:{body:typeof body==='string'?body:JSON.stringify(body)})}),env);
 for(const chat of ['known','unknown']) {
   assert.deepEqual(await (await call(`/v1/presence/typing/${chat}`)).json(),{chatId:chat,typing:false});
   assert.deepEqual(await (await call(`/v1/presence/typing/${chat}`,'PUT',{typing:true,sessionId:'old'})).json(),{chatId:chat,typing:false});
   assert.deepEqual(await (await call(`/v1/presence/typing/${chat}`,'PUT',{typing:false})).json(),{chatId:chat,typing:false});
 }
 assert.equal((await call('/v1/presence/typing/known','GET',undefined,'invalid')).status,401);
 assert.equal((await call('/v1/presence/typing/known','POST')).status,405);
 assert.equal((await call('/v1/presence/typing/%2F')).status,400);
 assert.equal((await call('/v1/presence/typing/known?x=1')).status,400);
 assert.equal((await call('/v1/presence/typing/known','PUT',{typing:'true'})).status,400);
 assert.equal((await call('/v1/presence/typing/known','PUT','broken')).status,400);
 assert.equal((await call('/v1/presence/typing/known','PUT',{typing:true,extra:'bad'})).status,400);
 assert.equal(accesses,0);
});

test('disabling both indicators skips scheduled D1 and R2 cleanup',async()=>{
 let accesses=0;
 const db={prepare(){accesses++;throw Error('unexpected D1');}};
 const bucket={get(){accesses++;throw Error('unexpected R2');},put(){accesses++;throw Error('unexpected R2');}};
 await pruneExpiredRealtime(db,Date.now(),bucket,true,true);
 assert.equal(accesses,0);
});
