import {test} from 'node:test';
import assert from 'node:assert/strict';
import {batchEngagementReads, engagementSummary} from '../interactions.js';

const resources=Array.from({length:10},(_,i)=>`posts/p${i}`);

test('ten Home cards share one batch and reuse the returned counts and viewer reaction',async()=>{
  let calls=0;
  const db={
    prepare(sql){return {bind(...args){return {sql,args,first(){throw Error('unexpected individual read');}};}};},
    async batch(statements){
      calls++;
      assert.equal(statements.length,11);
      return statements.map(({args,sql},i)=>{
        if(i===10) {
          assert.equal(args.length,21);
          assert.equal(args[0],'["blocked"]');
          assert.equal((sql.match(/COUNT\(\*\)/g)||[]).length,10);
          // Do not depend on a database preserving compound-result ordering.
          return {results:resources.map((_,slot)=>({slot,n:0})).reverse()};
        }
        assert.equal(args[0],'viewer');
        assert.equal(args[1],resources[i]);
        assert.equal(args[2],'["blocked"]');
        return {results:[{types_json:'[{"type":"like","count":3,"mine":1}]',total_comments:i*2,preview_json:'[]'}]};
      });
    },
  };
  const rows=await batchEngagementReads(db,resources,'viewer',['blocked']);
  for(const [i,resource] of resources.entries()){
    const summary=await engagementSummary(db,resource,'viewer','author',['blocked'],rows.get(resource));
    assert.equal(summary.comments,i*2);
    assert.deepEqual(summary.types,[{type:'like',count:3,mine:1}]);
  }
  assert.equal(calls,1);
});

test('an unavailable or incomplete batch leaves existing per-post reads in charge',async()=>{
  const db={prepare(){return {bind(){return {};}};},async batch(){throw Error('unavailable');}};
  assert.equal((await batchEngagementReads(db,resources,'viewer',[])).size,0);
  const incomplete={...db,async batch(){return [...resources.map(()=>({results:[{types_json:'[]'}]})),{results:[{slot:0,n:0}]}];}};
  assert.equal((await batchEngagementReads(incomplete,resources,'viewer',[])).size,0);
  const duplicate={...db,async batch(){return [...resources.map(()=>({results:[{types_json:'[]'}]})),{results:resources.map(()=>({slot:0,n:0}))}];}};
  assert.equal((await batchEngagementReads(duplicate,resources,'viewer',[])).size,0);
});
