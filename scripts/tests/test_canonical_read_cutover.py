import sqlite3
import unittest
from pathlib import Path

from scripts.audit_canonical_read_arms import audit

ROOT = Path(__file__).resolve().parents[2]


class CanonicalReadCutover(unittest.TestCase):
    def test_no_legacy_read_arms_in_cutover_modules(self):
        self.assertEqual(audit()['total'], 0)

    def test_comment_indexes_are_additive_and_repeatable(self):
        with sqlite3.connect(':memory:') as db:
            db.executescript((ROOT / 'cloudflare/migrations/0014_additive_firestore_documents.sql').read_text())
            db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',
                       ('comments/c', 'comments', 'comments', 'c', 'alice', '{"postId":"p","createdAt":12}', 1, 1))
            migration = (ROOT / 'cloudflare/migrations/0087_canonical_comment_page_indexes.sql').read_text()
            db.executescript(migration)
            db.executescript(migration)
            self.assertEqual(db.execute('SELECT count(*) FROM friendbook_cf_firestore_documents').fetchone()[0], 1)
            indexes = db.execute("SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'idx_cf_canonical_comment_page_%'").fetchall()
            self.assertEqual(len(indexes), 3)
