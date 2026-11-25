-- Some D1 runtimes may replay/create the compatibility table after 0105.
-- Require a complete exact snapshot before enforcing its removal again.
CREATE TABLE friendbook_cf_documents_retirement_guard_0106(ok INTEGER NOT NULL CHECK(ok=1));
INSERT INTO friendbook_cf_documents_retirement_guard_0106(ok)
SELECT CASE WHEN
 EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents_retired_backup')
 AND EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents_retired_backup_schema')
 AND (SELECT COUNT(*) FROM friendbook_cf_documents)=(SELECT COUNT(*) FROM friendbook_cf_documents_retired_backup)
 AND NOT EXISTS(SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents EXCEPT SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents_retired_backup)
 AND NOT EXISTS(SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents_retired_backup EXCEPT SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents)
THEN 1 ELSE 0 END;
DROP TABLE friendbook_cf_documents_retirement_guard_0106;
DROP TABLE friendbook_cf_documents;
