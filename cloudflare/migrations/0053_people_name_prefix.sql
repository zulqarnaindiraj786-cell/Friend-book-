-- Keep typed PK/people prefix searches off the full users collection.
CREATE INDEX IF NOT EXISTS idx_cf_users_display_name
ON friendbook_cf_firestore_documents(json_extract(document_json,'$.displayName'))
WHERE parent_path='users';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_users_display_name
ON friendbook_cf_documents((CASE WHEN json_type(document_json,'$.fields')='object'
  THEN json_extract(document_json,'$.fields.displayName.stringValue') ELSE json_extract(document_json,'$.displayName') END))
WHERE collection='users';
