-- Polling only needs live typing sessions; idle sessions still use the expiry index.
CREATE INDEX IF NOT EXISTS idx_cf_typing_active_seek
ON friendbook_cf_typing_sessions(chat_id,updated_at,uid) WHERE typing=1;
DROP INDEX IF EXISTS idx_cf_typing_sessions_chat_updated;
