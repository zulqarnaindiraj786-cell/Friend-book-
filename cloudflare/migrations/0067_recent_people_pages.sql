-- One ordered index per store also supports seeking the exceptional rank.
CREATE INDEX IF NOT EXISTS idx_cf_recent_people ON friendbook_cf_firestore_documents((CASE WHEN doc_id GLOB '*[^ -~]*' OR path<>'users/'||doc_id THEN -1
    WHEN json_type(document_json,'$.createdAt') IS NULL OR json_type(document_json,'$.createdAt')='null' THEN 0
    WHEN json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991 THEN 2
    WHEN json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991 THEN 4
    ELSE -1 END),(COALESCE(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt')
    ELSE json_extract(document_json,'$.createdAt.millis') END,0)),doc_id) WHERE parent_path='users';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_recent_people ON friendbook_cf_documents((CASE WHEN json_type(document_json,'$.fields')='object' THEN -1 ELSE (CASE WHEN doc_id GLOB '*[^ -~]*' OR path<>'users/'||doc_id THEN -1
    WHEN json_type(document_json,'$.createdAt') IS NULL OR json_type(document_json,'$.createdAt')='null' THEN 0
    WHEN json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991 THEN 2
    WHEN json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991 THEN 4
    ELSE -1 END) END),(COALESCE(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt')
    ELSE json_extract(document_json,'$.createdAt.millis') END,0)),doc_id) WHERE collection='users';
