-- Preserve any compatibility rows re-created after 0105 before retiring the
-- application-facing table again. 0105's exact-copy snapshot remains intact;
-- this second archive contains only the reappeared version for audit/restore.
CREATE TABLE friendbook_cf_documents_reappeared_0106 AS
SELECT path,collection,doc_id,owner_uid,document_json,updated_at
FROM friendbook_cf_documents WHERE 0;
INSERT INTO friendbook_cf_documents_reappeared_0106
SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents;
CREATE TABLE friendbook_cf_documents_retirement_guard_0106(ok INTEGER NOT NULL CHECK(ok=1));
INSERT INTO friendbook_cf_documents_retirement_guard_0106(ok)
SELECT CASE WHEN
 EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents_retired_backup')
 AND EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents_retired_backup_schema')
 AND EXISTS(SELECT 1 FROM friendbook_cf_documents_retired_backup_schema WHERE type='table' AND name='friendbook_cf_documents' AND sql IS NOT NULL)
 AND (SELECT COUNT(*) FROM friendbook_cf_documents)=(SELECT COUNT(*) FROM friendbook_cf_documents_reappeared_0106)
 AND NOT EXISTS(SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents EXCEPT SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents_reappeared_0106)
 AND NOT EXISTS(SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents_reappeared_0106 EXCEPT SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents)
THEN 1 ELSE 0 END;
DROP TABLE friendbook_cf_documents_retirement_guard_0106;
DROP TABLE friendbook_cf_documents;
