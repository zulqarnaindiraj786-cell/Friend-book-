-- Committed alongside each official inbox delivery; never accessible to clients.
CREATE TABLE IF NOT EXISTS friendbook_cf_broadcast_push (
  notification_id TEXT PRIMARY KEY NOT NULL,
  recipient_uid TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  next_attempt_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_cf_broadcast_push_due
ON friendbook_cf_broadcast_push(next_attempt_at, notification_id);
