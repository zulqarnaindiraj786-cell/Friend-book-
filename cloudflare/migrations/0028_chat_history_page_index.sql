-- Ordered native chat history pages. Legacy/mixed formats retain the compatibility path.
CREATE INDEX IF NOT EXISTS idx_cf_firestore_chat_created_page
  ON friendbook_cf_firestore_documents(parent_path,
    (CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END),
    (CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END),
    doc_id);
-- Guard index: find exceptional records cheaply before using the ordered fast path.
CREATE INDEX IF NOT EXISTS idx_cf_chat_nonstandard_order
  ON friendbook_cf_firestore_documents(parent_path)
  WHERE doc_id GLOB '*[^ -~]*' OR COALESCE((
    (json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
    OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis') IN ('integer','real') AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)
  ),0)=0;
