-- Optional provenance for comments whose source did not contain a creation time.
-- No original records are changed or removed by this additive schema.
CREATE TABLE IF NOT EXISTS friendbook_cf_comment_history (
 resource TEXT NOT NULL, id TEXT NOT NULL,
 created_at_known INTEGER NOT NULL CHECK(created_at_known IN (0,1)),
 PRIMARY KEY(resource,id),
 FOREIGN KEY(resource,id) REFERENCES friendbook_cf_comments(resource,id)
);
