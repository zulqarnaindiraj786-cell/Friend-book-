-- Periodic Home live-strip checks should not read ended rooms.
CREATE INDEX IF NOT EXISTS idx_cf_lives_status ON friendbook_cf_firestore_documents(json_extract(document_json,'$.status')) WHERE parent_path='lives';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_lives_status ON friendbook_cf_documents(
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN CASE
    WHEN json_type(document_json,'$.fields.status')='text' THEN json_extract(document_json,'$.fields.status')
    WHEN json_type(document_json,'$.fields.status.stringValue')='text' THEN json_extract(document_json,'$.fields.status.stringValue')
    WHEN json_type(document_json,'$.fields.status.stringValue') IS NOT NULL THEN 1 END
    ELSE json_extract(document_json,'$.status') END)
) WHERE collection='lives';
