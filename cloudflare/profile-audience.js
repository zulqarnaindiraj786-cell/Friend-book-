/** Profile-only aggregate: count a person once across follows, pending incoming requests and verified friends. */
export async function profileAudience(db, profileId, viewerId, followers) {
  const from = "CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.fromUid.stringValue') ELSE json_extract(a.document_json,'$.fromUid') END";
  const to = "CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.toUid.stringValue') ELSE json_extract(a.document_json,'$.toUid') END";
  const status = "CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.status.stringValue') ELSE json_extract(a.document_json,'$.status') END";
  const result = await db.prepare(`WITH audience(uid) AS (
    SELECT value FROM json_each(?1) WHERE type='text'
    UNION SELECT uid_high FROM friendbook_cf_trusted_friendships WHERE uid_low=?2
    UNION SELECT uid_low FROM friendbook_cf_trusted_friendships WHERE uid_high=?2
    UNION SELECT json_extract(document_json,'$.fromUid') FROM friendbook_cf_firestore_documents
      WHERE parent_path='friend_requests' AND json_extract(document_json,'$.toUid')=?2
        AND json_extract(document_json,'$.status')='pending'
    UNION SELECT ${from} FROM friendbook_cf_documents a
      WHERE a.collection='friend_requests' AND (${to})=?2 AND (${status})='pending'
        AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)
  ) SELECT COUNT(*) AS total, COALESCE(MAX(uid=?3),0) AS viewerIncluded
    FROM audience WHERE typeof(uid)='text' AND uid<>'' AND uid<>?2`)
    .bind(JSON.stringify(Array.isArray(followers)?followers:[]),profileId,viewerId).first();
  return {profileAudienceCount:Number(result?.total)||0, profileAudienceIncludesViewer:Number(result?.viewerIncluded)===1};
}
