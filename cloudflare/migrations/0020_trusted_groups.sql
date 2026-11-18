-- Only the Worker creates this ownership record, at the same time as a new group.
-- Historical group admin fields are not automatically trusted.
CREATE TABLE IF NOT EXISTS friendbook_cf_trusted_groups (
  group_path TEXT PRIMARY KEY NOT NULL,
  owner_uid TEXT NOT NULL
);
