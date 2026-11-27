-- Default people pages seek in document ID order across both stores.
CREATE INDEX IF NOT EXISTS idx_cf_people_default_page
ON friendbook_cf_firestore_documents(doc_id,path) WHERE parent_path='users';
CREATE INDEX IF NOT EXISTS idx_cf_legacy_people_default_page
ON friendbook_cf_documents(doc_id,path) WHERE collection='users';
