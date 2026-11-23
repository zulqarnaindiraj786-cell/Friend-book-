import importlib.util
from pathlib import Path
import sqlite3
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('timestamp_billing',ROOT/'scripts/admin/prove_timestamp_index_billing.py')
p=importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class BillingProbe(unittest.TestCase):
    def harness(self,fail=None):
        db=sqlite3.connect(':memory:');db.row_factory=sqlite3.Row
        db.execute('CREATE TABLE real_user_data(value TEXT)');db.execute("INSERT INTO real_user_data VALUES ('untouched')")
        trace=[]
        def query(sql,params=()):
            trace.append(sql)
            cursor=db.execute(sql,params)
            if fail and sql.startswith(fail):raise ValueError('Simulated lost response')
            return {'results':[dict(r) for r in cursor.fetchall()],
                    'meta':{'rows_written':max(0,cursor.rowcount),'rows_read':0}}
        return db,trace,query

    def test_isolated_rows_cleanup_and_no_fake_billed_savings_from_sqlite_changes(self):
        db,trace,query=self.harness()
        try:
            report=p.probe(query,'a'*32)
            self.assertTrue(report['cleanup_verified'])
            self.assertFalse(report['nonchat_index_write_reduction_observed'],'logical SQLite rowcounts are not D1 index billing')
            self.assertFalse(report['daily_application_savings_proven'])
            self.assertEqual(db.execute('SELECT * FROM real_user_data').fetchone()[0],'untouched')
            self.assertEqual(db.execute("SELECT count(*) FROM sqlite_schema WHERE name LIKE 'quota_index_probe_%'").fetchone()[0],0)
            self.assertTrue(all('real_user_data' not in sql for sql in trace))
        finally:db.close()

    def test_cleanup_after_insert_error_and_lost_create_response(self):
        for failure in ('INSERT INTO','CREATE TABLE quota_index_probe_'):
            db,trace,query=self.harness(failure)
            try:
                with self.assertRaises(ValueError):p.probe(query,'b'*32)
                self.assertTrue(any(s.startswith('DROP TABLE IF EXISTS quota_index_probe_') for s in trace))
                self.assertEqual(db.execute("SELECT count(*) FROM sqlite_schema WHERE name LIKE 'quota_index_probe_%'").fetchone()[0],0)
                self.assertEqual(db.execute('SELECT count(*) FROM real_user_data').fetchone()[0],1)
            finally:db.close()

    def test_rejects_untrusted_identifier_before_any_sql(self):
        def forbidden(*args):raise AssertionError('No query allowed')
        with self.assertRaises(ValueError):p.probe(forbidden,'untrusted; DROP TABLE user_data;')
