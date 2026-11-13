-- Optional additive provenance; apply only with reviewed normalized import.
-- Unknown source timestamps are encoded as updated_at=0 + timestamp_known=0.
-- This is NOT a guessed date; API returns JSON null for these legacy timestamps.
-- Raw originals remain untouched in the per-batch snapshot archive.
CREATE TABLE IF NOT EXISTS friendbook_cf_reaction_history (
 resource TEXT NOT NULL, uid TEXT NOT NULL,
 timestamp_known INTEGER NOT NULL CHECK(timestamp_known IN (0,1)),
 PRIMARY KEY(resource,uid),
 FOREIGN KEY(resource,uid) REFERENCES friendbook_cf_reactions(resource,uid)
);
