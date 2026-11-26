/** Profile-only audience graph. Cache the graph per profile so different viewers share one D1 scan. */
const AUDIENCE_CACHE_TTL_MS=15_000;
const AUDIENCE_CACHE_LIMIT=256;
const audienceCaches=new WeakMap();
function audienceCache(db) {
  let cache=audienceCaches.get(db);
  if(!cache){cache=new Map();audienceCaches.set(db,cache);}
  return cache;
}
export async function profileAudience(db, profileId, viewerId, followers, profileRevision=null) {
  const followerList=Array.isArray(followers)?followers:[];
  const signature=JSON.stringify([profileRevision,followerList]);
  const cache=audienceCache(db), key=profileId;
  const now=Date.now(), cached=cache.get(key);
  let audience;
  if(cached?.expiresAt>now && cached.signature===signature) {
    cache.delete(key);cache.set(key,cached);
    audience=cached.uids;
  } else {
    if(cached)cache.delete(key);
    // Root documents are canonical-only. A mirror-only request must not reappear
    // in audience counts after its canonical document has been removed. Avoid the
    // extra legacy lookup and per-candidate canonical shadow probes entirely.
    const result = await db.prepare(`WITH audience(uid) AS (
      SELECT value FROM json_each(?1) WHERE type='text'
      UNION SELECT uid_high FROM friendbook_cf_trusted_friendships WHERE uid_low=?2
      UNION SELECT uid_low FROM friendbook_cf_trusted_friendships WHERE uid_high=?2
      UNION SELECT json_extract(document_json,'$.fromUid') FROM friendbook_cf_firestore_documents
        WHERE parent_path='friend_requests' AND json_extract(document_json,'$.toUid')=?2
          AND json_extract(document_json,'$.status')='pending'
    ) SELECT uid FROM audience WHERE typeof(uid)='text' AND uid<>'' AND uid<>?2`)
      .bind(JSON.stringify(followerList),profileId).all();
    audience=new Set((result?.results||[]).map(row=>row.uid));
    cache.set(key,{signature,expiresAt:now+AUDIENCE_CACHE_TTL_MS,uids:audience});
    if(cache.size>AUDIENCE_CACHE_LIMIT)cache.delete(cache.keys().next().value);
  }
  return {profileAudienceCount:audience.size,profileAudienceIncludesViewer:audience.has(viewerId)};
}
