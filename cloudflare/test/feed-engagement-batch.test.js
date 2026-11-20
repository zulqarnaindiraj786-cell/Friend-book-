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
      assert.equal(statements.length,20);
      return statements.map(({args},i)=>{
        if(i%2)return {results:[{n:0}]};
        assert.equal(args[0],'viewer');
        assert.equal(args[1],resources[i/2]);
        assert.equal(args[2],'["blocked"]');
        return {results:[{types_json:'[{"type":"like","count":3,"mine":1}]',total_comments:i,preview_json:'[]'}]};
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

test('an unavailable batch leaves existing per-post reads in charge',async()=>{
  const db={prepare(){return {bind(){return {};}};},async batch(){throw Error('unavailable');}};
  assert.equal((await batchEngagementReads(db,resources,'viewer',[])).size,0);
});
