-- Restrict owner-private payment review reads before applying the normal ACL.
-- The legacy bucket 1 retains unusual Firestore stringValue coercions.
CREATE INDEX IF NOT EXISTS idx_cf_payments_user ON friendbook_cf_firestore_documents(
  json_extract(document_json,'$.userId')
) WHERE parent_path='payments';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_payments_user ON friendbook_cf_documents(
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN CASE
    WHEN json_type(document_json,'$.fields.userId')='text' THEN json_extract(document_json,'$.fields.userId')
    WHEN json_type(document_json,'$.fields.userId.stringValue')='text' THEN json_extract(document_json,'$.fields.userId.stringValue')
    WHEN json_type(document_json,'$.fields.userId.stringValue') IS NOT NULL THEN 1 END
    ELSE json_extract(document_json,'$.userId') END)
) WHERE collection='payments';
