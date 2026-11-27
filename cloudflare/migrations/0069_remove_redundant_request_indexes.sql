-- The 0049 status indexes start with these same collection/participant keys.
-- Keep one index for each direction and store, plus the distinct duplicate-check indexes.
DROP INDEX IF EXISTS idx_cf_request_fromUid;
DROP INDEX IF EXISTS idx_cf_request_toUid;
DROP INDEX IF EXISTS idx_cf_legacy_request_fromUid;
DROP INDEX IF EXISTS idx_cf_legacy_request_toUid;
