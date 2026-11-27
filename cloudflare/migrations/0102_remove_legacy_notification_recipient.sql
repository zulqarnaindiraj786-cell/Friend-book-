-- The ordered legacy notification page index has exactly the same recipient
-- expression and collection predicate as this redundant prefix index. Keep
-- all notification data, the ordered replacement, unread and exception indexes.
-- Before remote application require exact live DDL and recipient seek checks;
-- rollback: recreate idx_cf_legacy_inbox_recipient using migration 0025.
DROP INDEX IF EXISTS idx_cf_legacy_inbox_recipient;
