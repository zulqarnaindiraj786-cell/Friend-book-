-- Cover scheduled, bounded expiry queries without changing existing records.
CREATE INDEX IF NOT EXISTS idx_cf_presence_sessions_expiry ON friendbook_cf_presence_sessions(last_seen);
CREATE INDEX IF NOT EXISTS idx_cf_typing_sessions_expiry ON friendbook_cf_typing_sessions(updated_at);
