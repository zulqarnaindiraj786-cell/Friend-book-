-- Retain historical aggregate counts whose actor identities were absent in source.
-- No fabricated users/reactions. Raw variants remain in the snapshot archive.
CREATE TABLE IF NOT EXISTS friendbook_cf_comment_legacy_aggregates (
 resource TEXT NOT NULL, id TEXT NOT NULL,
 unattributed_count INTEGER NOT NULL CHECK(unattributed_count>=0),
 source_path TEXT NOT NULL, source_sha256 TEXT NOT NULL, snapshot_batch TEXT NOT NULL,
 PRIMARY KEY(resource,id),
 FOREIGN KEY(resource,id) REFERENCES friendbook_cf_comments(resource,id)
);
