-- Bound the atomic orphan-message adoption check to the room being created.
-- Only historical root messages are indexed; ordinary nested chat messages do
-- not incur these index-maintenance writes. Existing legacy plain-chatId and
-- chatId index is reused. Composite prefixes let SQLite seek both the root
-- and ID range instead of picking a broad collection-only index.
CREATE INDEX IF NOT EXISTS idx_cf_root_message_doc_id
  ON friendbook_cf_firestore_documents(parent_path,doc_id) WHERE parent_path='messages';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_root_message_doc_id
  ON friendbook_cf_documents(collection,doc_id) WHERE collection='messages';
CREATE INDEX IF NOT EXISTS idx_cf_root_message_chat_id
  ON friendbook_cf_firestore_documents(json_extract(document_json,'$.chatId')) WHERE parent_path='messages';
CREATE INDEX IF NOT EXISTS idx_cf_root_message_typed_chat_id
  ON friendbook_cf_firestore_documents(json_extract(document_json,'$.fields.chatId.stringValue')) WHERE parent_path='messages';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_message_typed_chat_id
  ON friendbook_cf_documents(json_extract(document_json,'$.fields.chatId.stringValue')) WHERE collection='messages';
