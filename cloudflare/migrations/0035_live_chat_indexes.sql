-- Recent live messages and per-sender slow-mode checks without scanning other rooms.
CREATE INDEX IF NOT EXISTS idx_cf_live_chat_sender_time
  ON friendbook_cf_firestore_documents(parent_path, owner_uid, create_time DESC)
  WHERE collection_id = 'chat';
