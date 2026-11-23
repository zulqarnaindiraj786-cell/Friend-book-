-- Preserve unusual and historical representations for indexed friendship guards.
-- Only exceptional rows enter these indexes; authoritative documents are unchanged.
CREATE INDEX IF NOT EXISTS idx_cf_friend_guard_exceptions ON friendbook_cf_firestore_documents(path)
  WHERE parent_path='friends' AND (
    json_type(document_json,'$.participants')='object'
    OR json_array_length(document_json,'$.participants')>2
    OR json_type(document_json,'$.fields.participants.arrayValue.values') IS NOT NULL
  );
CREATE INDEX IF NOT EXISTS idx_cf_legacy_friend_guard_exceptions ON friendbook_cf_documents(path)
  WHERE collection='friends' AND (
    json_type(document_json,'$.fields')='object'
    OR json_type(document_json,'$.participants')='object'
    OR json_array_length(document_json,'$.participants')>2
  );
