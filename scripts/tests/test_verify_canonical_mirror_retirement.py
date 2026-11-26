import unittest
from scripts.loadtest.verify_canonical_mirror_retirement import diff


class CanonicalMirrorDiffTests(unittest.TestCase):
    def test_diff_uses_migration_equivalent_set_aggregates(self):
        calls = []
        responses = [
            ([{'name': 'friendbook_cf_documents'},
              {'name': 'friendbook_cf_documents_retired_backup'}], {'rows_read': 1}),
            ([{'n': 100}], {'rows_read': 100}),
            ([{'n': 100}], {'rows_read': 100}),
            ([{'n': 2}], {'rows_read': 200}),
            ([{'n': 2}], {'rows_read': 200}),
        ]
        def query(sql, params):
            calls.append(sql)
            return responses.pop(0)
        result = diff(query)
        self.assertEqual(result['current_rows'], 100)
        self.assertEqual(result['snapshot_rows'], 100)
        self.assertEqual(result['current_only'], 2)
        self.assertEqual(result['snapshot_only'], 2)
        self.assertEqual(result['rows_read'], 600)
        self.assertEqual(len(calls), 5)
        self.assertIn('EXCEPT', calls[3])
        self.assertIn('EXCEPT', calls[4])
        self.assertTrue(all('document_json' not in sql or 'COUNT(*)' in sql for sql in calls[1:]))


if __name__ == '__main__':
    unittest.main()
