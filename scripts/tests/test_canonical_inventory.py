import sqlite3
import unittest
from scripts.loadtest.canonical_inventory import inspect, CAP


class CanonicalInventoryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.executescript('CREATE TABLE friendbook_cf_documents(path TEXT PRIMARY KEY,collection TEXT);'
                              'CREATE TABLE friendbook_cf_firestore_documents(path TEXT PRIMARY KEY);')
        self.addCleanup(self.db.close)

    def query(self, sql, params):
        return [dict(row) for row in self.db.execute(sql, params)], {'rows_read': 1}

    def test_only_unshadowed_paths_count_and_unknown_names_are_aggregated(self):
        self.db.executemany('INSERT INTO friendbook_cf_documents VALUES (?,?)',
                            [('users/a','users'),('users/b','users'),('posts/a','posts'),('x/a','private')])
        self.db.execute("INSERT INTO friendbook_cf_firestore_documents VALUES ('users/a')")
        result = inspect(self.query)
        self.assertEqual(result['collections']['users'], {'sampled':2,'legacy_only':1})
        self.assertEqual(result['collections']['other'], {'sampled':1,'legacy_only':1})
        self.assertNotIn('private', str(result))
        self.assertFalse(result['truncated'])

    def test_truncation_is_explicit_and_invalid_metadata_fails(self):
        self.db.executemany('INSERT INTO friendbook_cf_documents VALUES (?,?)',
                            [(f'users/{i:05}', 'users') for i in range(CAP+1)])
        result = inspect(self.query)
        self.assertEqual(result['sampled'], CAP)
        self.assertTrue(result['truncated'])
        with self.assertRaises(ValueError): inspect(lambda *args: ([], {}))
