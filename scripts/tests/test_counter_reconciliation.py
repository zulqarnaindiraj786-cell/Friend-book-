from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).parents[1]/'admin'))
import reconcile_interaction_counters as m
from test_interaction_import_plan import fixture, document
from plan_interaction_import import plan

class CounterReviewTests(unittest.TestCase):
    def entry(self,status,value):
        return {'source_path':'posts/p','metric':'likes','original_json':m.canonical({'present':True,'value':value}),
                'identity_count':2,'status':status,'source_document_sha256':'synthetic'}
    def test_underreported_cache_preserves_original_without_inventing_users(self):
        original=self.entry('underreported_counter',1)
        result=m.decision(original)
        self.assertEqual(result['identity_count'],2)
        self.assertEqual(result['original_json'],original['original_json'])
        self.assertEqual(result['resolution'],'known_identities_with_original_counter_preserved')
    def test_surplus_stays_unresolved(self):
        self.assertEqual(m.decision(self.entry('unattributed_counter_surplus',999))['resolution'],'requires_identity_or_schema_review')
    def test_unknown_container_requires_review_not_blind_drop(self):
        self.assertEqual(m.decision(self.entry('unsupported_counter',{'legacy':'data'}))['resolution'],'requires_identity_or_schema_review')
    def test_non_numeric_scalar_not_coerced_to_fake_identity_count(self):
        result=m.decision(self.entry('unsupported_counter','999'))
        self.assertEqual(result['identity_count'],2)
        self.assertEqual(result['source_value_kind'],'str')
        self.assertEqual(result['resolution'],'known_identities_with_non_numeric_scalar_preserved')
    def test_comment_counter_aliases_and_mirrors_stay_preserved(self):
        docs=fixture()
        docs.update([document('posts/p/comments/c',{'authorId':'u','content':'hello','createdAt':123,'likes':999})])
        rows,_=plan(docs)
        entries=m.comment_counters(docs,rows)
        self.assertEqual(len(entries),1)
        self.assertEqual(entries[0]['status'],'unattributed_counter_surplus')
        self.assertEqual(entries[0]['identity_count'],0)
        self.assertIn('999',entries[0]['original_json'])

if __name__=='__main__': unittest.main()
