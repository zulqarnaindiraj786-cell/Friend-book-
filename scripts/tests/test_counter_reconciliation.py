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
        self.assertEqual(len(entries),2)
        entries=[e for e in entries if e['source_path']=='posts/p/comments/c']
        self.assertEqual(entries[0]['status'],'unattributed_counter_surplus')
        self.assertEqual(entries[0]['identity_count'],0)
        self.assertIn('999',entries[0]['original_json'])

    def test_negative_numeric_counter_is_not_mislabeled_as_non_numeric(self):
        result=m.decision(self.entry('unsupported_counter',-1))
        self.assertEqual(result['resolution'],'known_identities_with_invalid_numeric_counter_preserved')

    def test_authoritative_canonical_absence_does_not_import_stale_mirror_count(self):
        docs=fixture(); docs.update([document('comments/c',{'authorId':'u','postId':'p','content':'hello','createdAt':123,'likes':3})])
        rows,_=plan(docs); entries=m.comment_counters(docs,rows)
        self.assertEqual(m.legacy_aggregate_rows(entries,'batch'),[])

    def test_historical_total_is_residual_not_fabricated_actor_rows(self):
        docs=fixture()
        for path in ('posts/p/comments/c','comments/c'):
            docs.update([document(path,{'authorId':'u','postId':'p','content':'hello','createdAt':123,
                                       'likes':5,'reactions':{'known':'like'}})])
        rows,_=plan(docs); entries=m.comment_counters(docs,rows)
        metadata=m.legacy_aggregate_rows(entries,'batch')
        self.assertEqual(len(metadata),1)
        self.assertEqual(metadata[0]['unattributed_count'],4)
        self.assertEqual(metadata[0]['source_path'],'posts/p/comments/c')
        self.assertEqual(len(rows['comments'][0]['reactions']),1)

if __name__=='__main__': unittest.main()
