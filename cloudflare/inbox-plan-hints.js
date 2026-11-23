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
export function tryInboxSeek(db,uid,now=Date.now()) {
  const map=entries(db),hint=map.get(uid);
  if(!hint)return false;
  if(hint.expires<=now){map.delete(uid);return false;}
  return hint.blockedUntil<=now && hint.cost>=256;
}
export function suspendInboxSeek(db,uid,now=Date.now()) {
  const map=entries(db);map.delete(uid);
  map.set(uid,{cost:0,expires:now+TTL,blockedUntil:now+TTL});
  if(map.size>MAX_ACTORS)map.delete(map.keys().next().value);
}
