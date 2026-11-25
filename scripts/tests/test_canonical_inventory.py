import sqlite3
import unittest
from scripts.loadtest.canonical_inventory import inspect, inspect_users, require_complete_canonical, CAP, FULL_CAP


class CanonicalInventoryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.executescript('CREATE TABLE friendbook_cf_documents(path TEXT PRIMARY KEY,collection TEXT);'
                              'CREATE INDEX idx_friendbook_cf_documents_collection_updated ON friendbook_cf_documents(collection);'
                              'CREATE TABLE friendbook_cf_firestore_documents(path TEXT PRIMARY KEY);')
        self.addCleanup(self.db.close)

    def query(self, sql, params):
        return [dict(row) for row in self.db.execute(sql, params)], {'rows_read': 1}

    def test_canonical_cutover_is_guarded_in_worker_deployment_workflow(self):
        from pathlib import Path
        root=Path(__file__).resolve().parents[2]
        workflow=(root/'.github/workflows/deploy-cloudflare.yml').read_text()
        config=(root/'wrangler.toml').read_text()
        self.assertIn("'0104_retire_legacy_derived_index_triggers.sql'",workflow)
        self.assertIn("python3 scripts/loadtest/canonical_inventory.py --require-no-legacy-only",workflow)
        import re
        self.assertRegex(workflow,r'grep -q .*DOCUMENT_MIRROR_MODE.*canonical.*wrangler\.toml')
        self.assertIn('DOCUMENT_MIRROR_MODE = "canonical"',config)
        self.assertIn('DOCUMENT_MIRROR_MODE = "dual"',config)

    def test_only_unshadowed_paths_count_and_unknown_names_are_aggregated(self):
        self.db.executemany('INSERT INTO friendbook_cf_documents VALUES (?,?)',
                            [('users/a','users'),('users/b','users'),('posts/a','posts'),('x/a','private')])
        self.db.execute("INSERT INTO friendbook_cf_firestore_documents VALUES ('users/a')")
        result = inspect(self.query)
        self.assertEqual(result['collections']['users'], {'sampled':2,'legacy_only':1})
        self.assertEqual(result['collections']['other'], {'sampled':1,'legacy_only':1})
        self.assertNotIn('private', str(result))
        self.assertFalse(result['truncated'])
        users = inspect_users(self.query)
        self.assertEqual((users['sampled'], users['legacy_only'], users['truncated']), (2,1,False))

    def test_cutover_gate_requires_full_zero_legacy_only_inventory(self):
        self.db.executemany('INSERT INTO friendbook_cf_documents VALUES (?,?)',
                            [('users/a','users'),('posts/p','posts')])
        self.db.executemany('INSERT INTO friendbook_cf_firestore_documents VALUES (?)',
                            [('users/a',),('posts/p',)])
        result=inspect(self.query,FULL_CAP)
        self.assertIs(require_complete_canonical(result),result)
        incomplete=dict(result,truncated=True)
        with self.assertRaisesRegex(ValueError,'truncated'):
            require_complete_canonical(incomplete)
        with self.assertRaisesRegex(ValueError,'Legacy-only'):
            require_complete_canonical(dict(result,collections={'users':{'sampled':2,'legacy_only':1}}))
        with self.assertRaisesRegex(ValueError,'Legacy-only users'):
            require_complete_canonical({'sampled':1,'legacy_only':1,'truncated':False})

    def test_truncation_is_explicit_and_invalid_metadata_fails(self):
        self.db.executemany('INSERT INTO friendbook_cf_documents VALUES (?,?)',
                            [(f'users/{i:05}', 'users') for i in range(CAP+1)])
        result = inspect(self.query)
        self.assertEqual(result['sampled'], CAP)
        self.assertTrue(result['truncated'])
        complete = inspect(self.query, FULL_CAP)
        self.assertFalse(complete['truncated'])
        self.assertEqual(complete['sampled'], CAP + 1)
        self.assertEqual(complete['collections']['users']['legacy_only'], CAP + 1)
        with self.assertRaises(ValueError): inspect(self.query, FULL_CAP + 1)
        with self.assertRaises(ValueError): inspect(lambda *args: ([], {}))
