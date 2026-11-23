-- Keep legacy root-document compatibility bridges bounded as imported app collections grow.
-- Partial JSON-expression indexes apply only to the known legacy collections; no app data is
-- copied or modified by this migration.
CREATE INDEX IF NOT EXISTS idx_friendbook_cf_documents_message_chat_id
  ON friendbook_cf_documents(json_extract(document_json, '$.chatId'))
  WHERE collection='messages';

CREATE INDEX IF NOT EXISTS idx_friendbook_cf_documents_comment_post_id
  ON friendbook_cf_documents(json_extract(document_json, '$.postId'))
  WHERE collection='comments';

CREATE INDEX IF NOT EXISTS idx_friendbook_cf_documents_comment_post_id_legacy
  ON friendbook_cf_documents(json_extract(document_json, '$.post_id'))
  WHERE collection='comments';

CREATE INDEX IF NOT EXISTS idx_friendbook_cf_documents_comment_resource
  ON friendbook_cf_documents(json_extract(document_json, '$.resource'))
  WHERE collection='comments';
