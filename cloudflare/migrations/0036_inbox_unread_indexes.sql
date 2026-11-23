-- Unread badge queries use both recipient and unread state. Keep the older
-- recipient-only indexes for historical and non-badge inbox reads.
CREATE INDEX IF NOT EXISTS idx_cf_inbox_unread
  ON friendbook_cf_firestore_documents(json_extract(document_json,'$.toUserId'))
  WHERE parent_path='notifications' AND json_type(document_json,'$.isRead')='false';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_inbox_unread
  ON friendbook_cf_documents((CASE WHEN json_type(document_json,'$.fields')='object'
    THEN json_extract(document_json,'$.fields.toUserId.stringValue')
    ELSE json_extract(document_json,'$.toUserId') END))
  WHERE collection='notifications' AND (CASE WHEN json_type(document_json,'$.fields')='object'
    THEN json_type(document_json,'$.fields.isRead.booleanValue')
    ELSE json_type(document_json,'$.isRead') END)='false';
