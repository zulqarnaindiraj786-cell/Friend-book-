-- The common head consists of valid timestamp posts (rank 4). Other timestamp,
-- array, or map records can compete with that head and must trigger the exact
-- evaluator. Text/numeric/missing dates have lower rank and need not be read
-- unless the timestamp head cannot fill the visible page.
CREATE INDEX IF NOT EXISTS idx_cf_feed_high_rank_exceptions
ON friendbook_cf_firestore_documents(doc_id)
WHERE parent_path='posts' AND json_type(document_json,'$.createdAt') IN ('object','array')
  AND COALESCE((json_type(document_json,'$.createdAt')='object'
    AND json_extract(document_json,'$.createdAt.__type')='timestamp'
    AND json_type(document_json,'$.createdAt.millis')='integer'
    AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991
    AND doc_id<>'' AND length(doc_id)<=512 AND instr(doc_id,char(0))=0
    AND doc_id NOT GLOB '*[^a-zA-Z0-9_-]*' AND path='posts/'||doc_id),0)=0;
