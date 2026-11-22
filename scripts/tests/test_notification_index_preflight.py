import importlib.util
import sqlite3
import unittest
from pathlib import Path

P = Path(__file__).resolve().parents[1] / 'loadtest' / 'notification_index_preflight.py'
spec = importlib.util.spec_from_file_location('notification_index_preflight', P)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class NotificationIndexPreflightTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript('''
          CREATE TABLE friendbook_cf_firestore_documents(parent_path TEXT, document_json TEXT);
          CREATE TABLE friendbook_cf_documents(collection TEXT, document_json TEXT);
          CREATE INDEX idx_cf_firestore_inbox_recipient ON friendbook_cf_firestore_documents(json_extract(document_json,'$.toUserId')) WHERE parent_path='notifications';
          CREATE INDEX idx_cf_legacy_inbox_recipient ON friendbook_cf_documents(json_extract(document_json,'$.toUserId')) WHERE collection='notifications';
          CREATE INDEX idx_cf_notif_page ON friendbook_cf_firestore_documents(parent_path);
          CREATE INDEX idx_cf_notif_unread ON friendbook_cf_firestore_documents(parent_path);
          CREATE INDEX idx_cf_legacy_notif_page ON friendbook_cf_documents(collection);
          CREATE INDEX idx_cf_legacy_notif_unread ON friendbook_cf_documents(collection);
        ''')

    def tearDown(self):
        self.db.close()

    def payload(self, row, reads=8, writes=0):
        return {'success': True, 'result': [{'success': True, 'results': [dict(zip(
            ('canonical_entries','legacy_entries','replacement_indexes'), row))],
            'meta': {'rows_read': reads, 'rows_written': writes}}]}

    def test_aggregate_excludes_other_collections_and_uses_index(self):
        for table, field in [('friendbook_cf_firestore_documents','parent_path'), ('friendbook_cf_documents','collection')]:
            self.db.executemany(f'INSERT INTO {table} ({field},document_json) VALUES (?,?)',
                                [('notifications', '{}')]*3 + [('users','{}')]*5)
        before = self.db.total_changes
        row = self.db.execute(m.SQL).fetchone()
        self.assertEqual(row, (3, 3, 4))
        self.assertEqual(self.db.total_changes, before)
        self.assertTrue(m.within_preflight_bounds(m.summarize(self.payload(row))))
        plan = ' '.join(x[3] for x in self.db.execute('EXPLAIN QUERY PLAN ' + m.SQL))
        self.assertIn('idx_cf_firestore_inbox_recipient', plan)
        self.assertIn('idx_cf_legacy_inbox_recipient', plan)

    def test_cap_missing_index_and_incomplete_response_fail_closed(self):
        self.db.executemany('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?)',
                            [('notifications','{}')]*(m.CAP+2))
        row = self.db.execute(m.SQL).fetchone()
        self.assertEqual(row[0], m.CAP)
        self.assertFalse(m.within_preflight_bounds(m.summarize(self.payload(row))))
        self.db.execute('DROP INDEX idx_cf_legacy_inbox_recipient')
        with self.assertRaises(sqlite3.OperationalError): self.db.execute(m.SQL)
        for payload in ({}, self.payload((1,2,4), writes=1), self.payload((1,2,4), reads=None), self.payload((1,2,5))):
            with self.assertRaises(ValueError): m.summarize(payload)
        self.assertFalse(m.within_preflight_bounds(m.summarize(self.payload((1,2,3)))))
        self.assertFalse(m.within_preflight_bounds(m.summarize(self.payload((1,2,4), reads=12001))))


if __name__ == '__main__':
    unittest.main()
