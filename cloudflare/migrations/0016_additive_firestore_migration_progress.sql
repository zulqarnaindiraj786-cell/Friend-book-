-- Durable resumable checkpoints for the quota-bounded Firestore -> D1 copy.
-- No source data is stored here and no existing document is changed or removed.
CREATE TABLE IF NOT EXISTS friendbook_cf_firestore_migration_progress (
    collection_path TEXT PRIMARY KEY NOT NULL,
    priority INTEGER NOT NULL DEFAULT 3,
    parent_path TEXT NOT NULL,
    collection_id TEXT NOT NULL,
    next_page_token TEXT,
    documents_read INTEGER NOT NULL DEFAULT 0,
    documents_written INTEGER NOT NULL DEFAULT 0,
    firebase_count INTEGER,
    d1_count INTEGER,
    status TEXT NOT NULL DEFAULT 'pending',
    updated_at INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_friendbook_cf_firestore_migration_queue
    ON friendbook_cf_firestore_migration_progress(status, priority, collection_path);
