// D1 sessions are request-scoped. Keep the usual binding-scoped WeakMaps for
// adapters without sessions, and use bounded isolate-local hints for the single
// production D1 binding. Every hit is validated against its primary epoch.
const scopes=new WeakMap();
const countScopes=new WeakMap();
const MAX_HINTS=1024;
let sharedScopeMap=null;
let sharedCountMap=null;
function isSessionBinding(scope) {
  return !!scope && (typeof scope==='object' || typeof scope==='function')
    && typeof scope.withSession==='function';
}
function scopeMap(scope,create=false) {
  if(!scope || (typeof scope!=='object' && typeof scope!=='function'))return null;
  const shared=isSessionBinding(scope);
  let map=shared?sharedScopeMap:scopes.get(scope);
  if(!map && create){map=new Map();if(shared)sharedScopeMap=map;else scopes.set(scope,map);}
  return map;
}
function countMap(scope,create=false) {
  if(!scope || (typeof scope!=='object' && typeof scope!=='function'))return null;
  const shared=isSessionBinding(scope);
  let map=shared?sharedCountMap:countScopes.get(scope);
  if(!map && create){map=new Map();if(shared)sharedCountMap=map;else countScopes.set(scope,map);}
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
function countKey(resource,blocked) {
  const actors=[...new Set(Array.isArray(blocked)?blocked:[])].sort();
  return JSON.stringify([resource,actors]);
}
// Counts are reused only for the same resource, blocked-author set and primary
// epoch. The epoch is generated randomly per database and advances on every
// legacy-comment insert, edit, move or delete.
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
