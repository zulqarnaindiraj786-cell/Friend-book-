-- The legacy name query uses idx_cf_legacy_people_cursor_prefix (0061), which
-- supports both plain names and Firestore envelopes. The older 0053 index is
-- no longer used by the Worker and adds maintenance to every mirrored user
-- update. Keep the canonical name index and the replacement legacy index.
DROP INDEX IF EXISTS idx_cf_legacy_users_display_name;
