-- Keep a reversible in-D1 snapshot, then retire the application-facing mirror.
CREATE TABLE friendbook_cf_documents_retired_backup AS
SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents;
CREATE TABLE friendbook_cf_documents_retired_backup_schema(type TEXT NOT NULL,name TEXT NOT NULL,sql TEXT NOT NULL,PRIMARY KEY(type,name));
INSERT INTO friendbook_cf_documents_retired_backup_schema(type,name,sql)
SELECT type,name,sql FROM sqlite_master WHERE tbl_name='friendbook_cf_documents' AND type IN ('table','index','trigger') AND sql IS NOT NULL;
CREATE TABLE friendbook_cf_documents_retirement_guard(ok INTEGER NOT NULL CHECK(ok=1));
INSERT INTO friendbook_cf_documents_retirement_guard(ok)
SELECT CASE WHEN
 (SELECT COUNT(*) FROM friendbook_cf_documents)=(SELECT COUNT(*) FROM friendbook_cf_documents_retired_backup)
 AND NOT EXISTS(SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents EXCEPT SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents_retired_backup)
 AND NOT EXISTS(SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents_retired_backup EXCEPT SELECT path,collection,doc_id,owner_uid,document_json,updated_at FROM friendbook_cf_documents)
THEN 1 ELSE 0 END;
DROP TABLE friendbook_cf_documents_retirement_guard;
DROP TABLE friendbook_cf_documents;
