import importlib.util
import json
import sqlite3
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('friend_guard_preflight', ROOT / 'scripts/loadtest/friend_guard_index_preflight.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class Response:
    def __init__(self, value): self.value = value
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def read(self, *args): return json.dumps(self.value).encode()


class FriendGuardPreflightTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        for name in ('0011_additive_app_documents.sql', '0014_additive_firestore_documents.sql'):
            self.db.executescript((ROOT / 'cloudflare/migrations' / name).read_text())

    def tearDown(self): self.db.close()

    def test_capped_summary_only_and_exception_counts(self):
        # Simulate historical corruption despite the present schema constraint.
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        canonical = [('{"participants":["alice","bob"]}', 'friends'),
                     ('{"participants":["alice","bob","eve"]}', 'friends'),
                     ('{"fields":{"participants":{"arrayValue":{"values":["invalid-json"]}}}}', 'friends'),
                     ('not-json', 'friends'), ('{}', 'posts')]
        for i, (doc, parent) in enumerate(canonical):
            self.db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',
                            (f'{parent}/{i}', parent, parent, str(i), 'private', doc, 1, 1))
        self.db.execute('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',
                        ('friends/l', 'friends', 'l', 'private', '{"fields":{}}', 1))
        row = dict(zip(p.KEYS, self.db.execute(p.SQL).fetchone()))
        self.assertEqual(row, {'canonical_sample': 5, 'legacy_sample': 1,
            'canonical_friends': 4, 'legacy_friends': 1, 'canonical_invalid_json': 1,
            'legacy_invalid_json': 0, 'canonical_exceptions': 2, 'legacy_exceptions': 1,
            'canonical_bad_wrapped': 1, 'legacy_bad_wrapped': 0})
        result = p.summarize({'success': True, 'result': [{'success': True, 'results': [row], 'meta': {'rows_read': 8}}]})
        self.assertFalse(result['truncated'])
        self.assertNotIn('alice', json.dumps(result))
        self.assertNotIn('private', json.dumps(result))

    def test_limit_stops_overlarge_source_sample(self):
        self.db.executemany('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',
                            [(f'posts/{i}', 'posts', str(i), '', '{}', i) for i in range(p.CAP + 200)])
        row = dict(zip(p.KEYS, self.db.execute(p.SQL).fetchone()))
        self.assertEqual(row['legacy_sample'], p.CAP)
        self.assertTrue(p.summarize({'success': True, 'result': [{'success': True, 'results': [row]}]})['truncated'])

    def test_rejects_incomplete_or_impossible_response(self):
        empty = {k: 0 for k in p.KEYS}
        with self.assertRaises(ValueError): p.summarize({'success': False})
        with self.assertRaises(ValueError): p.summarize({'success': True, 'result': [{'success': True, 'results': [{}]}]})
        empty['canonical_friends'] = 1
        with self.assertRaises(ValueError): p.summarize({'success': True, 'result': [{'success': True, 'results': [empty]}]})
        with patch.dict('os.environ', {'CLOUDFLARE_API_TOKEN': 'synthetic'}), patch.object(p.urllib.request, 'urlopen', return_value=Response({'success': False})):
            with self.assertRaises(SystemExit): p.main()


if __name__ == '__main__': unittest.main()
