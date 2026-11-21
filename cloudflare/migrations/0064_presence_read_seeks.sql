-- Presence reads seek the newest visible row and any fresh visible online row.
-- Hidden sessions still suppress the legacy fallback via the existing UID index.
CREATE INDEX IF NOT EXISTS idx_cf_presence_visible_seen
ON friendbook_cf_presence_sessions(uid,last_seen DESC) WHERE visible=1;
CREATE INDEX IF NOT EXISTS idx_cf_presence_visible_online_seen
ON friendbook_cf_presence_sessions(uid,last_seen DESC) WHERE visible=1 AND online=1;
