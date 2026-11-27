-- Canonical-only retirement: idx_cf_notif_page has the identical recipient
-- leftmost expression and parent_path predicate. Its ordered keys also serve
-- recipient-only reads. Retain every legacy, unread, exception and page index.
-- Live application requires exact replacement-DDL and EXPLAIN checks first.
DROP INDEX IF EXISTS idx_cf_firestore_inbox_recipient;
