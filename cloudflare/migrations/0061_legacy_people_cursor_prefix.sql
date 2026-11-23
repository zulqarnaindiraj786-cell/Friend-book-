-- Legacy profile names can be plain text or Firestore value envelopes.
-- Bucket unusual stringValue coercions separately for exact JS comparison.
CREATE INDEX IF NOT EXISTS idx_cf_legacy_people_cursor_prefix ON friendbook_cf_documents(
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN CASE
    WHEN json_type(document_json,'$.fields.displayName')='text' THEN json_extract(document_json,'$.fields.displayName')
    WHEN json_type(document_json,'$.fields.displayName.stringValue')='text' THEN json_extract(document_json,'$.fields.displayName.stringValue')
    WHEN json_type(document_json,'$.fields.displayName.stringValue') IS NOT NULL THEN 1 END
    ELSE json_extract(document_json,'$.displayName') END)
) WHERE collection='users';
