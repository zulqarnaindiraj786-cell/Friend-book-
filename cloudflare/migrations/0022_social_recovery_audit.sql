-- Administrator-attested recoveries preserve their original document and cannot be replayed.
CREATE TABLE IF NOT EXISTS friendbook_cf_social_recovery_audit (
  path TEXT PRIMARY KEY NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('group','chat')),
  admin_uid TEXT NOT NULL,
  owner_uid TEXT NOT NULL,
  original_json TEXT NOT NULL CHECK(json_valid(original_json)),
  recovered_at INTEGER NOT NULL
);

-- Recovery audit migration is additive and preserves prior documents.
