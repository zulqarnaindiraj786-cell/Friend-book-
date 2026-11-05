from pathlib import Path
import sqlite3
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parents[1]/'admin'))
import observe_interaction_changes as m
from test_interaction_import_plan import fixture, document
from plan_interaction_import import plan


class ObservationTests(unittest.TestCase):
    def test_created_changed_and_absent_reconstruct_exact_snapshot(self):
        before=fixture(); after=dict(before)
        absent=m.PREFIX+'/posts/p/reactions/u'; after.pop(absent)
        after.update([document('posts/p',{'likes':999}),document('posts/new',{'likes':0})])
        changes=m.differences(before,after)
        self.assertEqual({r['operation'] for r in changes},{'created','changed','absent'})
        m.verify_reconstruction(before,changes,after)
        self.assertIn(absent,before)
        self.assertNotEqual(m.snapshot_hash(before),m.snapshot_hash(after))
    def test_unchanged_snapshot_has_no_change_events(self):
        self.assertEqual(m.differences(fixture(),fixture()),[])
    def test_corrupt_change_is_rejected(self):
        before=fixture(); after=dict(before); after.update([document('posts/new',{})])
        changes=m.differences(before,after); changes[0]['after_sha256']='corrupt'
        with self.assertRaises(m.BackupError): m.verify_reconstruction(before,changes,after)
    def test_counters_are_classified_without_fabricating_users(self):
        docs=fixture(); docs.update([document('posts/p',{'likes':999,'comments':0})])
        rows,_=plan(docs); ledger=m.counter_ledger(docs,rows)
        by_metric={r['metric']:r for r in ledger}
        self.assertEqual(by_metric['likes']['status'],'unattributed_counter_surplus')
        self.assertEqual(by_metric['likes']['identity_count'],1)
        self.assertEqual(by_metric['comments']['status'],'underreported_counter')
        self.assertEqual(len(rows['reactions']),1)
    def test_append_only_records_and_seal_are_read_back_and_old_observations_retained(self):
        db=sqlite3.connect(':memory:'); db.row_factory=sqlite3.Row; statements=[]
        def query(session,sql,params=()):
            statements.append(sql); cursor=db.execute(sql,params)
            return [dict(r) for r in cursor.fetchall()] if cursor.description else []
        before=fixture(); after=dict(before); after.update([document('posts/p',{'likes':999,'comments':1})])
        rows,report=plan(after)
        with patch.object(m,'d1_query',side_effect=query), patch('import_interaction_staging.d1_query',side_effect=query):
            first=m.store_observation(None,'obs1','baseline','time',before,after,rows,report)
            m.store_observation(None,'obs2','baseline','time',before,after,rows,report)
            self.assertTrue(first['observation_verified']); self.assertFalse(first['safe_to_cutover'])
            self.assertFalse(first['is_continuous_change_capture'])
            self.assertEqual(db.execute('SELECT COUNT(*) FROM friendbook_cf_migration_observation_manifests').fetchone()[0],2)
        self.assertFalse(any(s.lstrip().upper().startswith(('UPDATE ','DELETE ','DROP ','REPLACE ')) for s in statements))
        db.close()

if __name__=='__main__': unittest.main()
