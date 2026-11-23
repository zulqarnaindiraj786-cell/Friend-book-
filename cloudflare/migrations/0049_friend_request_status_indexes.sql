-- Index pending inbox/outbox reads without reading the account's accepted history.
CREATE INDEX IF NOT EXISTS idx_cf_request_canonical_in_status ON friendbook_cf_firestore_documents(
  parent_path,
  json_extract(document_json,'$.toUid'), json_extract(document_json,'$.status'), json_extract(document_json,'$.fromUid'), doc_id
) WHERE parent_path='friend_requests';
CREATE INDEX IF NOT EXISTS idx_cf_request_canonical_out_status ON friendbook_cf_firestore_documents(
  parent_path,
  json_extract(document_json,'$.fromUid'), json_extract(document_json,'$.status'), json_extract(document_json,'$.toUid'), doc_id
) WHERE parent_path='friend_requests';
CREATE INDEX IF NOT EXISTS idx_cf_request_legacy_in_status ON friendbook_cf_documents(
  collection,
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.toUid.stringValue') ELSE json_extract(document_json,'$.toUid') END),
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.status.stringValue') ELSE json_extract(document_json,'$.status') END),
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.fromUid.stringValue') ELSE json_extract(document_json,'$.fromUid') END), doc_id
) WHERE collection='friend_requests';
CREATE INDEX IF NOT EXISTS idx_cf_request_legacy_out_status ON friendbook_cf_documents(
  collection,
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.fromUid.stringValue') ELSE json_extract(document_json,'$.fromUid') END),
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.status.stringValue') ELSE json_extract(document_json,'$.status') END),
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.toUid.stringValue') ELSE json_extract(document_json,'$.toUid') END), doc_id
) WHERE collection='friend_requests';
