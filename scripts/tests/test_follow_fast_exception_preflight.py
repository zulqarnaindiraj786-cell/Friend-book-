import importlib.util
import json
from pathlib import Path
import sqlite3
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('fast_follow_preflight',ROOT/'scripts/loadtest/follow_fast_exception_preflight.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class Response:
    def __init__(self,row):self.row=row
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def read(self,*args):return json.dumps({'success':True,'result':[{'success':True,'results':[self.row]}]}).encode()

class FollowFastPreflightTests(unittest.TestCase):
    def test_simple_and_exception_counts(self):
        db=sqlite3.connect(':memory:')
        for name in ('0011_additive_app_documents.sql','0014_additive_firestore_documents.sql'):
            db.executescript((ROOT/'cloudflare/migrations'/name).read_text())
        db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',('users/a','users','users','a','a','{"followers":["b"]}',1,1))
        db.execute('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',('users/a','users','a','a','{"bio":"hello\\nworld","following":["x","y"]}',1))
        self.assertEqual(db.execute(module.SQL).fetchone(),(2,1,1,0,1,3,2))
        plan=' '.join(str(row) for row in db.execute('EXPLAIN QUERY PLAN '+module.SQL))
        self.assertIn('USING INDEX idx_friendbook_cf_firestore_parent (parent_path=?)',plan)
        self.assertIn('USING INDEX idx_friendbook_cf_documents_collection_updated (collection=?)',plan)

    def test_guard_limits_backfill_and_exceptions(self):
        good={'sampled':856,'canonical':436,'legacy':420,'canonical_exception':9,'legacy_exception':10,'sampled_array_entries':363,'largest_array':19}
        def run(row):
            with patch.dict('os.environ',{'CLOUDFLARE_API_TOKEN':'synthetic'}),patch('sys.argv',['preflight','--require-index-build-safe']),patch.object(module.urllib.request,'urlopen',return_value=Response(row)):
                module.main()
        run(good)
        for field,value in [('canonical',1001),('canonical_exception',65),('legacy_exception',65),('sampled_array_entries',2001),('largest_array',101)]:
            with self.subTest(field=field),self.assertRaises(SystemExit):run({**good,field:value})

if __name__=='__main__':unittest.main()
