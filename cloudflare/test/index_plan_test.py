"""Exercise additive index migrations and verify critical SQLite query plans."""
from pathlib import Path
import sqlite3
import unittest

MIGRATIONS = Path(__file__).resolve().parents[1] / 'migrations'


class IndexPlans(unittest.TestCase):
    def test_production_migrations_and_index_plans(self):
        with sqlite3.connect(':memory:') as db:
            for migration in sorted(MIGRATIONS.glob('*.sql')):
                db.executescript(migration.read_text())
            db.executescript((MIGRATIONS / '0038_document_and_reply_indexes.sql').read_text())
            table = 'friendbook_cf_firestore_documents'
            archive = 'friendbook_cf_documents'
            checks = [
                ('idx_cf_group_post_lookup', f"SELECT doc_id FROM {table} WHERE parent_path='posts' AND json_extract(document_json,'$.groupId')=?", ('group',)),
                ('idx_cf_story_author', f"SELECT doc_id FROM {table} WHERE parent_path='stories' AND json_extract(document_json,'$.authorId')=?", ('author',)),
                ('idx_cf_legacy_page_owner', f"SELECT doc_id FROM {archive} WHERE collection='pages' AND (CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.ownerId.stringValue') ELSE json_extract(document_json,'$.ownerId') END)=?", ('owner',)),
                ('idx_cf_comment_reply_parent', 'SELECT id FROM friendbook_cf_comments WHERE resource=? AND parent_id=? ORDER BY created_at DESC,id DESC', ('posts/post', 'root')),
            ]
            for index, sql, args in checks:
                with self.subTest(index=index):
                    plan = db.execute('EXPLAIN QUERY PLAN ' + sql, args).fetchall()
                    self.assertTrue(any(index in row[-1] for row in plan), plan)


if __name__ == '__main__':
    unittest.main()
