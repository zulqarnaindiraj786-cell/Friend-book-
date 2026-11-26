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
            # Reapplying historical 0038 recreates indexes retired later.
            db.executescript((MIGRATIONS / '0079_remove_unused_message_sender_index.sql').read_text())
            db.executescript((MIGRATIONS / '0080_remove_unused_comment_author_index.sql').read_text())
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
            # Representative user-requested app areas mapped to the real schema;
            # these checks do not establish that every dynamic query is indexed.
            checks += [
                ('idx_cf_profile_email', f"SELECT path FROM {table} WHERE parent_path='users' AND json_extract(document_json,'$.email')=?", ('a',)),
                ('idx_cf_profile_username', f"SELECT path FROM {table} WHERE parent_path='users' AND json_extract(document_json,'$.username')=?", ('a',)),
                ('friendbook_cf_comments_page', 'SELECT id FROM friendbook_cf_comments WHERE resource=? ORDER BY created_at DESC,id DESC LIMIT 20', ('posts/p',)),
                ('sqlite_autoindex_friendbook_cf_reactions_1', 'SELECT type FROM friendbook_cf_reactions WHERE resource=? AND uid=?', ('posts/p', 'u')),
                ('idx_cf_friend_participant_0', f"SELECT path FROM {table} WHERE parent_path='friends' AND json_extract(document_json,'$.participants[0]')=?", ('u',)),
                ('idx_cf_friend_wrapped_participant_0', f"SELECT path FROM {table} INDEXED BY idx_cf_friend_wrapped_participant_0 WHERE parent_path='friends' AND COALESCE((json_array_length(document_json,'$.fields.participants.arrayValue.values')=2 AND json_type(document_json,'$.fields.participants.arrayValue.values[0].stringValue')='text' AND json_type(document_json,'$.fields.participants.arrayValue.values[1].stringValue')='text'),0)=1 AND json_extract(document_json,'$.fields.participants.arrayValue.values[0].stringValue')=?", ('u',)),
                ('idx_cf_friend_wrapped_participant_1', f"SELECT path FROM {table} INDEXED BY idx_cf_friend_wrapped_participant_1 WHERE parent_path='friends' AND COALESCE((json_array_length(document_json,'$.fields.participants.arrayValue.values')=2 AND json_type(document_json,'$.fields.participants.arrayValue.values[0].stringValue')='text' AND json_type(document_json,'$.fields.participants.arrayValue.values[1].stringValue')='text'),0)=1 AND json_extract(document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?", ('u',)),
                ('idx_cf_friend_guard_exceptions_v2', f"SELECT path FROM {table} INDEXED BY idx_cf_friend_guard_exceptions_v2 WHERE parent_path='friends' AND (json_type(document_json,'$.participants')='object' OR json_array_length(document_json,'$.participants')>2 OR (json_type(document_json,'$.fields.participants.arrayValue.values') IS NOT NULL AND NOT (COALESCE((json_array_length(document_json,'$.fields.participants.arrayValue.values')=2 AND json_type(document_json,'$.fields.participants.arrayValue.values[0].stringValue')='text' AND json_type(document_json,'$.fields.participants.arrayValue.values[1].stringValue')='text'),0)=1)))", ()),
                ('idx_cf_chat_participant_source', 'SELECT chat_path FROM friendbook_cf_chat_participants WHERE participant_uid=? AND source=1', ('u',)),
                ('idx_cf_notif_page', f"SELECT path FROM {table} INDEXED BY idx_cf_notif_page WHERE parent_path='notifications' AND json_extract(document_json,'$.toUserId')=?", ('u',)),
                ('sqlite_autoindex_friendbook_cf_view_counters_1', 'SELECT views FROM friendbook_cf_view_counters WHERE resource=?', ('posts/p',)),
            ]
            for obsolete in ('idx_cf_broadcast_users', 'idx_cf_legacy_broadcast_users', 'idx_cf_posts_author',
                             'idx_cf_legacy_posts_author', 'idx_cf_reels_author', 'idx_cf_posts_feed_nonstandard',
                             'idx_cf_legacy_posts_feed_nonstandard', 'idx_cf_chat_nonstandard_order',
                             'idx_cf_chat_nonstandard_order_v2', 'idx_cf_legacy_users_display_name',
                             'idx_cf_message_sender', 'idx_cf_comment_author', 'idx_cf_inbox_unread', 'idx_cf_firestore_inbox_recipient'):
                self.assertIsNone(db.execute("SELECT name FROM sqlite_master WHERE type='index' AND name=?", (obsolete,)).fetchone())
            for index, sql, args in checks:
                with self.subTest(index=index):
                    plan = db.execute('EXPLAIN QUERY PLAN ' + sql, args).fetchall()
                    self.assertTrue(any(index in row[-1] for row in plan), plan)

    def test_obsolete_legacy_name_index_is_removed_without_losing_prefix_seek(self):
        with sqlite3.connect(':memory:') as db:
            for migration in sorted(MIGRATIONS.glob('*.sql')):
                if migration.name != '0075_remove_obsolete_legacy_name_index.sql':
                    db.executescript(migration.read_text())
            db.execute('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',
                       ('users/alice', 'users', 'alice', 'alice', '{"displayName":"Alice","lastSeenMs":0}', 1))
            update = "UPDATE friendbook_cf_documents SET document_json=json_set(document_json,'$.lastSeenMs',?) WHERE path='users/alice'"
            def writes():
                steps = [0]
                def tick():
                    steps[0] += 1
                    return 0
                db.set_progress_handler(tick, 1)
                try:
                    for i in range(1000):
                        db.execute(update, (i,))
                finally:
                    db.set_progress_handler(None, 0)
                return steps[0]
            before = writes()
            db.executescript((MIGRATIONS / '0075_remove_obsolete_legacy_name_index.sql').read_text())
            db.executescript((MIGRATIONS / '0075_remove_obsolete_legacy_name_index.sql').read_text())
            after = writes()
            self.assertLess(after, before)
            self.assertIsNone(db.execute("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_cf_legacy_users_display_name'").fetchone())
            self.assertIsNotNone(db.execute("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_cf_legacy_people_cursor_prefix'").fetchone())
            legacy = """CASE WHEN json_type(document_json,'$.fields')='object' THEN CASE
              WHEN json_type(document_json,'$.fields.displayName')='text' THEN json_extract(document_json,'$.fields.displayName')
              WHEN json_type(document_json,'$.fields.displayName.stringValue')='text' THEN json_extract(document_json,'$.fields.displayName.stringValue')
              WHEN json_type(document_json,'$.fields.displayName.stringValue') IS NOT NULL THEN 1 END
              ELSE json_extract(document_json,'$.displayName') END"""
            query = f"SELECT path FROM friendbook_cf_documents INDEXED BY idx_cf_legacy_people_cursor_prefix WHERE collection='users' AND ({legacy})>=? AND ({legacy})<?"
            plan = db.execute('EXPLAIN QUERY PLAN '+query, ('Al','Am')).fetchall()
            self.assertTrue(any('SEARCH' in row[-1] and 'idx_cf_legacy_people_cursor_prefix' in row[-1] for row in plan),plan)
            self.assertEqual(db.execute(query, ('Al','Am')).fetchall(), [('users/alice',)])
            # SQLite VM instructions and the presence of an index are not D1 billed writes.

    def test_chat_membership_source_index_reduces_local_work_without_changing_results(self):
        with sqlite3.connect(':memory:') as db:
            for migration in sorted(MIGRATIONS.glob('*.sql')):
                if migration.name != '0073_chat_membership_source_seek.sql':
                    db.executescript(migration.read_text())
            for n in range(500):
                path = f'chats/room{n}'
                doc = '{"participants":["alice","bob"]}'
                db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',
                           (path, 'chats', 'chats', str(n), 'alice', doc, 1, 1))
                db.execute('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',
                           (path, 'chats', str(n), 'alice', doc, 1))
            sql = ('SELECT f.path FROM friendbook_cf_chat_participants m '
                   'JOIN friendbook_cf_firestore_documents f ON f.path=m.chat_path '
                   'WHERE m.participant_uid=? AND m.source=1')
            def run(query):
                steps = [0]
                def tick():
                    steps[0] += 1
                    return 0
                db.set_progress_handler(tick, 1)
                try:
                    rows = db.execute(query, ('bob',)).fetchall()
                finally:
                    db.set_progress_handler(None, 0)
                return sorted(rows), steps[0]
            old_rows, old_steps = run(sql)
            db.executescript((MIGRATIONS / '0073_chat_membership_source_seek.sql').read_text())
            db.executescript((MIGRATIONS / '0073_chat_membership_source_seek.sql').read_text())
            indexed_sql = sql.replace('m JOIN', 'm INDEXED BY idx_cf_chat_participant_source JOIN')
            plan = db.execute('EXPLAIN QUERY PLAN ' + indexed_sql, ('bob',)).fetchall()
            self.assertTrue(any('idx_cf_chat_participant_source' in x[-1] and 'source=?' in x[-1] for x in plan), plan)
            new_rows, new_steps = run(indexed_sql)
            self.assertEqual(old_rows, new_rows)
            self.assertEqual(len(new_rows), 500)
            self.assertLess(new_steps, old_steps)
            # SQLite VM instructions are not Cloudflare D1 rows read.

    def test_root_message_guards_seek_all_representations(self):
        with sqlite3.connect(':memory:') as db:
            for migration in sorted(MIGRATIONS.glob('*.sql')):
                db.executescript(migration.read_text())
            # Migrations are safe to rerun, including the new partial indexes.
            db.executescript((MIGRATIONS / '0072_root_message_guard_indexes.sql').read_text())
            checks = [
                ('friendbook_cf_firestore_documents', "parent_path='messages'", 'idx_cf_root_message_doc_id', 'doc_id>=? AND doc_id<? AND substr(doc_id,1,?)=?', ('room_', 'room`', 5, 'room_')),
                ('friendbook_cf_firestore_documents', "parent_path='messages'", 'idx_cf_root_message_chat_id', "json_extract(document_json,'$.chatId')=?", ('room',)),
                ('friendbook_cf_firestore_documents', "parent_path='messages'", 'idx_cf_root_message_typed_chat_id', "json_extract(document_json,'$.fields.chatId.stringValue')=?", ('room',)),
                ('friendbook_cf_documents', "collection='messages'", 'idx_cf_legacy_root_message_doc_id', 'doc_id>=? AND doc_id<? AND substr(doc_id,1,?)=?', ('room_', 'room`', 5, 'room_')),
                ('friendbook_cf_documents', "collection='messages'", 'idx_friendbook_cf_documents_message_chat_id', "json_extract(document_json,'$.chatId')=?", ('room',)),
                ('friendbook_cf_documents', "collection='messages'", 'idx_cf_legacy_message_typed_chat_id', "json_extract(document_json,'$.fields.chatId.stringValue')=?", ('room',)),
            ]
            for table, root, index, predicate, args in checks:
                with self.subTest(index=index):
                    plan = db.execute(f'EXPLAIN QUERY PLAN SELECT 1 FROM {table} WHERE {root} AND {predicate} LIMIT 1', args).fetchall()
                    self.assertTrue(any(index in row[-1] and 'SEARCH' in row[-1] for row in plan), plan)


if __name__ == '__main__':
    unittest.main()
