-- Additive index: bound legacy message-ID prefix lookups to one conversation.
-- Existing messages remain untouched.
CREATE INDEX IF NOT EXISTS idx_friendbook_cf_documents_message_doc_id
  ON friendbook_cf_documents(doc_id) WHERE collection='messages';
