import sqlite3, unittest
from pathlib import Path
from scripts.loadtest.restore_document_mirror import restore
ROOT=Path(__file__).resolve().parents[2]
class RetirementTest(unittest.TestCase):
 def test_snapshot_drop_restore_exact_rows_and_indexes(self):
  db=sqlite3.connect(':memory:');db.row_factory=sqlite3.Row;mdir=ROOT/'cloudflare/migrations'
  for p in sorted(mdir.glob('*.sql')):
   if p.name=='0105_retire_legacy_document_mirror.sql':break
   db.executescript(p.read_text())
  db.execute('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)',('users/test','users','test','owner','{"uid":"test"}',42));db.commit()
  db.executescript((mdir/'0105_retire_legacy_document_mirror.sql').read_text())
  self.assertIsNone(db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents'").fetchone())
  def query(sql,params=()):
   cur=db.execute(sql,params)
   if cur.description:return [dict(r) for r in cur.fetchall()]
   db.commit();return []
  self.assertEqual(restore(query),{'restored':True,'rows':1,'backup_retained':True})
  self.assertEqual(tuple(db.execute('SELECT * FROM friendbook_cf_documents').fetchone()),('users/test','users','test','owner','{"uid":"test"}',42))
  with self.assertRaises(ValueError):restore(query)
  # Rehearse a table reappearing after 0105: 0106 re-verifies exact parity to
  # the retained snapshot, then enforces DROP again.
  db.executescript((mdir/'0106_enforce_document_mirror_retirement.sql').read_text())
  self.assertIsNone(db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents'").fetchone())
if __name__=='__main__':unittest.main()
