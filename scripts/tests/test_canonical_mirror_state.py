import unittest
from scripts.loadtest.canonical_mirror_state import inspect, TABLES


class CanonicalMirrorStateTests(unittest.TestCase):
    def test_schema_presence_and_nonempty_check_are_aggregate_only(self):
        calls = []
        def query(sql, params):
            calls.append((sql, params))
            if 'sqlite_master' in sql:
                return ([{'name': TABLES[0]}, {'name': TABLES[1]}, {'name': TABLES[2]}], {'rows_read': 4})
            if 'COUNT(*)' in sql:
                return ([{'n': 336}], {'rows_read': 336})
            return ([{'present': 1}], {'rows_read': 1})
        result = inspect(query)
        self.assertEqual(result, {
            'mirror_table_exists': True, 'mirror_has_rows': True, 'mirror_rows': 336,
            'backup_exists': True, 'backup_schema_exists': True,
            'reappeared_snapshot_exists': False, 'rows_read': 341,
        })
        self.assertEqual(len(calls), 3)
        self.assertIn('LIMIT 1', calls[1][0])
        self.assertIn('COUNT(*)', calls[2][0])
        self.assertEqual(calls[0][1], TABLES)

    def test_absent_mirror_does_not_scan_application_rows(self):
        calls = []
        def query(sql, params):
            calls.append(sql)
            return ([{'name': TABLES[1]}], {'rows_read': 1})
        result = inspect(query)
        self.assertFalse(result['mirror_table_exists'])
        self.assertIsNone(result['mirror_has_rows'])
        self.assertEqual(calls.__len__(), 1)


if __name__ == '__main__':
    unittest.main()
