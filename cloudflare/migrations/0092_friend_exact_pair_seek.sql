-- Exact relationship probes must seek both participants, not scan the actor's
-- complete graph. Existing one-position indexes stay for lists/guard queries.
-- One additive, friends-only index; no rows/triggers/constraints are removed.
CREATE INDEX IF NOT EXISTS idx_cf_friend_exact_pair
ON friendbook_cf_firestore_documents(
  json_extract(document_json,'$.participants[0]'),
  json_extract(document_json,'$.participants[1]')
) WHERE parent_path='friends';
