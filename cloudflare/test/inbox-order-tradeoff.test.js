import test from 'node:test';
import assert from 'node:assert/strict';
import {runTradeoff} from '../../scripts/loadtest/inbox_order_tradeoff.mjs';

test('offline ordered inbox candidate proves seek benefit but exposes per-member write amplification',async()=>{
  const report=await runTradeoff(100);
  assert.equal(report.decision,'DO_NOT_DEPLOY_UNCONDITIONAL_MEMBER_FANOUT');
  assert.deepEqual(report.results.map(r=>r.currentCandidateVisits),[10,10]);
  assert.deepEqual(report.results.map(r=>r.orderedCandidateVisits),[10,10]);
  assert.deepEqual(report.results.map(r=>r.additionalLocalChangedRows),[4,100]);
  assert.ok(report.results.every(r=>r.orderedPlan.some(p=>p.includes('USING PRIMARY KEY (uid=?)'))));
});

test('offline inbox experiment refuses invalid fixture sizes',async()=>{
  for(const size of [0,9,10001,10.5,NaN])await assert.rejects(runTradeoff(size));
});
