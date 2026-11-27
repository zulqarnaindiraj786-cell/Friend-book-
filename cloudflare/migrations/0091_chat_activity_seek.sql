-- One shared canonical-room index: no per-member activity rows or triggers.
-- Existing timestamp and membership indexes remain intact.
CREATE INDEX IF NOT EXISTS idx_cf_chat_activity_seek
ON friendbook_cf_firestore_documents((COALESCE(NULLIF((CASE
  WHEN json_type(document_json,'$.lastMessageTime') IN ('integer','real')
    AND abs(CAST(json_extract(document_json,'$.lastMessageTime') AS REAL))<=1.7976931348623157e308
    THEN json_extract(document_json,'$.lastMessageTime')
  WHEN json_extract(document_json,'$.lastMessageTime.__type')='timestamp'
    AND json_type(document_json,'$.lastMessageTime.millis') IN ('integer','real')
    AND abs(CAST(json_extract(document_json,'$.lastMessageTime.millis') AS REAL))<=1.7976931348623157e308
    THEN CASE WHEN abs(CAST(json_extract(document_json,'$.lastMessageTime.millis') AS REAL))<9.223372036854776e18
      THEN CAST(json_extract(document_json,'$.lastMessageTime.millis') AS INTEGER)
      ELSE json_extract(document_json,'$.lastMessageTime.millis') END ELSE 0 END),0),NULLIF((CASE
  WHEN json_type(document_json,'$.lastMessageTimeMs') IN ('integer','real')
    AND abs(CAST(json_extract(document_json,'$.lastMessageTimeMs') AS REAL))<=1.7976931348623157e308
    THEN json_extract(document_json,'$.lastMessageTimeMs')
  WHEN json_extract(document_json,'$.lastMessageTimeMs.__type')='timestamp'
    AND json_type(document_json,'$.lastMessageTimeMs.millis') IN ('integer','real')
    AND abs(CAST(json_extract(document_json,'$.lastMessageTimeMs.millis') AS REAL))<=1.7976931348623157e308
    THEN CASE WHEN abs(CAST(json_extract(document_json,'$.lastMessageTimeMs.millis') AS REAL))<9.223372036854776e18
      THEN CAST(json_extract(document_json,'$.lastMessageTimeMs.millis') AS INTEGER)
      ELSE json_extract(document_json,'$.lastMessageTimeMs.millis') END ELSE 0 END),0),NULLIF((CASE
  WHEN json_type(document_json,'$.lastMessageAt') IN ('integer','real')
    AND abs(CAST(json_extract(document_json,'$.lastMessageAt') AS REAL))<=1.7976931348623157e308
    THEN json_extract(document_json,'$.lastMessageAt')
  WHEN json_extract(document_json,'$.lastMessageAt.__type')='timestamp'
    AND json_type(document_json,'$.lastMessageAt.millis') IN ('integer','real')
    AND abs(CAST(json_extract(document_json,'$.lastMessageAt.millis') AS REAL))<=1.7976931348623157e308
    THEN CASE WHEN abs(CAST(json_extract(document_json,'$.lastMessageAt.millis') AS REAL))<9.223372036854776e18
      THEN CAST(json_extract(document_json,'$.lastMessageAt.millis') AS INTEGER)
      ELSE json_extract(document_json,'$.lastMessageAt.millis') END ELSE 0 END),0),0)) DESC,doc_id DESC)
WHERE path='chats/'||doc_id;
