from pathlib import Path
import sqlite3
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parents[1] / 'admin'))
import import_interaction_staging as importer
import plan_interaction_import as planner
from test_interaction_import_plan import fixture, document


class StagingTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:'); self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA foreign_keys=ON')
        self.statements = []
        def query(session, sql, params=()):
            self.statements.append(sql)
            cursor = self.db.execute(sql, params)
            return [dict(row) for row in cursor.fetchall()] if cursor.description else []
        self.patch = patch.object(importer,'d1_query',side_effect=query); self.patch.start()
    def tearDown(self): self.patch.stop(); self.db.close()
    def run_import(self, docs):
        rows, report = planner.plan(docs)
        return importer.import_rows(None,'test-batch',docs,rows,report)
    def test_complete_staging_import_readback_and_idempotent_retry(self):
        result = self.run_import(fixture())
        self.assertTrue(result['normalized_staging_verified'])
        self.assertEqual(result['resources_marked_verified'],0)
        self.assertFalse(result['safe_to_cutover'])
        self.assertEqual(self.db.execute('SELECT state FROM friendbook_cf_interaction_resources').fetchone()[0],'staging')
        self.assertEqual(self.run_import(fixture()), result)
        self.assertEqual(self.db.execute('SELECT total FROM friendbook_cf_reaction_totals').fetchone()[0],1)
        self.assertFalse(any(s.lstrip().upper().startswith(('UPDATE ', 'DELETE ', 'DROP ', 'REPLACE ')) for s in self.statements))
    def test_existing_different_row_never_overwritten(self):
        self.run_import(fixture())
        self.db.execute("UPDATE friendbook_cf_comments SET text='existing content'")
        with self.assertRaises(importer.BackupError): self.run_import(fixture())
        self.assertEqual(self.db.execute('SELECT text FROM friendbook_cf_comments').fetchone()[0],'existing content')
    def test_unsupported_mapping_stops_before_any_schema_or_data_write(self):
        docs=fixture(); docs.update([document('posts/p/unknown/item',{})])
        with self.assertRaises(importer.BackupError): self.run_import(docs)
        self.assertEqual(self.statements,[])
    def test_unknown_dates_and_orphan_are_preserved_without_fake_parent(self):
        docs=fixture(); docs.pop(planner.PREFIX+'/posts/p/reactions/u')
        docs.update([document('posts/absent/reactions/old-user',{'type':'like','createdAt':123})])
        result=self.run_import(docs)
        self.assertEqual(result['unresolved_source_reconciliation']['reaction_missing_resource'],1)
        self.assertEqual(self.db.execute('SELECT timestamp_known FROM friendbook_cf_reaction_history').fetchone()[0],0)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM friendbook_cf_import_preservation WHERE kind=?',('orphan_reaction_quarantine',)).fetchone()[0],1)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM friendbook_cf_interaction_resources').fetchone()[0],1)
    def test_counter_mismatch_retained_not_corrected_in_original_snapshot(self):
        docs=fixture(); docs.update([document('posts/p',{'likes':999,'comments':1})])
        result=self.run_import(docs)
        self.assertEqual(result['unresolved_source_reconciliation']['resource_reaction_counter_mismatch'],1)
        original=self.db.execute('SELECT original_json FROM friendbook_cf_import_preservation').fetchone()[0]
        self.assertIn('999',original)
        self.assertEqual(self.db.execute('SELECT total FROM friendbook_cf_reaction_totals').fetchone()[0],1)

if __name__ == '__main__': unittest.main()
