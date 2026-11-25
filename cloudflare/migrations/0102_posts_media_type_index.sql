-- Watch and Reels also page video posts through posts.mediaType.
-- Keep the lookup selective without limiting candidates before per-viewer ACLs.
CREATE INDEX IF NOT EXISTS idx_cf_posts_media_type
  ON friendbook_cf_firestore_documents(parent_path, json_extract(document_json,'$.mediaType'), doc_id)
  WHERE parent_path='posts';
