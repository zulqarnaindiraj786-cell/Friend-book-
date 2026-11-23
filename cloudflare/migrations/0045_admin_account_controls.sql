-- Do not grant privileges from historical profile flags.
CREATE TABLE IF NOT EXISTS friendbook_cf_admin_moderators (
  uid TEXT PRIMARY KEY NOT NULL,
  granted_by TEXT NOT NULL,
  granted_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS friendbook_cf_admin_account_audit (
  id TEXT PRIMARY KEY NOT NULL,
  admin_uid TEXT NOT NULL,
  action TEXT NOT NULL,
  target_path TEXT NOT NULL,
  original_json TEXT NOT NULL CHECK(json_valid(original_json)),
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cf_admin_account_audit_target ON friendbook_cf_admin_account_audit(target_path,created_at);
