-- Retention is split by read state so SQLite can seek both the state and the
-- expiry timestamp. The preceding index begins with createdAt alone and can
-- scan thousands of fresh rows to find a handful of expired notifications.
-- Keep the older index for compatibility; do not drop indexes in this rollout.
CREATE INDEX IF NOT EXISTS idx_cf_notification_retention_state
ON friendbook_cf_firestore_documents (
  json_type(document_json,'$.isRead'),
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
  AND json_type(document_json,'$.isRead') IN ('true','false')
  AND (json_type(document_json,'$.createdAt') IN ('integer','real')
    OR (json_type(document_json,'$.createdAt')='object'
      AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis') IN ('integer','real')
      AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991));
