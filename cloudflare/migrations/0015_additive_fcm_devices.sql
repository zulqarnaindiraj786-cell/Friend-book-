-- Additive FCM device registry + push dedup claims (Cloudflare D1).
-- Replaces Supabase fcm_device_tokens / push_delivery_claims.
-- Repeatable: IF NOT EXISTS only. No existing row is touched.
CREATE TABLE IF NOT EXISTS friendbook_cf_fcm_devices (
    device_id TEXT PRIMARY KEY NOT NULL,
    user_id TEXT NOT NULL,
    token TEXT NOT NULL,
    updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_friendbook_cf_fcm_devices_user
    ON friendbook_cf_fcm_devices(user_id);

CREATE TABLE IF NOT EXISTS friendbook_cf_push_claims (
    event_key TEXT PRIMARY KEY NOT NULL,
    created_at INTEGER NOT NULL DEFAULT 0
);

-- Some existing D1 databases already contain this table from the earlier push-claim path,
-- without the actor column. CREATE TABLE IF NOT EXISTS does not update its schema.
ALTER TABLE friendbook_cf_push_claims ADD COLUMN actor TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_friendbook_cf_push_claims_actor
    ON friendbook_cf_push_claims(actor, created_at DESC);
