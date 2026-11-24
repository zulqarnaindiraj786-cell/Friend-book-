import importlib.util,sqlite3,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('comment_route_preflight',ROOT/'scripts/loadtest/comment_route_preflight.py');p=importlib.util.module_from_spec(s);s.loader.exec_module(p)
class CommentRoutePreflightTest(unittest.TestCase):
 def test_bounded_aggregate(self):
  db=sqlite3.connect(':memory:');db.executescript((ROOT/'cloudflare/migrations/0014_additive_firestore_documents.sql').read_text())
  rows=[('comments/a','comments','comments','a','a','{"postId":"private","post_id":"private","resource":"posts/private"}',0,0),('comments/b','comments','comments','b','b','{"fields":{"postId":{"stringValue":"private"}}}',0,0),('posts/c','posts','posts','c','c','{}',0,0)]
  db.executemany('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)',rows)
  self.assertEqual(db.execute(p.SQL).fetchone(),(2,4,7))
  self.assertIn('idx_friendbook_cf_firestore_parent',str(db.execute('EXPLAIN QUERY PLAN '+p.SQL).fetchall()))
 def test_invalid_fails(self):
  for row in ({'sampled':258,'text_fields':0,'route_rows_upper_bound':0},{'sampled':1,'text_fields':4,'route_rows_upper_bound':5},{'sampled':1,'text_fields':2,'route_rows_upper_bound':1}):
   with self.assertRaises(ValueError):p.summarize({'success':True,'result':[{'success':True,'results':[row]}]})
