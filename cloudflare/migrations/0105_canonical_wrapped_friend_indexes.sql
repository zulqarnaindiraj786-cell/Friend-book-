-- Normal Firestore-wrapped two-person friendships deserve participant seeks,
-- not a scan of every exceptional wrapped friendship document.
-- Preserve all unusual/malformed rows in the separate exception index.
CREATE INDEX IF NOT EXISTS idx_cf_friend_wrapped_participant_0
  ON friendbook_cf_firestore_documents(parent_path, json_extract(document_json,'$.fields.participants.arrayValue.values[0].stringValue'))
  WHERE parent_path='friends'
    AND json_array_length(document_json,'$.fields.participants.arrayValue.values')=2
    AND json_type(document_json,'$.fields.participants.arrayValue.values[0].stringValue')='text'
    AND json_type(document_json,'$.fields.participants.arrayValue.values[1].stringValue')='text';
CREATE INDEX IF NOT EXISTS idx_cf_friend_wrapped_participant_1
  ON friendbook_cf_firestore_documents(parent_path, json_extract(document_json,'$.fields.participants.arrayValue.values[1].stringValue'))
  WHERE parent_path='friends'
    AND json_array_length(document_json,'$.fields.participants.arrayValue.values')=2
    AND json_type(document_json,'$.fields.participants.arrayValue.values[0].stringValue')='text'
    AND json_type(document_json,'$.fields.participants.arrayValue.values[1].stringValue')='text';
CREATE INDEX IF NOT EXISTS idx_cf_friend_guard_exceptions_v2
  ON friendbook_cf_firestore_documents(path)
  WHERE parent_path='friends' AND (
    json_type(document_json,'$.participants')='object'
    OR json_array_length(document_json,'$.participants')>2
    OR (json_type(document_json,'$.fields.participants.arrayValue.values') IS NOT NULL AND NOT (
      COALESCE((json_array_length(document_json,'$.fields.participants.arrayValue.values')=2
        AND json_type(document_json,'$.fields.participants.arrayValue.values[0].stringValue')='text'
        AND json_type(document_json,'$.fields.participants.arrayValue.values[1].stringValue')='text'),0)=1
    ))
  );
