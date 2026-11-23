-- Seek active timestamp expiries while retaining nonstandard values for JS.
CREATE INDEX IF NOT EXISTS idx_cf_story_timestamp_expiry_lookup
ON friendbook_cf_firestore_documents((CASE WHEN json_type(document_json,'$.expiresAt') IN ('object','array') THEN
      CASE WHEN json_extract(document_json,'$.expiresAt.__type')='timestamp'
        AND json_type(document_json,'$.expiresAt.millis') IN ('integer','real')
        THEN json_extract(document_json,'$.expiresAt.millis') ELSE 'compat' END END)) WHERE parent_path='stories';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_story_timestamp_expiry_lookup
ON friendbook_cf_documents((CASE WHEN json_type(document_json,'$.fields')='object' THEN
      CASE WHEN json_type(document_json,'$.fields.expiresAt') IN ('object','array') THEN 'compat' END
    ELSE (CASE WHEN json_type(document_json,'$.expiresAt') IN ('object','array') THEN
      CASE WHEN json_extract(document_json,'$.expiresAt.__type')='timestamp'
        AND json_type(document_json,'$.expiresAt.millis') IN ('integer','real')
        THEN json_extract(document_json,'$.expiresAt.millis') ELSE 'compat' END END) END)) WHERE collection='stories';
