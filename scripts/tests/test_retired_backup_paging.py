"""Offline regression tests; never call remote D1."""
import hashlib
import json
import pathlib
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from scripts.loadtest import backup_retired_tables as backup


class RetiredBackupPagingTest(unittest.TestCase):
    def test_seek_preserves_all_rows_and_checksum(self):
        for size in (0, 1, backup.PAGE, backup.PAGE * 3 + 7):
            with self.subTest(size=size), sqlite3.connect(':memory:') as db:
                db.row_factory = sqlite3.Row
                table = backup.TABLES[0]
                db.execute(f'CREATE TABLE {table}(uid TEXT, payload TEXT)')
                # Include negative and sparse rowids, not just positive counters.
                for i in range(size):
                    db.execute(f'INSERT INTO {table}(rowid,uid,payload) VALUES(?,?,?)',
                               (-1000 + i * 3, str(i), 'hello ü'))
                expected = b''.join((json.dumps(dict(row), sort_keys=True, separators=(',', ':')) + '\n').encode()
                                    for row in db.execute(f'SELECT * FROM {table} ORDER BY rowid'))
                calls = []

                def remote(sql, args):
                    self.assertNotIn('OFFSET', sql)
                    calls.append((sql, args))
                    return [dict(row) for row in db.execute(sql, args)], {}

                with patch.object(backup, 'remote_query', side_effect=remote), tempfile.TemporaryDirectory() as tmp:
                    directory = pathlib.Path(tmp)
                    self.assertEqual(backup.write_backup(table, directory), size)
                    self.assertEqual((directory / f'{table}.jsonl').read_bytes(), expected)
                    self.assertEqual(backup.checksum_pages(table), (size, hashlib.sha256(expected).hexdigest()))
                self.assertTrue(all('WHERE rowid > ?' in sql for sql, args in calls if len(args) == 2))

    def test_rejects_unknown_table_without_remote_call(self):
        with patch.object(backup, 'remote_query') as remote:
            with self.assertRaises(ValueError):
                list(backup.backup_pages('not_an_allowed_table'))
            remote.assert_not_called()

    def test_rejects_nonadvancing_cursor(self):
        with patch.object(backup, 'remote_query', return_value=([
                {'__friendbook_backup_rowid': 1, 'uid': 'a'},
                {'__friendbook_backup_rowid': 1, 'uid': 'b'}], {})):
            with self.assertRaises(ValueError):
                list(backup.backup_pages(backup.TABLES[0]))

    def test_seek_vm_budget_beats_historical_offset_without_losing_rows(self):
        with sqlite3.connect(':memory:') as db:
            db.row_factory = sqlite3.Row
            table = backup.TABLES[0]
            db.execute(f'CREATE TABLE {table}(uid TEXT, payload TEXT)')
            db.executemany(f'INSERT INTO {table} VALUES (?,?)',
                           ((str(i), 'payload') for i in range(5000)))
            steps = [0]
            def tick():
                steps[0] += 1
                return 0
            db.set_progress_handler(tick, 1)
            historical = []
            for offset in range(0, 5001, backup.PAGE):
                historical.extend(dict(row) for row in db.execute(
                    f'SELECT * FROM {table} ORDER BY rowid LIMIT ? OFFSET ?', (backup.PAGE, offset)))
            offset_work = steps[0]
            steps[0] = 0
            def remote(sql, args):
                return [dict(row) for row in db.execute(sql, args)], {}
            with patch.object(backup, 'remote_query', side_effect=remote):
                current = [row for page in backup.backup_pages(table) for row in page]
            self.assertEqual(current, historical)
            # Local SQLite VM work, NOT D1 billed-row savings or production proof.
            self.assertLess(steps[0], offset_work // 2)
