-- Optional staging only; no app queries use this table. NOT automatic on deploy.
-- Keep typed Firestore documents and batch IDs for non-destructive verification.
CREATE TABLE IF NOT EXISTS friendbook_cf_copy_staging (
    batch_id TEXT NOT NULL,
    path TEXT NOT NULL,
    document_json TEXT NOT NULL CHECK (json_valid(document_json)),
    sha256 TEXT NOT NULL,
    PRIMARY KEY (batch_id, path)
);
