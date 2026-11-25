// Read-only aggregate probes; never return paths or document data.
import { indexedFriendCandidates, fullFriendCandidates, malformedFriendProbe, friendshipDuplicateGuard } from '../../cloudflare/friendship-candidates.js';
const uid='__quota_probe_sender_7164__',other='__quota_probe_recipient_7164__';
const indexed={sql:indexedFriendCandidates,args:[uid,uid,uid,uid]};
const guard=candidates=>{
  const g=friendshipDuplicateGuard(candidates,uid,other);
  return {sql:`SELECT ${g.sql} AS allowed`,params:g.args};
};
console.log(JSON.stringify([
  {name:'malformed-exception-probe',sql:`SELECT ${malformedFriendProbe} AS dangerous`,params:[]},
  {name:'indexed-candidate-count',sql:`SELECT count(*) AS candidates FROM (${indexedFriendCandidates})`,params:indexed.args},
  {name:'indexed-duplicate-guard',...guard(indexed)},
  {name:'original-duplicate-guard',...guard({sql:fullFriendCandidates,args:[]})}
]));
