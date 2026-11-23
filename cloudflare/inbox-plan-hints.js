// Performance hints ONLY. Never cache membership, documents or authorization.
// A stale hint can choose a slower query, never authorize or hide a room.
const databases=new WeakMap();
const TTL=10*60*1000,MAX_ACTORS=512;
function entries(db){let map=databases.get(db);if(!map){map=new Map();databases.set(db,map);}return map;}
export function recordInboxSortCost(db,uid,meta,now=Date.now()) {
  const rows=meta?.rows_read;
  if(!Number.isSafeInteger(rows)||rows<0)return;
  const map=entries(db),old=map.get(uid);
  if(old?.blockedUntil>now)return;
  map.delete(uid);map.set(uid,{cost:rows,expires:now+TTL,blockedUntil:0});
  if(map.size>MAX_ACTORS)map.delete(map.keys().next().value);
}
export function inboxSeekSize(db,uid,limit,now=Date.now()) {
  const map=entries(db),hint=map.get(uid);
  if(!hint)return 0;
  if(hint.expires<=now){map.delete(uid);return 0;}
  if(hint.blockedUntil>now || hint.cost<64)return 0;
  // Budget at most four potential index/table/membership/ACL reads per
  // candidate against the last measured sort cost. Never issue a head that
  // cannot fill the requested page (except naturally exhausted streams).
  const size=Math.min(64,Math.floor(hint.cost/4));
  return size>=limit?size:0;
}
export function tryInboxSeek(db,uid,now=Date.now()) {
  return inboxSeekSize(db,uid,1,now)>0;
}
export function suspendInboxSeek(db,uid,now=Date.now()) {
  const map=entries(db);map.delete(uid);
  map.set(uid,{cost:0,expires:now+TTL,blockedUntil:now+TTL});
  if(map.size>MAX_ACTORS)map.delete(map.keys().next().value);
}
