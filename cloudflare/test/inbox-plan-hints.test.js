import test from 'node:test';
import assert from 'node:assert/strict';
import {recordInboxSortCost,tryInboxSeek,suspendInboxSeek} from '../inbox-plan-hints.js';
test('only measured expensive sorts choose seek; sparse failures cool down and expire',()=>{
  const db={};assert.equal(tryInboxSeek(db,'a',0),false);
  for(const rows_read of [undefined,-1,NaN,Infinity,'500',255]){
    recordInboxSortCost(db,'a',{rows_read},0);assert.equal(tryInboxSeek(db,'a',1),false);
  }
  recordInboxSortCost(db,'a',{rows_read:256},0);assert.equal(tryInboxSeek(db,'a',1),true);
  assert.equal(tryInboxSeek({},'a',1),false);assert.equal(tryInboxSeek(db,'b',1),false);
  suspendInboxSeek(db,'a',2);recordInboxSortCost(db,'a',{rows_read:9000},3);
  assert.equal(tryInboxSeek(db,'a',4),false);assert.equal(tryInboxSeek(db,'a',600003),false);
  recordInboxSortCost(db,'a',{rows_read:400},600004);assert.equal(tryInboxSeek(db,'a',600005),true);
  assert.equal(tryInboxSeek(db,'a',1200004),false);
});
test('performance hints evict old actors instead of retaining unbounded request state',()=>{
  const db={};for(let i=0;i<600;i++)recordInboxSortCost(db,String(i),{rows_read:400},0);
  assert.equal(tryInboxSeek(db,'0',1),false);assert.equal(tryInboxSeek(db,'599',1),true);
});
