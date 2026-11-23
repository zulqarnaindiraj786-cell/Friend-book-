-- Scope index maintenance to live-chat messages, not ordinary chat or Feed.
CREATE INDEX IF NOT EXISTS idx_cf_live_chat_page
ON friendbook_cf_firestore_documents(parent_path,json_extract(document_json,'$.createdAtMs'),doc_id)
WHERE parent_path GLOB 'lives/*/chat';
CREATE INDEX IF NOT EXISTS idx_cf_live_chat_page_exceptions
ON friendbook_cf_firestore_documents(parent_path)
WHERE parent_path GLOB 'lives/*/chat' AND (
  doc_id GLOB '*[^ -~]*' OR COALESCE((
    json_type(document_json,'$.createdAtMs') IN ('integer','real')
    AND json_extract(document_json,'$.createdAtMs') BETWEEN -9007199254740991 AND 9007199254740991
  ),0)=0
);
