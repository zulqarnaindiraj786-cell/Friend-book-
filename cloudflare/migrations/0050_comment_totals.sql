-- Keep exact visible canonical totals without re-reading entire comment histories.
CREATE TABLE IF NOT EXISTS friendbook_cf_visible_comment_totals (
  resource TEXT PRIMARY KEY NOT NULL,
  total INTEGER NOT NULL CHECK(total>=0)
);
INSERT INTO friendbook_cf_visible_comment_totals(resource,total)
SELECT c.resource,COUNT(*) FROM friendbook_cf_comments c
LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id
WHERE COALESCE(d.removed,0)=0 GROUP BY c.resource
ON CONFLICT(resource) DO UPDATE SET total=excluded.total;
CREATE INDEX IF NOT EXISTS idx_cf_comment_resource_author ON friendbook_cf_comments(resource,uid);
CREATE TRIGGER IF NOT EXISTS cf_visible_comment_insert AFTER INSERT ON friendbook_cf_comments
WHEN COALESCE((SELECT removed FROM friendbook_cf_comment_details WHERE resource=NEW.resource AND id=NEW.id),0)=0
BEGIN
  INSERT INTO friendbook_cf_visible_comment_totals VALUES(NEW.resource,1)
  ON CONFLICT(resource) DO UPDATE SET total=total+1;
END;
CREATE TRIGGER IF NOT EXISTS cf_visible_comment_delete AFTER DELETE ON friendbook_cf_comments
WHEN COALESCE((SELECT removed FROM friendbook_cf_comment_details WHERE resource=OLD.resource AND id=OLD.id),0)=0
BEGIN
  UPDATE friendbook_cf_visible_comment_totals SET total=total-1 WHERE resource=OLD.resource;
END;
CREATE TRIGGER IF NOT EXISTS cf_visible_comment_detail_insert AFTER INSERT ON friendbook_cf_comment_details
WHEN NEW.removed<>0 AND EXISTS(SELECT 1 FROM friendbook_cf_comments WHERE resource=NEW.resource AND id=NEW.id)
BEGIN
  UPDATE friendbook_cf_visible_comment_totals SET total=total-1 WHERE resource=NEW.resource;
END;
CREATE TRIGGER IF NOT EXISTS cf_visible_comment_detail_update AFTER UPDATE OF removed ON friendbook_cf_comment_details
WHEN (OLD.removed=0)<>(NEW.removed=0) AND EXISTS(SELECT 1 FROM friendbook_cf_comments WHERE resource=NEW.resource AND id=NEW.id)
BEGIN
  INSERT INTO friendbook_cf_visible_comment_totals VALUES(NEW.resource,0)
  ON CONFLICT(resource) DO NOTHING;
  UPDATE friendbook_cf_visible_comment_totals SET total=total+(NEW.removed=0)-(OLD.removed=0) WHERE resource=NEW.resource;
END;
CREATE TRIGGER IF NOT EXISTS cf_visible_comment_detail_delete AFTER DELETE ON friendbook_cf_comment_details
WHEN OLD.removed<>0 AND EXISTS(SELECT 1 FROM friendbook_cf_comments WHERE resource=OLD.resource AND id=OLD.id)
BEGIN
  INSERT INTO friendbook_cf_visible_comment_totals VALUES(OLD.resource,1)
  ON CONFLICT(resource) DO UPDATE SET total=total+1;
END;
