-- Additive indexes only: speed up atomic duplicate checks without changing historical records.
CREATE INDEX IF NOT EXISTS idx_friendbook_pending_request_canonical
  ON friendbook_cf_firestore_documents(
    COALESCE(json_extract(document_json,'$.fromUid'),json_extract(document_json,'$.fields.fromUid.stringValue')),
    COALESCE(json_extract(document_json,'$.toUid'),json_extract(document_json,'$.fields.toUid.stringValue')),
    COALESCE(json_extract(document_json,'$.status'),json_extract(document_json,'$.fields.status.stringValue'))
  ) WHERE parent_path='friend_requests';
CREATE INDEX IF NOT EXISTS idx_friendbook_pending_request_app
  ON friendbook_cf_documents(
    COALESCE(json_extract(document_json,'$.fromUid'),json_extract(document_json,'$.fields.fromUid.stringValue')),
    COALESCE(json_extract(document_json,'$.toUid'),json_extract(document_json,'$.fields.toUid.stringValue')),
    COALESCE(json_extract(document_json,'$.status'),json_extract(document_json,'$.fields.status.stringValue'))
  ) WHERE collection='friend_requests';
