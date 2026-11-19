-- Per-device typing state: one device stopping must not hide another active device.
CREATE TABLE IF NOT EXISTS friendbook_cf_typing_sessions (
  chat_id TEXT NOT NULL,
  uid TEXT NOT NULL,
  session_id TEXT NOT NULL,
  typing INTEGER NOT NULL CHECK(typing IN (0,1)),
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(chat_id,uid,session_id)
);
CREATE INDEX IF NOT EXISTS idx_cf_typing_sessions_chat_updated ON friendbook_cf_typing_sessions(chat_id,updated_at);
