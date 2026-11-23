-- A visible online heartbeat used to update both overlapping visibility indexes.
-- Keep disjoint indexes so each visible session maintains just one of them.
CREATE INDEX IF NOT EXISTS idx_cf_presence_visible_offline_seen
ON friendbook_cf_presence_sessions(uid,last_seen DESC) WHERE visible=1 AND online=0;
DROP INDEX IF EXISTS idx_cf_presence_visible_seen;
