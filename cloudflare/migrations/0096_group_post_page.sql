-- Group-only post paging index. It does not index ordinary non-group posts.
CREATE INDEX IF NOT EXISTS idx_cf_group_posts_page ON friendbook_cf_firestore_documents(
  json_extract(document_json,'$.groupId'),
  (CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END),
  (CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END), doc_id
) WHERE parent_path='posts' AND json_type(document_json,'$.groupId')='text';

-- Only group posts with incompatible IDs/timestamps need this exception probe.
CREATE INDEX IF NOT EXISTS idx_cf_group_posts_exceptions ON friendbook_cf_firestore_documents(
  json_extract(document_json,'$.groupId'), doc_id
) WHERE parent_path='posts' AND json_type(document_json,'$.groupId')='text'
  AND (doc_id GLOB '*[^ -~]*' OR path<>'posts/'||doc_id OR COALESCE(((json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
    OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)),0)=0);

-- Moderator pending list should not scan every approved post in a busy group.
CREATE INDEX IF NOT EXISTS idx_cf_group_pending_page ON friendbook_cf_firestore_documents(
  json_extract(document_json,'$.groupId'),
  (CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END),
  (CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END), doc_id
) WHERE parent_path='posts' AND json_type(document_json,'$.groupId')='text'
  AND json_extract(document_json,'$.groupApprovalStatus')='pending';
