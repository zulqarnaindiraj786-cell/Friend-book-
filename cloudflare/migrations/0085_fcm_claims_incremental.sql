-- New push claims cost one WITHOUT ROWID insert, with no copy, scan, deletion,
-- or rewrite of the existing claims. Keep the old table indefinitely: old
-- in-flight Worker versions and rollbacks must continue to see old claims.
CREATE TABLE IF NOT EXISTS friendbook_cf_fcm_device_claims_v2 (
  event_key TEXT PRIMARY KEY NOT NULL,
  created_at INTEGER NOT NULL
) WITHOUT ROWID;

-- An old Worker may still insert into the original table during a rollout or
-- after rollback. Suppress its insert when the new store already owns the key;
-- RAISE(IGNORE) reports zero changes just like ON CONFLICT DO NOTHING.
CREATE TRIGGER IF NOT EXISTS cf_fcm_claims_legacy_bridge
BEFORE INSERT ON friendbook_cf_fcm_device_claims
WHEN EXISTS (SELECT 1 FROM friendbook_cf_fcm_device_claims_v2 WHERE event_key=NEW.event_key)
BEGIN
  SELECT RAISE(IGNORE);
END;
