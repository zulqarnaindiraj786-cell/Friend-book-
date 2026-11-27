-- Replace the old account/status indexes with page-ordered equivalents. This
-- keeps the same index count while letting newest-first cursors stop at LIMIT.
DROP INDEX IF EXISTS idx_cf_request_canonical_in_status;
DROP INDEX IF EXISTS idx_cf_request_canonical_out_status;
CREATE INDEX IF NOT EXISTS idx_cf_request_canonical_in_status ON friendbook_cf_firestore_documents(
  parent_path,
  json_extract(document_json,'$.toUid'),
  json_extract(document_json,'$.status'),
  CASE
    WHEN json_type(document_json,'$.createdAt') IS NULL OR json_type(document_json,'$.createdAt')='null' THEN 0
    WHEN json_type(document_json,'$.createdAt') IN ('true','false') THEN 1
    WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2
    WHEN json_type(document_json,'$.createdAt')='text' THEN 3
    WHEN json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp' THEN 4
    WHEN json_type(document_json,'$.createdAt')='array' THEN 5 ELSE 6
  END DESC,
  CASE
    WHEN json_type(document_json,'$.createdAt') IN ('true','false','integer','real') THEN CAST(json_extract(document_json,'$.createdAt') AS REAL)
    WHEN json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp' THEN CAST(json_extract(document_json,'$.createdAt.millis') AS REAL)
    ELSE 0
  END DESC,
  COALESCE(CASE WHEN json_type(document_json,'$.createdAt') IN ('text','array','object')
    AND NOT (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp')
    THEN json_extract(document_json,'$.createdAt') END,'') DESC,
  doc_id DESC
) WHERE parent_path='friend_requests';
CREATE INDEX IF NOT EXISTS idx_cf_request_canonical_out_status ON friendbook_cf_firestore_documents(
  parent_path,
  json_extract(document_json,'$.fromUid'),
  json_extract(document_json,'$.status'),
  CASE
    WHEN json_type(document_json,'$.createdAt') IS NULL OR json_type(document_json,'$.createdAt')='null' THEN 0
    WHEN json_type(document_json,'$.createdAt') IN ('true','false') THEN 1
    WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2
    WHEN json_type(document_json,'$.createdAt')='text' THEN 3
    WHEN json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp' THEN 4
    WHEN json_type(document_json,'$.createdAt')='array' THEN 5 ELSE 6
  END DESC,
  CASE
    WHEN json_type(document_json,'$.createdAt') IN ('true','false','integer','real') THEN CAST(json_extract(document_json,'$.createdAt') AS REAL)
    WHEN json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp' THEN CAST(json_extract(document_json,'$.createdAt.millis') AS REAL)
    ELSE 0
  END DESC,
  COALESCE(CASE WHEN json_type(document_json,'$.createdAt') IN ('text','array','object')
    AND NOT (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp')
    THEN json_extract(document_json,'$.createdAt') END,'') DESC,
  doc_id DESC
) WHERE parent_path='friend_requests';
