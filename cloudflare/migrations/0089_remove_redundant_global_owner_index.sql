-- Runtime document authorization uses primary-key paths, not global owner scans.
-- Live-chat sender throttles retain idx_cf_live_chat_sender_time, which seeks
-- parent_path,owner_uid,create_time within collection_id='chat'. The global
-- owner-only index adds maintenance to every canonical document insert/delete.
-- No documents, authorization rules, or scoped indexes are changed.
-- Rollback (requires an index rebuild):
-- CREATE INDEX idx_friendbook_cf_firestore_owner
--   ON friendbook_cf_firestore_documents(owner_uid);
DROP INDEX IF EXISTS idx_friendbook_cf_firestore_owner;
