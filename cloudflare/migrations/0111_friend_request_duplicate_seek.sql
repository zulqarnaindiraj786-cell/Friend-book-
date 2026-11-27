-- The transactional duplicate guard must cover both canonical app documents
-- and Firestore-wrapped legacy request envelopes. Existing single-field indexes
-- do not satisfy the guard's COALESCE expressions and can devolve into a scan.
CREATE INDEX IF NOT EXISTS idx_cf_request_duplicate_pair_status
ON friendbook_cf_firestore_documents(
  COALESCE(json_extract(document_json,'$.fromUid'),json_extract(document_json,'$.fields.fromUid.stringValue')),
  COALESCE(json_extract(document_json,'$.toUid'),json_extract(document_json,'$.fields.toUid.stringValue')),
  COALESCE(json_extract(document_json,'$.status'),json_extract(document_json,'$.fields.status.stringValue'))
) WHERE parent_path='friend_requests';
