import importlib.util
from pathlib import Path
import unittest
PATH=Path(__file__).resolve().parents[1]/'admin/migrate_comments_v2.py'
spec=importlib.util.spec_from_file_location('migration',PATH);migration=importlib.util.module_from_spec(spec);spec.loader.exec_module(migration)
class LegacyCopySafety(unittest.TestCase):
    def test_copy_has_identity_and_does_not_mutate_parent(self):
        parent={'commentsMap':{'one':{'content':'old','createdAt':123}},'commentsList':[{'id':'one','content':'duplicate'},{'id':'two','content':'second'}]}
        records=migration.legacy_records(parent,'post1')
        self.assertEqual(set(records),{'one','two'});self.assertEqual(records['one']['content'],'old')
        self.assertEqual(records['two']['postId'],'post1');self.assertEqual(records['two']['createdAt'],0)
        self.assertNotIn('postId',parent['commentsMap']['one'])
    def test_invalid_map_cannot_be_silently_pruned(self):
        for value in [{'commentsMap':{'bad/id':{}}},{'commentsMap':{'one':'unsupported'}},{'commentsMap':['bad']},{'commentsList':[{'content':'no id'}]}]:
            with self.assertRaises(ValueError): migration.legacy_records(value,'post1')
    def test_write_and_prune_are_separate_explicit_opt_ins(self):
        text=PATH.read_text();self.assertIn("'--apply',action='store_true'",text);self.assertIn('if not args.prune_legacy: continue',text)
        self.assertIn('last_update_time=doc.update_time',text);self.assertIn("except AlreadyExists: pass",text)
        self.assertIn('0o600',text);self.assertIn("'--backup'",text)
