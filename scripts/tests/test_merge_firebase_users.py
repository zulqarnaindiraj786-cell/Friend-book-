"""The Firebase -> D1 profile merge must be additive only, and only fill placeholders."""
from pathlib import Path
import json
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / 'admin'))
import merge_firebase_users as merge


class PlaceholderTests(unittest.TestCase):
    def test_schema_defaults_count_as_placeholders(self):
        for value in (None, '', '   ', 'User', 'user', 'USER', 'Unknown'):
            self.assertTrue(merge.placeholder(value), value)

    def test_a_real_name_is_never_a_placeholder(self):
        for value in ('Ayesha Khan', 'Rahul', 'user123', 'U', '  Meera '):
            self.assertFalse(merge.placeholder(value), value)


class FirestoreParsingTests(unittest.TestCase):
    def test_typed_values_unwrap(self):
        self.assertEqual(merge.firestore_value({'stringValue': 'Ayesha'}), 'Ayesha')
        self.assertEqual(merge.firestore_value({'integerValue': '12'}), 12)
        self.assertIs(merge.firestore_value({'booleanValue': True}), True)
        self.assertIsNone(merge.firestore_value({'nullValue': None}))
        self.assertIsNone(merge.firestore_value({'mapValue': {}}))

    def test_first_real_name_and_photo_win_and_pages_are_followed(self):
        pages = [
            {'documents': [
                {'name': 'projects/p/databases/(default)/documents/users/u1',
                 'fields': {'displayName': {'stringValue': 'User'},
                            'name': {'stringValue': 'Ayesha Khan'},
                            'photoURL': {'stringValue': 'https://cdn/a.jpg'}}},
            ], 'nextPageToken': 'page2'},
            {'documents': [
                {'name': 'projects/p/databases/(default)/documents/users/u2',
                 'fields': {'displayName': {'stringValue': 'Rahul'},
                            'photoUrl': {'stringValue': ''}}},
            ]},
        ]

        class FakeSession:
            def __init__(self):
                self.calls = 0

            def get(self, url, params=None, timeout=None):
                body = pages[self.calls]
                self.calls += 1

                class Response:
                    status_code = 200

                    @staticmethod
                    def json():
                        return body

                return Response()

        session = FakeSession()
        users = merge.firestore_users(session)
        self.assertEqual(users, [
            {'uid': 'u1', 'name': 'Ayesha Khan', 'photo': 'https://cdn/a.jpg'},
            {'uid': 'u2', 'name': 'Rahul', 'photo': None},
        ])
        self.assertEqual(session.calls, 2)

    def test_a_failed_firestore_read_writes_nothing(self):
        class Failing:
            @staticmethod
            def get(url, params=None, timeout=None):
                class Response:
                    status_code = 403

                    @staticmethod
                    def json():
                        return {'error': {'message': 'insufficient permissions'}}

                return Response()

        with self.assertRaises(merge.MergeError):
            merge.firestore_users(Failing())


class ApplySafetyTests(unittest.TestCase):
    """No INSERT, no DELETE, and every UPDATE is guarded by the placeholder condition."""

    def runApply(self, firebaseUsers, users=(), docs=()):
        statements = []

        def fakeD1(sql, params=(), retries=3):
            statements.append((sql, list(params)))
            if sql.strip().startswith('SELECT'):
                if 'FROM users' in sql and 'COUNT(*)' in sql:
                    return ([{'usersTable': len(users), 'postsTable': 1, 'userDocs': 1,
                              'postDocs': 1}], {})
                if sql.strip().startswith('SELECT uid FROM users'):
                    return ([{'uid': uid} for uid in users], {})
                if 'FROM friendbook_cf_documents' in sql and 'collection = ' in sql:
                    return (list(docs), {})
                return ([], {})
            return ([], {'changes': 1})

        def fakeSession():
            return object()

        with patch.object(merge, 'd1', fakeD1), \
                patch.object(merge, 'load_profiles',
                             lambda: (firebaseUsers, {'test': len(firebaseUsers)})), \
                patch.object(merge, 'verify', lambda: 0), \
                patch.object(merge, 'say', lambda payload: None):
            merge.apply()
        return statements

    def test_only_guarded_updates_are_issued(self):
        statements = self.runApply([{'uid': 'u1', 'name': 'Ayesha', 'photo': 'https://cdn/a.jpg'}],
                                   users=('u1',))
        writes = [sql for sql, _ in statements if not sql.strip().startswith('SELECT')]
        self.assertTrue(writes)
        for sql in writes:
            lowered = sql.lower()
            self.assertNotIn('insert', lowered)
            self.assertNotIn('delete', lowered)
            self.assertIn('where', lowered)
        self.assertTrue(any("TRIM(display_name) IN ('', 'User')" in sql for sql in writes))
        self.assertTrue(any("TRIM(author_name) IN ('', 'User')" in sql for sql in writes))
        self.assertTrue(any("TRIM(photo_url) = ''" in sql for sql in writes))

    def test_a_firebase_user_missing_from_d1_is_reported_not_created(self):
        statements = self.runApply([{'uid': 'u9', 'name': 'Ghost', 'photo': None}], users=())
        writes = [sql for sql, params in statements
                  if not sql.strip().startswith('SELECT') and 'u9' in params]
        self.assertEqual(writes, [])

    def test_no_name_in_firebase_means_no_name_write(self):
        statements = self.runApply([{'uid': 'u1', 'name': None, 'photo': None}], users=('u1',))
        writes = [sql for sql, _ in statements if not sql.strip().startswith('SELECT')]
        self.assertEqual(writes, [])

    def test_the_users_document_is_patched_only_where_it_is_a_placeholder(self):
        stored = {'path': 'users/u1', 'doc_id': 'u1',
                  'document_json': json.dumps({'id': 'u1', 'displayName': 'User',
                                               'photoUrl': ''})}
        statements = self.runApply([{'uid': 'u1', 'name': 'Ayesha', 'photo': 'https://cdn/a.jpg'}],
                                   users=('u1',), docs=[stored])
        documentWrites = [(sql, params) for sql, params in statements
                          if sql.startswith('UPDATE friendbook_cf_documents')]
        self.assertEqual(len(documentWrites), 1)
        written = json.loads(documentWrites[0][1][0])
        self.assertEqual(written['displayName'], 'Ayesha')
        self.assertEqual(written['photoUrl'], 'https://cdn/a.jpg')
        self.assertEqual(written['id'], 'u1')

    def test_a_real_name_in_the_users_document_is_left_alone(self):
        stored = {'path': 'users/u1', 'doc_id': 'u1',
                  'document_json': json.dumps({'id': 'u1', 'displayName': 'Already Real'})}
        statements = self.runApply([{'uid': 'u1', 'name': 'Ayesha', 'photo': None}],
                                   users=('u1',), docs=[stored])
        self.assertEqual([sql for sql, _ in statements
                          if sql.startswith('UPDATE friendbook_cf_documents')], [])


if __name__ == '__main__':
    unittest.main()
