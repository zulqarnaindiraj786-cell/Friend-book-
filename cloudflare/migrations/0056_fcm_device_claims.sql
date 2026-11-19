-- Keep per-device FCM dedupe separate from historical actor and bulk claims.
CREATE TABLE IF NOT EXISTS friendbook_cf_fcm_device_claims (
  event_key TEXT PRIMARY KEY NOT NULL,
  created_at INTEGER NOT NULL
);
