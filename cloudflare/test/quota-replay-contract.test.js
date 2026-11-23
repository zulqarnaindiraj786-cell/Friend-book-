import test from 'node:test';
import assert from 'node:assert/strict';
import { READ_ONLY_WORKLOADS, assertReadOnlyWorkloads } from '../../scripts/loadtest/quota_replay_contract.mjs';

function clean() {
  return {workloads:Object.fromEntries(READ_ONLY_WORKLOADS.map(name=>[name,
    {requests:5,changedRowsIncludingTriggers:0}]))};
}
test('read-only replay contract covers feed/chat-adjacent/profile/notification reads without allowing trigger writes',()=>{
  assert.doesNotThrow(()=>assertReadOnlyWorkloads(clean()));
  for (const name of READ_ONLY_WORKLOADS) {
    const report=clean();
    report.workloads[name].changedRowsIncludingTriggers=1;
    assert.throws(()=>assertReadOnlyWorkloads(report),/Read workload mutated data/);
  }
});
test('missing, unexecuted or malformed workload cannot silently pass the replay contract',()=>{
  for (const value of [undefined,{requests:0,changedRowsIncludingTriggers:0},
    {requests:true,changedRowsIncludingTriggers:0},{requests:1,changedRowsIncludingTriggers:'0'},
    {requests:1}]) {
    const report=clean();report.workloads[READ_ONLY_WORKLOADS[0]]=value;
    assert.throws(()=>assertReadOnlyWorkloads(report));
  }
});
test('real mutations are not incorrectly prohibited by read-only contracts',()=>{
  const report=clean();
  report.workloads.sendMessageAndUpdateRoom={requests:5,changedRowsIncludingTriggers:10};
  report.workloads.firstComment={requests:5,changedRowsIncludingTriggers:15};
  assert.doesNotThrow(()=>assertReadOnlyWorkloads(report));
});
