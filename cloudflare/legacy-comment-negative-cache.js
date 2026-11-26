// Isolate-local quota hints only. Every cached count is bound to a fresh primary epoch
// and the exact blocked-author set; no comment body or author data is cached.
const scopes=new WeakMap();
const MAX_HINTS=1024;
function scopeMap(scope,create=false) {
  if(!scope || (typeof scope!=='object' && typeof scope!=='function'))return null;
  let map=scopes.get(scope);
  if(!map && create){map=new Map();scopes.set(scope,map);}
  return map;
}
export async function legacyEpoch(db) {
  try {
    const row=await db.prepare('SELECT generation,revision FROM friendbook_cf_legacy_comment_epoch WHERE id=1').bind().first();
    if(!row || typeof row.generation!=='string' || !/^[a-f0-9]{32}$/.test(row.generation)
        || !Number.isSafeInteger(row.revision) || row.revision<0 || row.revision>9007199254740990)return null;
    return `${row.generation}:${row.revision}`;
  } catch { return null; } // Old schema/unavailable guard: never use an unverified hint.
}
export function legacyNoCandidates(scope,resource,epoch) {
  return !!epoch && scopeMap(scope)?.get(resource)===epoch;
}
export function rememberLegacyNoCandidates(scope,resource,epoch) {
  if(!epoch || typeof resource!=='string')return;
  const map=scopeMap(scope,true);if(!map)return;
  map.delete(resource);map.set(resource,epoch);
  while(map.size>MAX_HINTS)map.delete(map.keys().next().value);
}

const countScopes=new WeakMap();
function countMap(scope,create=false) {
  if(!scope || (typeof scope!=='object' && typeof scope!=='function'))return null;
  let map=countScopes.get(scope);
  if(!map && create){map=new Map();countScopes.set(scope,map);}
  return map;
}
function countKey(resource,blocked) {
  const actors=[...new Set(Array.isArray(blocked)?blocked:[])].sort();
  return JSON.stringify([resource,actors]);
}
// Cached counts are safe only for the same database binding, primary-backed
// epoch, resource and blocked-author set. Epoch triggers cover legacy comment
// inserts, edits, moves and deletes. The cache is bounded per binding.
export function legacyCachedCount(scope,resource,blocked,epoch) {
  if(!epoch)return undefined;
  const entry=countMap(scope)?.get(countKey(resource,blocked));
  return entry?.epoch===epoch && Number.isSafeInteger(entry.count) && entry.count>=0 ? entry.count : undefined;
}
export function rememberLegacyCount(scope,resource,blocked,epoch,count) {
  if(!epoch || !Number.isSafeInteger(count) || count<0)return;
  const map=countMap(scope,true),key=countKey(resource,blocked);
  map.delete(key);map.set(key,{epoch,count});
  while(map.size>MAX_HINTS)map.delete(map.keys().next().value);
}
