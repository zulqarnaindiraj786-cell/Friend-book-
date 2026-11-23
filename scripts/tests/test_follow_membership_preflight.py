import importlib.util
import json
from pathlib import Path
import sqlite3
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('follow_preflight',ROOT/'scripts/loadtest/follow_membership_preflight.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class Response:
    def __init__(self,row): self.row=row
    def __enter__(self): return self
    def __exit__(self,*args): pass
    def read(self,*args): return json.dumps({'success':True,'result':[{'success':True,'results':[self.row]}]}).encode()

class FollowPreflightTests(unittest.TestCase):
    def test_aggregate_and_collection_seeks(self):
        db=sqlite3.connect(':memory:')
        for name in ('0011_additive_app_documents.sql','0014_additive_firestore_documents.sql'):
            db.executescript((ROOT/'cloudflare/migrations'/name).read_text())
        db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',('users/a','users','users','a','a','{"followers":["b"],"followers":["c","d"]}',1,1))
        db.executemany('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',[
            ('users/a','users','a','a','{"followers":["b"]}',1),
            ('users/b','users','b','b','{"fields":{"followers":{"arrayValue":{"values":[{"stringValue":"a"}]}}}}',1)])
        self.assertEqual(db.execute(module.SQL).fetchone(),(1,2,1,1,1,0,3,1))
        plan=' '.join(str(row) for row in db.execute('EXPLAIN QUERY PLAN '+module.SQL))
        self.assertIn('USING INDEX idx_friendbook_cf_firestore_parent (parent_path=?)',plan)
        self.assertIn('USING INDEX idx_friendbook_cf_documents_collection_updated (collection=?)',plan)

    def test_guard_rejects_large_or_exception_heavy_sample(self):
        good={'canonical_users':436,'legacy_users':420,'legacy_only':2,'wrapped_legacy':0,'canonical_duplicates':0,'legacy_duplicates':0,'sampled_array_entries':363,'largest_array':19}
        def run(row):
            with patch.dict('os.environ',{'CLOUDFLARE_API_TOKEN':'synthetic'}),patch('sys.argv',['preflight','--require-index-build-safe']),patch.object(module.urllib.request,'urlopen',return_value=Response(row)):
                module.main()
        run(good)
        for field,value in [('canonical_users',1001),('sampled_array_entries',2001),('largest_array',101),('canonical_duplicates',65),('wrapped_legacy',65)]:
            with self.subTest(field=field),self.assertRaises(SystemExit):run({**good,field:value})

if __name__=='__main__':unittest.main()
