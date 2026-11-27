import importlib.util
import sqlite3
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('feed_exception_preflight',ROOT/'scripts/loadtest/feed_exception_preflight.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)

class FeedExceptionPreflightTest(unittest.TestCase):
 def test_aggregate_is_capped_indexed_and_excludes_values(self):
  db=sqlite3.connect(':memory:')
  for n in ['0011_additive_app_documents','0014_additive_firestore_documents','0051_feed_exception_indexes']:
   db.executescript((ROOT/'cloudflare/migrations'/f'{n}.sql').read_text())
  rows=[('missing','{}'),('null','{"createdAt":null}'),('bool','{"createdAt":false}'),('text','{"createdAt":"private"}'),('object','{"createdAt":{"x":1}}'),('safe','{"createdAt":100}')]
  db.executemany('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',[(f'posts/{i}','posts','posts',i,'owner',v,0,0) for i,v in rows])
  self.assertEqual(db.execute(p.SQL).fetchone(),(5,3,1,0,1,0))
  plan=str(db.execute('EXPLAIN QUERY PLAN '+p.SQL).fetchall())
  self.assertIn('idx_cf_feed_exceptions',plan)
  self.assertNotIn('private',str(p.summarize({'success':True,'result':[{'success':True,'results':[{'sampled':5,'low_rank':3,'text_values':1,'numeric_values':0,'structured_values':1,'unusual_ids':0}]}]})))
 def test_invalid_or_inconsistent_aggregate_fails_closed(self):
  for row in ({'sampled':130,'low_rank':130,'text_values':0,'numeric_values':0,'structured_values':0,'unusual_ids':0},
              {'sampled':1,'low_rank':0,'text_values':0,'numeric_values':0,'structured_values':0,'unusual_ids':0},
              {'sampled':1,'low_rank':True,'text_values':0,'numeric_values':0,'structured_values':0,'unusual_ids':0}):
   with self.assertRaises(ValueError):p.summarize({'success':True,'result':[{'success':True,'results':[row]}]})
