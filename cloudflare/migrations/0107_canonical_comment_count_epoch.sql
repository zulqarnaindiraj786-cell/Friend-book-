-- Cached legacy counts also depend on whether a canonical comment shadows an
-- archived row. Invalidate the same primary-backed epoch on canonical comment
-- insert/delete/move so cached counts cannot double-count promotions.
CREATE TRIGGER IF NOT EXISTS cf_legacy_comment_epoch_canonical_insert
AFTER INSERT ON friendbook_cf_comments
BEGIN
 UPDATE friendbook_cf_legacy_comment_epoch SET
  generation=CASE WHEN revision>=9007199254740990 THEN lower(hex(randomblob(16))) ELSE generation END,
  revision=CASE WHEN revision>=9007199254740990 THEN 0 ELSE revision+1 END WHERE id=1;
END;
CREATE TRIGGER IF NOT EXISTS cf_legacy_comment_epoch_canonical_delete
AFTER DELETE ON friendbook_cf_comments
BEGIN
 UPDATE friendbook_cf_legacy_comment_epoch SET
  generation=CASE WHEN revision>=9007199254740990 THEN lower(hex(randomblob(16))) ELSE generation END,
  revision=CASE WHEN revision>=9007199254740990 THEN 0 ELSE revision+1 END WHERE id=1;
END;
CREATE TRIGGER IF NOT EXISTS cf_legacy_comment_epoch_canonical_update
AFTER UPDATE OF resource,id ON friendbook_cf_comments
WHEN OLD.resource IS NOT NEW.resource OR OLD.id IS NOT NEW.id
BEGIN
 UPDATE friendbook_cf_legacy_comment_epoch SET
  generation=CASE WHEN revision>=9007199254740990 THEN lower(hex(randomblob(16))) ELSE generation END,
  revision=CASE WHEN revision>=9007199254740990 THEN 0 ELSE revision+1 END WHERE id=1;
END;
