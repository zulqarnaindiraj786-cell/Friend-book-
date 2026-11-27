-- Archive the small reappeared mirror only after the canonical-only Worker is
-- deployed. ALTER TABLE preserves its exact rows, columns, indexes and triggers;
-- nothing is copied or deleted. Rollback is a plain reverse rename.
ALTER TABLE friendbook_cf_documents
RENAME TO friendbook_cf_documents_reappeared_archive_0109;

CREATE TABLE friendbook_cf_documents_reappeared_archive_guard_0109 (
  ok INTEGER NOT NULL CHECK (ok=1)
);
INSERT INTO friendbook_cf_documents_reappeared_archive_guard_0109(ok)
SELECT CASE WHEN
  NOT EXISTS (SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents')
  AND EXISTS (SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents_reappeared_archive_0109')
  AND EXISTS (SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents_retired_backup')
  AND EXISTS (SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents_reappeared_0106')
  AND (SELECT COUNT(*) FROM pragma_table_info('friendbook_cf_documents_reappeared_archive_0109')
       WHERE name IN ('path','collection','doc_id','owner_uid','document_json','updated_at'))=6
THEN 1 ELSE 0 END;
DROP TABLE friendbook_cf_documents_reappeared_archive_guard_0109;
