-- Keep root app documents visible to both the Firestore-compatible D1 facade and
-- the feed/engagement source used by CloudflareStore and D1 ACL checks.
-- This is additive and preserves a newer app-document row on path conflicts.
INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
SELECT path, collection_id, doc_id, COALESCE(owner_uid, ''), document_json, update_time
FROM friendbook_cf_firestore_documents
WHERE parent_path = collection_id
ON CONFLICT(path) DO UPDATE SET
  collection = excluded.collection,
  doc_id = excluded.doc_id,
  owner_uid = excluded.owner_uid,
  document_json = excluded.document_json,
  updated_at = excluded.updated_at
WHERE excluded.updated_at >= friendbook_cf_documents.updated_at;

-- New root posts/reels written through the D1 Firestore-compatible facade are
-- authorized from their mirrored D1 documents. Preserve any explicit staging state.
INSERT INTO friendbook_cf_interaction_resources(resource, state)
SELECT path, 'verified'
FROM friendbook_cf_documents
WHERE collection IN ('posts', 'reels')
ON CONFLICT(resource) DO NOTHING;
