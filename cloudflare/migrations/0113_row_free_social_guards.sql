-- Guard assertions only need to abort on a failed predicate; successful checks
-- must not create and later delete one D1 row per authorization guard. Replacing
-- the empty guard table with an INSTEAD OF trigger preserves the existing INSERT
-- contract for old and new Workers while storing no successful assertion rows.
DROP TABLE IF EXISTS friendbook_cf_social_guards;
CREATE VIEW friendbook_cf_social_guards AS SELECT CAST(NULL AS INTEGER) AS ok WHERE 0;
CREATE TRIGGER friendbook_cf_social_guards_assert_insert
INSTEAD OF INSERT ON friendbook_cf_social_guards
BEGIN
  SELECT CASE WHEN NEW.ok IS NOT 1 THEN RAISE(ABORT, 'CHECK constraint failed: ok = 1') END;
END;
CREATE TRIGGER friendbook_cf_social_guards_noop_delete
INSTEAD OF DELETE ON friendbook_cf_social_guards
BEGIN
  SELECT 1;
END;
