-- The Worker now explicitly seeks the recipient-scoped ordered unread index
-- idx_cf_notif_unread for both compatibility and standard unread queries.
-- Its leftmost key and partial predicate cover this older canonical index.
-- Keep all legacy indexes: wrapped historical unread records differ.
DROP INDEX IF EXISTS idx_cf_inbox_unread;
