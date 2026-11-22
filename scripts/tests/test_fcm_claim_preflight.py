import importlib.util
import json
from pathlib import Path
import sqlite3
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('fcm_claim_preflight',ROOT/'scripts/loadtest/fcm_claim_preflight.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class Response:
    def __init__(self,row):self.row=row
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def read(self,*args):return json.dumps({'success':True,'result':[{'success':True,'results':[self.row]}]}).encode()

class FcmClaimPreflightTests(unittest.TestCase):
    def test_bounded_aggregate(self):
        db=sqlite3.connect(':memory:')
        db.execute('CREATE TABLE friendbook_cf_fcm_device_claims(event_key TEXT PRIMARY KEY,created_at INTEGER)')
        db.executemany('INSERT INTO friendbook_cf_fcm_device_claims VALUES(?,?)',[(str(i),i) for i in range(2100)])
        self.assertEqual(db.execute(module.SQL).fetchone(),(2001,))
        self.assertIn('COVERING INDEX',' '.join(str(r) for r in db.execute('EXPLAIN QUERY PLAN '+module.SQL)))

    def test_build_guard_refuses_truncation_and_bad_counts(self):
        def run(sampled):
            with patch.dict('os.environ',{'CLOUDFLARE_API_TOKEN':'synthetic'}),patch('sys.argv',['preflight','--require-build-safe']),patch.object(module.urllib.request,'urlopen',return_value=Response({'sampled':sampled})):
                module.main()
        run(2000)
        for value in (2001,2002,-1,True,'2',None):
            with self.subTest(value=value),self.assertRaises(SystemExit):run(value)

if __name__=='__main__':unittest.main()
