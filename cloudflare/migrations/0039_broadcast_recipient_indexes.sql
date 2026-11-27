-- Server-owned broadcast progress walks account IDs without loading all profiles.
CREATE INDEX IF NOT EXISTS idx_cf_broadcast_users ON friendbook_cf_firestore_documents(doc_id) WHERE parent_path='users';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_broadcast_users ON friendbook_cf_documents(doc_id) WHERE collection='users';
