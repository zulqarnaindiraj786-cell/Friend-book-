"""Actual SQLite plans/work for scoped root counts; not D1 billed-row estimates."""
import contextlib
import io
from pathlib import Path
import sqlite3
import unittest
from unittest.mock import patch
from scripts.loadtest import verify_root_backfill as coverage

ROOT = Path(__file__).resolve().parents[2]


class RootCoverageBudget(unittest.TestCase):
    def test_root_counts_match_without_grouping_thousands_of_nested_parents(self):
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.executescript((ROOT / 'cloudflare/migrations/0014_additive_firestore_documents.sql').read_text())
        for parent in coverage.COLLECTIONS:
            for i in range(2):
                db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',
                           (f'{parent}/id{i}', parent, parent, f'id{i}', 'owner', '{}', 1, 1))
        for i in range(3000):
            parent = f'chats/room{i}/messages'
            db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',
                       (f'{parent}/m', parent, 'messages', 'm', 'owner', '{}', 1, 1))
        def execute(sql, args):
            steps = [0]
            def tick():
                steps[0] += 1
                return 0
            db.set_progress_handler(tick, 1)
            try:
                rows = dict(db.execute(sql, args).fetchall())
            finally:
                db.set_progress_handler(None, 0)
            return rows, steps[0]
        old, old_steps = execute(coverage.HISTORICAL_CANONICAL_SQL, ())
        new, new_steps = execute(coverage.CANONICAL_SQL, coverage.COLLECTIONS)
        self.assertEqual(new, {k: old[k] for k in coverage.COLLECTIONS})
        self.assertEqual(new, dict.fromkeys(coverage.COLLECTIONS, 2))
        self.assertEqual(len(new), 13)
        self.assertLess(new_steps, old_steps / 10)
        plan = ' '.join(row[3] for row in db.execute('EXPLAIN QUERY PLAN ' + coverage.CANONICAL_SQL, coverage.COLLECTIONS))
        self.assertIn('SEARCH', plan)
        self.assertIn('idx_friendbook_cf_firestore_parent', plan)
        self.assertNotIn('SCAN friendbook_cf_firestore_documents', plan)

    def test_coverage_main_binds_root_allowlist_and_keeps_missing_root_failure(self):
        calls = []
        def query(sql, args):
            calls.append((sql, args))
            if sql == coverage.LEGACY_SQL:
                return [{'collection': 'posts', 'legacy_rows': 3, 'legacy_only': 1}], {}
            self.assertEqual(sql, coverage.CANONICAL_SQL)
            return [{'collection': 'posts', 'canonical_rows': 2}], {}
        with patch.object(coverage, 'remote_query', query), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(coverage.main(), 1)
        self.assertEqual(calls, [(coverage.LEGACY_SQL, ()), (coverage.CANONICAL_SQL, coverage.COLLECTIONS)])
        self.assertNotIn(coverage.HISTORICAL_CANONICAL_SQL, [q for q, _ in calls])

    def test_malformed_coverage_never_becomes_success_or_leaks_response(self):
        valid_legacy = [{'collection': 'posts', 'legacy_rows': 1, 'legacy_only': 0}]
        valid_canonical = [{'collection': 'posts', 'canonical_rows': 1}]
        cases = [
            (None, valid_canonical), ([None], valid_canonical),
            ([{'collection': 'SECRET'}], valid_canonical),
            ([{'collection': 'posts', 'legacy_rows': True, 'legacy_only': 0}], valid_canonical),
            ([{'collection': 'posts', 'legacy_rows': 1, 'legacy_only': -1}], valid_canonical),
            ([{'collection': 'posts', 'legacy_rows': 1, 'legacy_only': 2}], valid_canonical),
            (valid_legacy * 2, valid_canonical),
            (valid_legacy, [{'collection': 'posts'}]),
            (valid_legacy, [{'collection': 'posts', 'canonical_rows': '1'}]),
            (valid_legacy, [{'collection': 'SECRET', 'canonical_rows': 1}]),
            (valid_legacy, valid_canonical * 2),
        ]
        for legacy, canonical in cases:
            with self.subTest(legacy=legacy, canonical=canonical):
                output=io.StringIO()
                with patch.object(coverage, 'remote_query', side_effect=[(legacy, {}),(canonical, {})]), contextlib.redirect_stdout(output):
                    self.assertEqual(coverage.main(), 2)
                self.assertNotIn('SECRET', output.getvalue())
                self.assertNotIn('zero legacy-only', output.getvalue())

    def test_empty_database_is_valid_and_custom_collection_names_stay_private(self):
        for legacy, canonical in [([], []),
            ([{'collection':'SECRET_CUSTOM_COLLECTION','legacy_rows':3,'legacy_only':2}], [])]:
            output=io.StringIO()
            with patch.object(coverage,'remote_query',side_effect=[(legacy,{}),(canonical,{})]), contextlib.redirect_stdout(output):
                self.assertEqual(coverage.main(),0)
            self.assertNotIn('SECRET',output.getvalue())
            if legacy:
                self.assertIn('"collection": "other", "legacy_only": 2',output.getvalue())
