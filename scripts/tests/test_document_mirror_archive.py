import sqlite3
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
MIGRATION=ROOT/'cloudflare/migrations/0109_archive_reappeared_document_mirror.sql'


class DocumentMirrorArchiveMigration(unittest.TestCase):
    def test_rename_preserves_all_live_rows_indexes_triggers_and_reverse_rollback(self):
        db=sqlite3.connect(':memory:')
        try:
            db.executescript('''
            CREATE TABLE friendbook_cf_documents(path TEXT PRIMARY KEY,collection TEXT,doc_id TEXT,owner_uid TEXT,document_json TEXT,updated_at INTEGER);
            CREATE INDEX idx_archive_probe ON friendbook_cf_documents(collection,updated_at);
            CREATE TRIGGER archive_probe AFTER UPDATE ON friendbook_cf_documents BEGIN SELECT 1; END;
            CREATE TABLE friendbook_cf_documents_retired_backup(path TEXT);
            CREATE TABLE friendbook_cf_documents_reappeared_0106(path TEXT);
            INSERT INTO friendbook_cf_documents VALUES('users/a','users','a','a','{"uid":"a"}',4);
            INSERT INTO friendbook_cf_documents VALUES('posts/b','posts','b','a','{"id":"b"}',9);
            ''')
            original=db.execute('SELECT * FROM friendbook_cf_documents ORDER BY path').fetchall()
            db.executescript(MIGRATION.read_text())
            self.assertIsNone(db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents'").fetchone())
            archived=db.execute('SELECT * FROM friendbook_cf_documents_reappeared_archive_0109 ORDER BY path').fetchall()
            self.assertEqual(archived,original)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_archive_probe' AND tbl_name='friendbook_cf_documents_reappeared_archive_0109'").fetchone()[0],1)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='archive_probe' AND tbl_name='friendbook_cf_documents_reappeared_archive_0109'").fetchone()[0],1)
            db.execute('ALTER TABLE friendbook_cf_documents_reappeared_archive_0109 RENAME TO friendbook_cf_documents')
            self.assertEqual(db.execute('SELECT * FROM friendbook_cf_documents ORDER BY path').fetchall(),original)
        finally:
            db.close()


if __name__=='__main__':
    unittest.main()
