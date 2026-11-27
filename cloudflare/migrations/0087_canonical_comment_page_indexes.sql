-- Canonical comment routes retain their bounded lookup and cursor keys.
-- Create each superset before dropping its predecessor to preserve route lookup
-- if a migration is interrupted. Recreate the 0032 indexes to undo this change.
-- Replace each legacy comment route index with a superset preserving its
-- first lookup key and adding an exact safe creation-time page key. Unusual
-- historical encodings are kept in the -1 exception bucket.

CREATE INDEX IF NOT EXISTS idx_cf_canonical_comment_page_post ON friendbook_cf_firestore_documents(
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.postId.stringValue') ELSE json_extract(document_json,'$.postId') END),
  (CASE WHEN json_type(document_json,'$.fields') IS NOT 'object'
  AND json_type(document_json,'$.createdAt') IN ('integer','real')
  AND json_extract(document_json,'$.createdAt') BETWEEN 1 AND 9007199254740991
  AND doc_id<>'' AND doc_id NOT GLOB '*[^a-zA-Z0-9_-]*' AND path='comments/'||doc_id
  THEN CAST(json_extract(document_json,'$.createdAt') AS INTEGER) ELSE -1 END),
  doc_id
) WHERE parent_path='comments';


CREATE INDEX IF NOT EXISTS idx_cf_canonical_comment_page_post_legacy ON friendbook_cf_firestore_documents(
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.post_id.stringValue') ELSE json_extract(document_json,'$.post_id') END),
  (CASE WHEN json_type(document_json,'$.fields') IS NOT 'object'
  AND json_type(document_json,'$.createdAt') IN ('integer','real')
  AND json_extract(document_json,'$.createdAt') BETWEEN 1 AND 9007199254740991
  AND doc_id<>'' AND doc_id NOT GLOB '*[^a-zA-Z0-9_-]*' AND path='comments/'||doc_id
  THEN CAST(json_extract(document_json,'$.createdAt') AS INTEGER) ELSE -1 END),
  doc_id
) WHERE parent_path='comments';


CREATE INDEX IF NOT EXISTS idx_cf_canonical_comment_page_resource ON friendbook_cf_firestore_documents(
  (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.resource.stringValue') ELSE json_extract(document_json,'$.resource') END),
  (CASE WHEN json_type(document_json,'$.fields') IS NOT 'object'
  AND json_type(document_json,'$.createdAt') IN ('integer','real')
  AND json_extract(document_json,'$.createdAt') BETWEEN 1 AND 9007199254740991
  AND doc_id<>'' AND doc_id NOT GLOB '*[^a-zA-Z0-9_-]*' AND path='comments/'||doc_id
  THEN CAST(json_extract(document_json,'$.createdAt') AS INTEGER) ELSE -1 END),
  doc_id
) WHERE parent_path='comments';
