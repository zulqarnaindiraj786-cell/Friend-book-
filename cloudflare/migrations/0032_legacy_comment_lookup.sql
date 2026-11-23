-- Index the same plain/wrapped legacy comment keys used by the D1 read bridge.
-- Additive only: existing documents and historical interaction rows stay unchanged.
CREATE INDEX IF NOT EXISTS idx_cf_comment_lookup_post ON friendbook_cf_documents(
  CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.postId.stringValue') ELSE json_extract(document_json,'$.postId') END
) WHERE collection='comments';
CREATE INDEX IF NOT EXISTS idx_cf_comment_lookup_post_legacy ON friendbook_cf_documents(
  CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.post_id.stringValue') ELSE json_extract(document_json,'$.post_id') END
) WHERE collection='comments';
CREATE INDEX IF NOT EXISTS idx_cf_comment_lookup_resource ON friendbook_cf_documents(
  CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.resource.stringValue') ELSE json_extract(document_json,'$.resource') END
) WHERE collection='comments';
