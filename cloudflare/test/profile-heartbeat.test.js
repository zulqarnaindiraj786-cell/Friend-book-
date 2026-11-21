import {test} from 'node:test';
import assert from 'node:assert/strict';
import {redundantProfileHeartbeat as skip} from '../firestore-documents.js';
test('only repeated profile heartbeat patches are coalesced',()=>{
 const before={isOnline:true,isActive:true,showActiveStatus:true,lastSeen:{__type:'timestamp',millis:1000},lastSeenMs:1000};
 const next={...before,lastSeenMs:91000};
 assert.equal(skip(before,next,next,91000),true);
 assert.equal(skip(before,next,next,181000),false);
 const offline={...next,isOnline:false,isActive:false};
 assert.equal(skip(before,offline,offline,91000),false);
 const privateState={...next,showActiveStatus:false};
 assert.equal(skip(before,privateState,privateState,91000),false);
 assert.equal(skip(before,next,{...next,displayName:'test'},91000),false);
});
