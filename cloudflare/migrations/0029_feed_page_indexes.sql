-- Additive bounded feed order and exceptional-record guard indexes.
CREATE INDEX IF NOT EXISTS idx_cf_posts_feed_page ON friendbook_cf_firestore_documents(parent_path,(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END),(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END),doc_id) WHERE parent_path='posts';
CREATE INDEX IF NOT EXISTS idx_cf_posts_feed_nonstandard ON friendbook_cf_firestore_documents(parent_path) WHERE parent_path='posts' AND (doc_id GLOB '*[^ -~]*' OR path<>'posts/'||doc_id OR COALESCE(((json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
    OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)),0)=0);
CREATE INDEX IF NOT EXISTS idx_cf_legacy_posts_feed_page ON friendbook_cf_documents(collection,(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END),(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END),doc_id) WHERE collection='posts';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_posts_feed_nonstandard ON friendbook_cf_documents(collection) WHERE collection='posts' AND (doc_id GLOB '*[^ -~]*' OR path<>'posts/'||doc_id OR json_type(document_json,'$.fields')='object' OR COALESCE(((json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
    OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)),0)=0);
-- Include fractional timestamps in the fallback guard: JS normalizes them to integer milliseconds.
CREATE INDEX IF NOT EXISTS idx_cf_chat_nonstandard_order_v2
  ON friendbook_cf_firestore_documents(parent_path)
  WHERE doc_id GLOB '*[^ -~]*' OR COALESCE((
    (json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
    OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)
  ),0)=0;
