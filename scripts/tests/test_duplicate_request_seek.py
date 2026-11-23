"""Exercise the unchanged production UPDATE, not a simplified SELECT."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / 'cloudflare/migrations'
SOURCE = (ROOT / 'cloudflare/firestore-documents.js').read_text()
SQL = re.search(r'const duplicateLegacy=db.prepare\(`(.*?)`\)\.bind', SOURCE, re.S).group(1)
INDEX = MIGRATIONS / '0088_pending_duplicate_request_seek.sql'


class DuplicateRequestSeek(unittest.TestCase):
    def database(self):
        db = sqlite3.connect(':memory:')
        for name in ['0011_additive_app_documents.sql', '0014_additive_firestore_documents.sql',
                     '0023_friend_request_lookup_indexes.sql', '0049_friend_request_status_indexes.sql']:
            db.executescript((MIGRATIONS / name).read_text())
        return db

    def insert(self, db, path, doc, canonical=None):
        db.execute('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',
                   (path, 'friend_requests', path.split('/')[-1], '', json.dumps(doc), 1))
        if canonical is not None:
            db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',
                       (path, 'friend_requests', 'friend_requests', path.split('/')[-1], '', json.dumps(canonical), 1, 1))

    def test_exact_update_seeks_and_preserves_mixed_envelopes_and_shadowing(self):
        snapshots = []
        for indexed in (False, True):
            with self.database() as db:
                for i in range(500):
                    self.insert(db, f'friend_requests/noise{i}', {'fromUid': f'u{i}', 'toUid': 'other', 'status': 'pending'})
                plain = {'fromUid': 'alice', 'toUid': 'bob', 'status': 'pending'}
                wrapped = {'fields': {k: {'stringValue': v} for k, v in plain.items()}}
                self.insert(db, 'friend_requests/plain', plain)
                self.insert(db, 'friend_requests/wrapped', wrapped)
                self.insert(db, 'friend_requests/conflict', {**wrapped, 'fromUid': 'eve', 'toUid': 'mallory', 'status': 'rejected'})
                self.insert(db, 'friend_requests/partial', {'fields': {'fromUid': {'stringValue': 'alice'}}, 'toUid': 'bob', 'status': 'pending'})
                self.insert(db, 'friend_requests/shadow', plain, {**plain, 'status': 'rejected'})
                self.insert(db, 'friend_requests/accepted', plain, {**plain, 'status': 'accepted'})
                if indexed:
                    db.executescript(INDEX.read_text())
                    db.executescript(INDEX.read_text())
                args = (2, 'alice', 'bob', 'alice', 'bob')
                plan = '\n'.join(r[-1] for r in db.execute('EXPLAIN QUERY PLAN '+SQL, args))
                if indexed:
                    self.assertIn('SEARCH a USING INDEX idx_cf_legacy_pending_duplicate_pair', plan)
                steps = [0]
                def tick():
                    steps[0] += 1
                    return 0
                db.set_progress_handler(tick, 1)
                db.execute(SQL, args)
                db.set_progress_handler(None, 0)
                changed = [r[0] for r in db.execute('SELECT path FROM friendbook_cf_documents WHERE updated_at=2 ORDER BY path')]
                self.assertEqual(changed, [f'friend_requests/{p}' for p in ['accepted', 'conflict', 'partial', 'plain', 'wrapped']])
                snapshots.append((db.execute('SELECT * FROM friendbook_cf_documents ORDER BY path').fetchall(), steps[0]))
        self.assertEqual(snapshots[0][0], snapshots[1][0])
        self.assertLess(snapshots[1][1], snapshots[0][1])
        print('Local SQLite VM steps (NOT D1 billed reads):', snapshots[0][1], '->', snapshots[1][1])


if __name__ == '__main__':
    unittest.main()
