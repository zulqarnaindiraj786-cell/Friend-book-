-- Document queries are scoped by parent_path and apply sender filters after
-- loading the authorized collection. This collection_id-only index is not
-- used by those queries but is maintained on every message insert and update.
DROP INDEX IF EXISTS idx_cf_message_sender;
