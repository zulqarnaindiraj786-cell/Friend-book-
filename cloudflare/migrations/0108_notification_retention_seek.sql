-- Support bounded scheduled notification retention without sorting/scanning the
-- entire document mirror. Only canonical numeric timestamps are covered; older
-- legacy/wrapped shapes remain untouched until a separately reviewed migration.
CREATE INDEX IF NOT EXISTS idx_cf_notification_retention
ON friendbook_cf_firestore_documents (
  CASE
    WHEN json_type(document_json,'$.createdAt') IN ('integer','real')
      THEN json_extract(document_json,'$.createdAt')
    WHEN json_type(document_json,'$.createdAt')='object'
      AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis') IN ('integer','real')
      AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991
      THEN json_extract(document_json,'$.createdAt.millis')
  END,
  doc_id
)
WHERE parent_path='notifications'
  AND (json_type(document_json,'$.createdAt') IN ('integer','real')
    OR (json_type(document_json,'$.createdAt')='object'
      AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis') IN ('integer','real')
      AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991));
