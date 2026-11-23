-- Separate device/socket sessions prevent an old disconnect from hiding a newer active session.
CREATE TABLE IF NOT EXISTS friendbook_cf_presence_sessions (
  uid TEXT NOT NULL,
  session_id TEXT NOT NULL,
  online INTEGER NOT NULL CHECK (online IN (0,1)),
  visible INTEGER NOT NULL CHECK (visible IN (0,1)),
  last_seen INTEGER NOT NULL,
  PRIMARY KEY (uid, session_id)
);
CREATE INDEX IF NOT EXISTS idx_cf_presence_sessions_uid_seen ON friendbook_cf_presence_sessions(uid,last_seen);
