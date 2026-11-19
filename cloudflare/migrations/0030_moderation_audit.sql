-- Keep a server-side record and recoverable snapshot of administrator moderation actions.
CREATE TABLE IF NOT EXISTS friendbook_cf_moderation_audit (
  id TEXT PRIMARY KEY NOT NULL,
  admin_uid TEXT NOT NULL,
  action TEXT NOT NULL CHECK(action IN ('ban_user','unban_user','delete_content')),
  target_path TEXT NOT NULL,
  original_json TEXT NOT NULL CHECK(json_valid(original_json)),
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cf_moderation_audit_target ON friendbook_cf_moderation_audit(target_path,created_at);
