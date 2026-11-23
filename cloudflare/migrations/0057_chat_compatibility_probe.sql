-- Index only exceptional chat records so healthy rooms do not scan their history.
CREATE INDEX IF NOT EXISTS idx_cf_chat_invalid_order
ON friendbook_cf_firestore_documents(parent_path)
WHERE parent_path GLOB 'chats/*/messages' AND (
    doc_id GLOB '*[^ -~]*' OR COALESCE((
      (json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
      OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
        AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)
    ),0)=0);
