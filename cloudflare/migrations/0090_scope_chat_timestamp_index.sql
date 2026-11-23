-- Runtime collection_id timestamp ordering is used only for canonical root chats.
-- Build its exact partial replacement before retiring the global timestamp index.
-- This avoids timestamp-index maintenance for users/posts/notifications/messages.
-- Live-chat throttle indexes and parent/path indexes remain unchanged.
CREATE INDEX IF NOT EXISTS idx_cf_chat_root_updated
ON friendbook_cf_firestore_documents(parent_path,update_time DESC)
WHERE collection_id='chats' AND parent_path='chats';

DROP INDEX IF EXISTS idx_friendbook_cf_firestore_collection_updated;
-- Rollback: recreate the original 0014 global index before dropping this partial.
