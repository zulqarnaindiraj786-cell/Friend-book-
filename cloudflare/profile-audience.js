/** Profile-only aggregate: count a person once across follows, pending incoming requests and verified friends. */
export async function profileAudience(db, profileId, viewerId, followers) {
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
  ) SELECT COUNT(*) AS total, COALESCE(MAX(uid=?3),0) AS viewerIncluded
    FROM audience WHERE typeof(uid)='text' AND uid<>'' AND uid<>?2`)
    .bind(JSON.stringify(Array.isArray(followers)?followers:[]),profileId,viewerId).first();
  return {profileAudienceCount:Number(result?.total)||0, profileAudienceIncludesViewer:Number(result?.viewerIncluded)===1};
}
