-- Mirror the indexed chronological page and exception probes for Reels.
CREATE INDEX IF NOT EXISTS idx_cf_reels_feed_page ON friendbook_cf_firestore_documents(parent_path,(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END),(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END),doc_id) WHERE parent_path='reels';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_reels_feed_page ON friendbook_cf_documents(collection,(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END),(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END),doc_id) WHERE collection='reels';
-- Probe only historical exceptions before using the bounded sort/keyset query.
CREATE INDEX IF NOT EXISTS idx_cf_reels_feed_exceptions ON friendbook_cf_firestore_documents(
  json_extract(document_json,'$.authorId'), doc_id
) WHERE parent_path='reels' AND (doc_id GLOB '*[^ -~]*' OR path<>'reels/'||doc_id OR COALESCE(((json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
    OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)),0)=0);
CREATE INDEX IF NOT EXISTS idx_cf_legacy_reels_feed_exceptions ON friendbook_cf_documents(
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.authorId.stringValue') ELSE json_extract(document_json,'$.authorId') END), doc_id
) WHERE collection='reels' AND (doc_id GLOB '*[^ -~]*' OR path<>'reels/'||doc_id OR json_type(document_json,'$.fields')='object' OR COALESCE(((json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
    OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)),0)=0);
