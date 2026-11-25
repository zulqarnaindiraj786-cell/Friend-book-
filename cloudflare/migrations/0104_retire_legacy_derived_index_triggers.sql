-- Canonical-only Worker reads use source=1 for chat participants, post search,
-- and user follow membership. Their source=1 triggers are attached to
-- friendbook_cf_firestore_documents and remain in place. Source=0 trigger rows
-- only maintained duplicate projections from the legacy document mirror; no
-- production Worker read arm consumes those source=0 projections anymore.
-- Keep the legacy table and its rows intact for fallback/rollback review.
DROP TRIGGER IF EXISTS cf_chat_members_0_insert;
DROP TRIGGER IF EXISTS cf_chat_members_0_update;
DROP TRIGGER IF EXISTS cf_chat_members_0_delete;
DROP TRIGGER IF EXISTS cf_post_search_0_insert;
DROP TRIGGER IF EXISTS cf_post_search_0_update;
DROP TRIGGER IF EXISTS cf_post_search_0_delete;
DROP TRIGGER IF EXISTS cf_user_follow_0_insert;
DROP TRIGGER IF EXISTS cf_user_follow_0_update;
DROP TRIGGER IF EXISTS cf_user_follow_0_delete;
