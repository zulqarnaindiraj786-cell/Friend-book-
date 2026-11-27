-- Index real D1 data; these are not new sources of truth.
CREATE INDEX IF NOT EXISTS idx_cf_profile_email ON friendbook_cf_firestore_documents(json_extract(document_json,'$.email')) WHERE parent_path='users';
CREATE INDEX IF NOT EXISTS idx_cf_profile_username ON friendbook_cf_firestore_documents(json_extract(document_json,'$.username')) WHERE parent_path='users';
CREATE INDEX IF NOT EXISTS idx_cf_profile_phone ON friendbook_cf_firestore_documents(COALESCE(json_extract(document_json,'$.phoneNumber'),json_extract(document_json,'$.phone'))) WHERE parent_path='users';
CREATE INDEX IF NOT EXISTS idx_cf_profile_created ON friendbook_cf_firestore_documents(create_time DESC,doc_id) WHERE parent_path='users';
CREATE INDEX IF NOT EXISTS idx_cf_comment_reply_parent ON friendbook_cf_comments(resource,parent_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_cf_comment_author ON friendbook_cf_comments(uid,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cf_group_post_lookup ON friendbook_cf_firestore_documents(json_extract(document_json,'$.groupId'),create_time DESC,doc_id) WHERE parent_path='posts';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_group_post_lookup ON friendbook_cf_documents((CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.groupId.stringValue') ELSE json_extract(document_json,'$.groupId') END)) WHERE collection='posts';
CREATE INDEX IF NOT EXISTS idx_cf_story_author ON friendbook_cf_firestore_documents(json_extract(document_json,'$.authorId'),create_time DESC,doc_id) WHERE parent_path='stories';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_story_author ON friendbook_cf_documents((CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.authorId.stringValue') ELSE json_extract(document_json,'$.authorId') END)) WHERE collection='stories';
CREATE INDEX IF NOT EXISTS idx_cf_story_expiry ON friendbook_cf_firestore_documents(COALESCE(json_extract(document_json,'$.expiresAt.millis'),json_extract(document_json,'$.expiresAt'))) WHERE parent_path='stories';
CREATE INDEX IF NOT EXISTS idx_cf_reel_created ON friendbook_cf_firestore_documents(create_time DESC,doc_id) WHERE parent_path='reels';
CREATE INDEX IF NOT EXISTS idx_cf_reel_author_created ON friendbook_cf_firestore_documents(json_extract(document_json,'$.authorId'),create_time DESC,doc_id) WHERE parent_path='reels';
CREATE INDEX IF NOT EXISTS idx_cf_message_sender ON friendbook_cf_firestore_documents(json_extract(document_json,'$.senderId'),create_time DESC) WHERE collection_id='messages';
CREATE INDEX IF NOT EXISTS idx_cf_page_owner ON friendbook_cf_firestore_documents(json_extract(document_json,'$.ownerId')) WHERE parent_path='pages';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_page_owner ON friendbook_cf_documents((CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.ownerId.stringValue') ELSE json_extract(document_json,'$.ownerId') END)) WHERE collection='pages';
CREATE INDEX IF NOT EXISTS idx_cf_report_reporter ON friendbook_cf_firestore_documents(COALESCE(json_extract(document_json,'$.reporterId'),json_extract(document_json,'$.reportedBy'))) WHERE parent_path='reports';
CREATE INDEX IF NOT EXISTS idx_cf_report_post ON friendbook_cf_firestore_documents(json_extract(document_json,'$.postId')) WHERE parent_path='reports';
