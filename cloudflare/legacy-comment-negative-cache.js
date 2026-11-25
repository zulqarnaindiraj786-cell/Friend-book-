// Isolate-local hints only. A fresh primary-backed epoch must match before a
// negative candidate hint can be used; no comment data or author is cached.
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
