import test from 'node:test';
import assert from 'node:assert/strict';
import {checkCommentEpoch} from '../../scripts/loadtest/comment_epoch_smoke.mjs';

test('synthetic SQL smoke checks parity and reports D1 compound limits without credentials',async()=>{
 let calls=0;
 const fake=async(url,options)=>{
  calls++;assert.match(url,/\/d1\/database\//);const body=JSON.parse(options.body);
  assert.ok(body.sql);assert.ok(Array.isArray(body.params));
  if(calls>11)return {ok:false,json:async()=>({errors:[{message:'too many terms in compound SELECT'}]})};
  const candidate=body.sql.includes('quota_comment_candidates_0');
  const size=(body.params.length-1)/2;
  const rows=calls===1?[{n:1}]:Array.from({length:size},(_,slot)=>({slot,n:0,...(candidate?{has_candidates:0}:{})}));
  return {ok:true,json:async()=>({success:true,result:[{success:true,results:rows,meta:{rows_read:10}}]})};
 };
 const output=await checkCommentEpoch(fake,'SYNTHETIC_TOKEN','abcdefghijklmnop');
 assert.equal(calls,15);assert.equal(output.measurements.length,14);assert.equal(output.measurements.filter(x=>x.compound_limit).length,4);
 assert.ok(!JSON.stringify(output).includes('SYNTHETIC_TOKEN'));
 await assert.rejects(checkCommentEpoch(async()=>({ok:false,json:async()=>({})}),'token','abcdefghijklmnop'));
});
