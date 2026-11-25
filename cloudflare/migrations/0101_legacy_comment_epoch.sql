-- A tiny invalidation epoch, not a comment count. Existing historical rows do
-- not require backfill. Any root-comment mutation bumps it transactionally so
-- isolate-local *negative candidate* hints cannot survive a changed source.
CREATE TABLE IF NOT EXISTS friendbook_cf_legacy_comment_epoch (
 id INTEGER PRIMARY KEY CHECK(id=1),
 generation TEXT NOT NULL,
 revision INTEGER NOT NULL CHECK(revision>=0 AND revision<=9007199254740990)
);
INSERT INTO friendbook_cf_legacy_comment_epoch(id,generation,revision)
VALUES(1,lower(hex(randomblob(16))),0) ON CONFLICT(id) DO NOTHING;

CREATE TRIGGER IF NOT EXISTS cf_legacy_comment_epoch_insert
AFTER INSERT ON friendbook_cf_firestore_documents WHEN NEW.parent_path='comments'
BEGIN
 UPDATE friendbook_cf_legacy_comment_epoch SET
 generation=CASE WHEN revision>=9007199254740990 THEN lower(hex(randomblob(16))) ELSE generation END,
 revision=CASE WHEN revision>=9007199254740990 THEN 0 ELSE revision+1 END WHERE id=1;
END;
CREATE TRIGGER IF NOT EXISTS cf_legacy_comment_epoch_delete
AFTER DELETE ON friendbook_cf_firestore_documents WHEN OLD.parent_path='comments'
BEGIN
 UPDATE friendbook_cf_legacy_comment_epoch SET
 generation=CASE WHEN revision>=9007199254740990 THEN lower(hex(randomblob(16))) ELSE generation END,
 revision=CASE WHEN revision>=9007199254740990 THEN 0 ELSE revision+1 END WHERE id=1;
END;
CREATE TRIGGER IF NOT EXISTS cf_legacy_comment_epoch_update
AFTER UPDATE OF path,parent_path,document_json ON friendbook_cf_firestore_documents
WHEN OLD.parent_path='comments' OR NEW.parent_path='comments'
BEGIN
 UPDATE friendbook_cf_legacy_comment_epoch SET
 generation=CASE WHEN revision>=9007199254740990 THEN lower(hex(randomblob(16))) ELSE generation END,
 revision=CASE WHEN revision>=9007199254740990 THEN 0 ELSE revision+1 END WHERE id=1;
END;
