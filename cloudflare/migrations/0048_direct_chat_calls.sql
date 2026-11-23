-- One authoritative signaling session per direct chat. No media is stored.
CREATE TABLE IF NOT EXISTS friendbook_cf_chat_calls (
  chat_id TEXT PRIMARY KEY NOT NULL,
  call_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('ringing','connected','declined','ended','missed','cancelled')),
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cf_chat_calls_expiry ON friendbook_cf_chat_calls(expires_at);
