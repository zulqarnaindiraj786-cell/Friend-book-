import importlib.util
from pathlib import Path
import sqlite3
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('owner_retirement',ROOT/'scripts/admin/retire_global_owner_index.py')
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class OwnerIndexRetirement(unittest.TestCase):
    def test_exact_schema_and_production_probe_plans_before_after(self):
        with sqlite3.connect(':memory:', cached_statements=0) as db:
            db.row_factory=sqlite3.Row
            for path in sorted((ROOT/'cloudflare/migrations').glob('*.sql')):
                if path.name<'0089_': db.executescript(path.read_text())
            # Production already has 0090's chat-root replacement but still
            # retains the owner-only index because its retirement gate failed.
            db.executescript((ROOT/'cloudflare/migrations/0090_scope_chat_timestamp_index.sql').read_text())
            def indexes():
                return [dict(r) for r in db.execute("SELECT name,sql FROM sqlite_schema WHERE type='index'")]
            self.assertTrue(m.validate_indexes(indexes()))
            inserts=[]
            for stage in ('before','after'):
                m.validate_plans([[r['detail'] for r in db.execute('EXPLAIN QUERY PLAN '+q,args)] for q,args in m.PROBES])
                inserts.append(sum(r['opcode']=='IdxInsert' for r in db.execute('EXPLAIN '+m.INSERT)))
                if stage=='before': db.executescript((ROOT/'cloudflare/migrations/0089_remove_redundant_global_owner_index.sql').read_text())
            self.assertFalse(m.validate_indexes(indexes()))
            self.assertEqual(inserts[0]-inserts[1],1)
            self.assertEqual(db.execute('SELECT count(*) FROM friendbook_cf_firestore_documents').fetchone()[0],0)
            rows=indexes()
            for missing in m.REQUIRED:
                with self.assertRaises(ValueError): m.validate_indexes([r for r in rows if r['name']!=missing])
            with self.assertRaises(ValueError): m.validate_indexes(rows+[{'name':m.INDEX,'sql':'CREATE INDEX wrong ON wrong(x)'}])
            with self.assertRaises(ValueError): m.validate_plans([['SCAN table']]*4)
