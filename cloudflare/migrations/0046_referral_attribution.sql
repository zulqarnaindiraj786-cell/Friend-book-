CREATE TABLE IF NOT EXISTS friendbook_cf_referral_claims (
  referred_uid TEXT PRIMARY KEY NOT NULL,
  inviter_uid TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  CHECK(referred_uid<>inviter_uid)
);
CREATE INDEX IF NOT EXISTS idx_cf_referrals_from ON friendbook_cf_firestore_documents(json_extract(document_json,'$.refBy')) WHERE parent_path='referrals';
CREATE INDEX IF NOT EXISTS idx_cf_referrals_to ON friendbook_cf_firestore_documents(json_extract(document_json,'$.refTo')) WHERE parent_path='referrals';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_referrals_from ON friendbook_cf_documents((CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.refBy.stringValue') ELSE json_extract(document_json,'$.refBy') END)) WHERE collection='referrals';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_referrals_to ON friendbook_cf_documents((CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.refTo.stringValue') ELSE json_extract(document_json,'$.refTo') END)) WHERE collection='referrals';
