import sqlite3
import unittest
from pathlib import Path
from scripts.loadtest.retired_table_preflight import inspect, TABLES, CAP


class RetiredTablePreflightTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.addCleanup(self.db.close)

    def query(self, sql, params):
        return [dict(row) for row in self.db.execute(sql, params)], {'rows_read': 0}

    def test_empty_missing_and_small_tables(self):
        self.db.execute(f'CREATE TABLE {TABLES[0]}(value INTEGER)')
        self.db.executemany(f'INSERT INTO {TABLES[0]} VALUES (?)', [(1,), (2,)])
        result = inspect(self.query)
        self.assertEqual(result['table_counts'][TABLES[0]], 2)
        self.assertEqual(sum(result['table_counts'].values()), 2)
        self.assertEqual(set(result['table_counts']), set(TABLES))

    def test_truncation_and_total_cap_refuse(self):
        self.db.execute(f'CREATE TABLE {TABLES[0]}(value INTEGER)')
        self.db.executemany(f'INSERT INTO {TABLES[0]} VALUES (1)', [()] * CAP)
        with self.assertRaises(ValueError): inspect(self.query)
        self.db.execute(f'DELETE FROM {TABLES[0]} WHERE rowid > 3000')
        self.db.execute(f'CREATE TABLE {TABLES[1]}(value INTEGER)')
        self.db.executemany(f'INSERT INTO {TABLES[1]} VALUES (1)', [()] * 2011)
        with self.assertRaises(ValueError): inspect(self.query)

    def test_trigger_or_external_reference_refuses(self):
        self.db.execute(f'CREATE TABLE {TABLES[0]}(value INTEGER)')
        self.db.execute(f'CREATE TRIGGER unexpected AFTER DELETE ON {TABLES[0]} BEGIN SELECT 1; END')
        with self.assertRaisesRegex(ValueError, 'trigger'): inspect(self.query)
        self.db.execute('DROP TRIGGER unexpected')
        self.db.execute(f'CREATE TABLE related(value INTEGER REFERENCES {TABLES[0]}(value))')
        with self.assertRaisesRegex(ValueError, 'referencing'): inspect(self.query)

    def test_index_weight_is_bounded(self):
        self.db.execute(f'CREATE TABLE {TABLES[0]}(value INTEGER)')
        self.db.executemany(f'INSERT INTO {TABLES[0]} VALUES (1)', [()] * 5000)
        for index in range(6):
            self.db.execute(f'CREATE INDEX idx{index} ON {TABLES[0]}(value)')
        with self.assertRaisesRegex(ValueError, 'index weight'): inspect(self.query)

    def test_missing_read_metadata_refuses(self):
        with self.assertRaises(ValueError):
            inspect(lambda sql, params: ([], {}))

    def test_deployment_gate_applies_before_branch_specific_preflights(self):
        source = (Path(__file__).resolve().parents[2] / '.github/workflows/deploy-cloudflare.yml').read_text()
        gate = source.index("if printf '%s\\n' \"$pending\" | grep -q '0086_remove_unused_status_tables.sql'")
        branch = source.index('if [ "$GITHUB_REF" = refs/heads/arena/7c7ddb7b-friend-book ]; then', gate)
        apply = source.index('npx wrangler d1 migrations apply', branch)
        section = source[gate:branch]
        self.assertIn('[drop-retired-status]', section)
        self.assertIn('retired_table_preflight.py', section)
        self.assertEqual(section.count('d1_account_usage_analytics.py --retired-drop-budget'), 2)
        self.assertLess(branch, apply)
