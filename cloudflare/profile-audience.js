/** Profile-only audience graph. Cache the graph per profile so different viewers share one D1 scan. */
// The audience is a display-only profile count, not an authorization decision.
// Avoid invalidation from unrelated profile edits. The audience depends on the
// follower list and relationship graph, not the profile document revision.
// Relationship-only changes may lag for this existing 60-second cache window.
const AUDIENCE_CACHE_TTL_MS=60_000;
const AUDIENCE_CACHE_LIMIT=256;
const audienceCaches=new WeakMap();
function audienceCache(db) {
  let cache=audienceCaches.get(db);
  if(!cache){cache=new Map();audienceCaches.set(db,cache);}
  return cache;
}
export async function profileAudience(db, profileId, viewerId, followers, friendsCount=null) {
  const followerList=Array.isArray(followers)?followers:[];
  // Profile revision changes on bio/photo/last-seen edits and needlessly reruns
  // the D1 graph query. Count/follower changes are the profile-side graph inputs.
  const signature=JSON.stringify([friendsCount,followerList]);
  const cache=audienceCache(db), key=profileId;
  const now=Date.now(), cached=cache.get(key);
  let audience;
  if(cached && cached.signature===signature && (cached.promise || cached.expiresAt>now)) {
    cache.delete(key);cache.set(key,cached);
    audience=cached.promise ? await cached.promise : cached.uids;
  } else {
    if(cached)cache.delete(key);
    // Root documents are canonical-only. A mirror-only request must not reappear
    // in audience counts after its canonical document has been removed. Avoid the
    // extra legacy lookup and per-candidate canonical shadow probes entirely.
    const entry={signature,expiresAt:0,promise:null,uids:null};
    entry.promise=(async()=>{
      const result = await db.prepare(`WITH audience(uid) AS (
        SELECT value FROM json_each(?1) WHERE type='text'
        UNION SELECT uid_high FROM friendbook_cf_trusted_friendships WHERE uid_low=?2
        UNION SELECT uid_low FROM friendbook_cf_trusted_friendships WHERE uid_high=?2
        UNION SELECT json_extract(document_json,'$.fromUid') FROM friendbook_cf_firestore_documents
          WHERE parent_path='friend_requests' AND json_extract(document_json,'$.toUid')=?2
            AND json_extract(document_json,'$.status')='pending'
      ) SELECT uid FROM audience WHERE typeof(uid)='text' AND uid<>'' AND uid<>?2`)
        .bind(JSON.stringify(followerList),profileId).all();
      return new Set((result?.results||[]).map(row=>row.uid));
    })();
    cache.set(key,entry);
    if(cache.size>AUDIENCE_CACHE_LIMIT)cache.delete(cache.keys().next().value);
    try {
      audience=await entry.promise;
      entry.uids=audience;entry.expiresAt=Date.now()+AUDIENCE_CACHE_TTL_MS;entry.promise=null;
    } catch(error) {
      if(cache.get(key)===entry)cache.delete(key);
      throw error;
    }
  }
  return {profileAudienceCount:audience.size,profileAudienceIncludesViewer:audience.has(viewerId)};
}
