import unittest
from scripts.loadtest.verify_retired_drop import inspect
from scripts.loadtest.retired_table_preflight import TABLES


class VerifyRetiredDropTest(unittest.TestCase):
    def test_names_only_and_required_tables(self):
        names=['friendbook_cf_documents', 'friendbook_cf_firestore_documents', 'other']
        query=lambda *_: ([{'name': name} for name in names], {'rows_read': 3})
        self.assertEqual(inspect(query)['retired_tables_remaining'], 0)
        names.append(TABLES[0])
        with self.assertRaises(ValueError): inspect(query)
        names[:] = ['friendbook_cf_documents']
        with self.assertRaises(ValueError): inspect(query)

    def test_truncated_listing_refuses(self):
        with self.assertRaises(ValueError):
            inspect(lambda *_: ([{'name': f'table_{i}'} for i in range(501)], {'rows_read': 501}))
