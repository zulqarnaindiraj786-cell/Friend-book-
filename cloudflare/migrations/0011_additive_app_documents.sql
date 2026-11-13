-- Additive Cloudflare D1 document store for migrated and new app records.
CREATE TABLE IF NOT EXISTS friendbook_cf_documents (
    path TEXT PRIMARY KEY NOT NULL,
    collection TEXT NOT NULL,
    doc_id TEXT NOT NULL,
    owner_uid TEXT NOT NULL DEFAULT '',
    document_json TEXT NOT NULL CHECK (json_valid(document_json)),
    updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_friendbook_cf_documents_collection_updated
    ON friendbook_cf_documents(collection, updated_at DESC);
