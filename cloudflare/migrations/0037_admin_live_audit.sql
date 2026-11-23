-- Keep live actions separate from the historical ban/delete audit constraint.
CREATE TABLE IF NOT EXISTS friendbook_cf_live_moderation_audit (
  id TEXT PRIMARY KEY NOT NULL,
  admin_uid TEXT NOT NULL,
  action TEXT NOT NULL CHECK(action='end_live'),
  target_path TEXT NOT NULL,
  original_json TEXT NOT NULL CHECK(json_valid(original_json)),
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cf_live_moderation_target
  ON friendbook_cf_live_moderation_audit(target_path, created_at DESC);
