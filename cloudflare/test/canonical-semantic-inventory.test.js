import test from 'node:test';
import assert from 'node:assert/strict';
import {equalDocuments,mismatchFieldLabels} from '../../scripts/loadtest/canonical_semantic_inventory.mjs';

test('parity checker uses the Worker document conversion for Firestore envelopes',()=>{
  const canonical=JSON.stringify({fields:{name:{stringValue:'private'},score:{integerValue:'4'}},createTime:'2024-01-01T00:00:00Z'});
  const legacy=JSON.stringify({id:'u1',name:'private',score:4,createdAt:1704067200000});
  assert.equal(equalDocuments(legacy,'u1',canonical,'u1'),true);
  assert.deepEqual(mismatchFieldLabels(legacy,'u1',canonical,'u1'),[]);
});

test('parity mismatch output includes fixed safe labels only, never user values or arbitrary keys',()=>{
  const left=JSON.stringify({id:'u1',blockedUsers:['secret-user'],customPrivateAttribute:'secret-value'});
  const right=JSON.stringify({id:'u1',blockedUsers:[],customOtherAttribute:'different-secret'});
  assert.equal(equalDocuments(left,'u1',right,'u1'),false);
  assert.deepEqual(mismatchFieldLabels(left,'u1',right,'u1').sort(),['_other','_other','blockedUsers']);
});
