import importlib.util
from pathlib import Path
import sqlite3
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('chat_timestamp',ROOT/'scripts/admin/scope_chat_timestamp_index.py')
p=importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class ChatTimestampIndex(unittest.TestCase):
    def test_real_schema_and_queries_preserve_rows_without_nonchat_index_entries(self):
        with sqlite3.connect(':memory:',cached_statements=0) as db:
            db.row_factory=sqlite3.Row
            for file in sorted((ROOT/'cloudflare/migrations').glob('*.sql')):
                if file.name<'0090_':db.executescript(file.read_text())
            def indexes():return [dict(r) for r in db.execute("SELECT name,sql FROM sqlite_schema WHERE type='index'")]
            self.assertEqual(p.validate_schema(indexes()),(True,False))
            for i in range(100):
                parent='chats' if i%5==0 else 'notifications'
                db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',(parent+'/'+str(i),parent,parent,str(i),'alice','{}',i,i))
            before=[dict(r) for r in db.execute(p.CHAT,(20,))]
            # All 100 rows occupy the old global index; only 20 chat rows should
            # occupy its replacement. Physical cells are NOT D1 billed writes.
            old_cells=db.execute('SELECT sum(ncell) FROM dbstat WHERE name=?',(p.OLD,)).fetchone()[0]
            self.assertEqual(old_cells,100)
            db.executescript((ROOT/'cloudflare/migrations/0090_scope_chat_timestamp_index.sql').read_text())
            self.assertEqual(p.validate_schema(indexes()),(False,True))
            p.validate_chat_plan([dict(r) for r in db.execute('EXPLAIN QUERY PLAN '+p.CHAT,(20,))])
            self.assertEqual([dict(r) for r in db.execute(p.CHAT,(20,))],before)
            self.assertEqual(db.execute('SELECT sum(ncell) FROM dbstat WHERE name=?',(p.NEW,)).fetchone()[0],20)
            self.assertEqual(db.execute('SELECT count(*) FROM friendbook_cf_firestore_documents').fetchone()[0],100)

    def test_schema_and_plan_mismatches_fail_closed(self):
        with self.assertRaises(ValueError):p.validate_schema([])
        with self.assertRaises(ValueError):p.validate_schema([{'name':p.OLD,'sql':'CREATE INDEX wrong ON wrong(x)'}])
        with self.assertRaises(ValueError):p.validate_chat_plan([{'detail':'SCAN table; USE TEMP B-TREE FOR ORDER BY'}])
