-- Bounded existence probes for visible people: malformed legacy arrays cannot
-- consume the indexed candidate window. No document or trigger is changed.
CREATE INDEX IF NOT EXISTS idx_cf_friend_valid_pair
ON friendbook_cf_firestore_documents(
  json_extract(document_json,'$.participants[0]'),
  json_extract(document_json,'$.participants[1]')
) WHERE parent_path='friends'
  AND json_type(document_json,'$.participants')='array'
  AND json_array_length(document_json,'$.participants')=2;
