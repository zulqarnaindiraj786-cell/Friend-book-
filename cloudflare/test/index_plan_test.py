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
            def legacy(field):
                return f"(CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.{field}.stringValue') ELSE json_extract(document_json,'$.{field}') END)"
            for direction, uid, peer in [('in', 'toUid', 'fromUid'), ('out', 'fromUid', 'toUid')]:
                checks.extend([
                    (f'idx_cf_request_canonical_{direction}_status', f"SELECT * FROM {table} WHERE parent_path='friend_requests' AND json_extract(document_json,'$.{uid}')=? AND json_extract(document_json,'$.status')=?", ('actor','pending')),
                    (f'idx_cf_request_legacy_{direction}_status', f"SELECT * FROM {archive} a WHERE a.collection='friend_requests' AND {legacy(uid)}=? AND {legacy('status')}=? AND NOT EXISTS (SELECT 1 FROM {table} f WHERE f.path=a.path)", ('actor','pending')),
                ])
            checks += [
                ('idx_cf_posts_author_page', f"SELECT * FROM {table} WHERE parent_path='posts' AND json_extract(document_json,'$.authorId')=?", ('author',)),
                ('idx_cf_legacy_posts_author_page', f"SELECT * FROM {archive} WHERE collection='posts' AND {legacy('authorId')}=?", ('author',)),
                ('idx_cf_reel_author_created', f"SELECT * FROM {table} WHERE parent_path='reels' AND json_extract(document_json,'$.authorId')=?", ('author',)),
                ('idx_cf_people_default_page', f"SELECT doc_id FROM {table} INDEXED BY idx_cf_people_default_page WHERE parent_path='users' AND doc_id>? ORDER BY doc_id LIMIT 7", ('cursor',)),
                ('idx_cf_legacy_people_default_page', f"SELECT doc_id FROM {archive} INDEXED BY idx_cf_legacy_people_default_page WHERE collection='users' AND doc_id>? ORDER BY doc_id LIMIT 7", ('cursor',)),
            ]
            for obsolete in ('idx_cf_broadcast_users', 'idx_cf_legacy_broadcast_users', 'idx_cf_posts_author',
                             'idx_cf_legacy_posts_author', 'idx_cf_reels_author', 'idx_cf_posts_feed_nonstandard',
                             'idx_cf_legacy_posts_feed_nonstandard', 'idx_cf_chat_nonstandard_order',
                             'idx_cf_chat_nonstandard_order_v2'):
                self.assertIsNone(db.execute("SELECT name FROM sqlite_master WHERE type='index' AND name=?", (obsolete,)).fetchone())
            for index, sql, args in checks:
                with self.subTest(index=index):
                    plan = db.execute('EXPLAIN QUERY PLAN ' + sql, args).fetchall()
                    self.assertTrue(any(index in row[-1] for row in plan), plan)


if __name__ == '__main__':
    unittest.main()
