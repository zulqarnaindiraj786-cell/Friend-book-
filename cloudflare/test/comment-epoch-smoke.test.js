import test from 'node:test';
import assert from 'node:assert/strict';
import {checkCommentEpoch} from '../../scripts/loadtest/comment_epoch_smoke.mjs';

test('synthetic SQL smoke checks result parity without exposing credentials',async()=>{
 let calls=0;
 const fake=async(url,options)=>{
  calls++;assert.match(url,/\/d1\/database\//);const body=JSON.parse(options.body);
  assert.ok(body.sql);assert.ok(Array.isArray(body.params));
  const rows=calls===1?[{n:1}]:Array.from({length:10},(_,slot)=>({slot,n:0,...(calls===3?{has_candidates:0}:{})}));
  return {ok:true,json:async()=>({success:true,result:[{success:true,results:rows,meta:{rows_read:10}}]})};
 };
 const output=await checkCommentEpoch(fake,'SYNTHETIC_TOKEN','abcdefghijklmnop');
 assert.equal(calls,3);assert.equal(output.resources,10);assert.ok(!JSON.stringify(output).includes('SYNTHETIC_TOKEN'));
 await assert.rejects(checkCommentEpoch(async()=>({ok:false}),'token','abcdefghijklmnop'));
});
