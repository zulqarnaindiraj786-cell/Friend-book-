-- Recipient-scoped reads keep unrelated private inboxes out of the query result.
CREATE INDEX IF NOT EXISTS idx_cf_firestore_inbox_recipient
  ON friendbook_cf_firestore_documents(json_extract(document_json,'$.toUserId'))
  WHERE parent_path='notifications';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_inbox_recipient
  ON friendbook_cf_documents((CASE WHEN json_type(document_json,'$.fields')='object'
    THEN json_extract(document_json,'$.fields.toUserId.stringValue')
    ELSE json_extract(document_json,'$.toUserId') END))
  WHERE collection='notifications';
