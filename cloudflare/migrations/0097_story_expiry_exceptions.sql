-- Only unusual object/array expiries or malformed IDs need a compatibility merge.
-- Ordinary timestamp stories are not added to this index.
CREATE INDEX IF NOT EXISTS idx_cf_story_expiry_exceptions ON friendbook_cf_firestore_documents(doc_id)
WHERE parent_path='stories' AND json_type(document_json,'$.expiresAt') IN ('object','array')
  AND COALESCE((json_type(document_json,'$.expiresAt')='object'
    AND json_extract(document_json,'$.expiresAt.__type')='timestamp'
    AND json_type(document_json,'$.expiresAt.millis')='integer'
    AND json_extract(document_json,'$.expiresAt.millis') BETWEEN -9007199254740991 AND 9007199254740991
    AND doc_id<>'' AND length(doc_id)<=512 AND doc_id NOT GLOB '*[^a-zA-Z0-9_-]*' AND path='stories/'||doc_id),0)=0;

-- doc_id is the evaluator's tie breaker; the old expiry-only index requires
-- sorting every story with the same expiry before it can return the first page.
CREATE INDEX IF NOT EXISTS idx_cf_story_expiry_page ON friendbook_cf_firestore_documents(
  (CASE WHEN json_type(document_json,'$.expiresAt') IN ('object','array') THEN
    CASE WHEN json_extract(document_json,'$.expiresAt.__type')='timestamp'
      AND json_type(document_json,'$.expiresAt.millis') IN ('integer','real')
      THEN json_extract(document_json,'$.expiresAt.millis') ELSE 'compat' END END), doc_id
) WHERE parent_path='stories';
