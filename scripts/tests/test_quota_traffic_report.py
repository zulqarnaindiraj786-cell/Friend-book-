import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('quota_traffic',ROOT/'scripts/loadtest/quota_traffic_report.py')
p=importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


def payload(rows):
    return {'data':{'viewer':{'accounts':[{'d1QueriesAdaptiveGroups':rows}]}}}


class TrafficReport(unittest.TestCase):
    def test_graphql_only_query_without_dimensions_for_total(self):
        q=p.query_text('2026-01-01T00:00:00Z','2026-01-01T00:05:00Z')
        self.assertNotIn('dimensions',q)
        self.assertIn('limit:1',q)
        self.assertIn('databaseId:',q)
        self.assertIn('orderBy:[sum_rowsRead_DESC]',p.query_text('start','end','rowsRead'))
        with self.assertRaises(ValueError): p.query_text('start','end','untrusted')
        source=(ROOT/'scripts/loadtest/quota_traffic_report.py').read_text()
        self.assertEqual(source.count('https://'),1)
        self.assertIn('https://api.cloudflare.com/client/v4/graphql',source)
        self.assertNotIn('/query',source)
        self.assertNotIn('subprocess',source)

    def test_empty_is_not_zero_and_invalid_data_fails(self):
        self.assertEqual(p.parse_groups(payload([])),[])
        for rows in ([{'sum':{'rowsRead':-1,'rowsWritten':0},'count':1}],
                     [{'sum':{'rowsRead':True,'rowsWritten':0},'count':1}],
                     [{'sum':{'rowsRead':1,'rowsWritten':0},'count':None}],
                     [{'sum':{'rowsRead':1,'rowsWritten':0},'count':1}]*2):
            with self.assertRaises(ValueError): p.parse_groups(payload(rows))
        with self.assertRaises(ValueError): p.parse_groups({'errors':[{'message':'unavailable'}]})

    def test_known_diagnostics_do_not_hide_real_friendship_writes(self):
        self.assertEqual(p.category('WITH canonical AS (...) SELECT canonical_sample,legacy_sample,bad_wrapped'),'known_diagnostic_preflight')
        self.assertEqual(p.category('SELECT NOT EXISTS(SELECT 1 FROM friendbook_cf_documents) AS allowed'),'known_diagnostic_probe')
        self.assertEqual(p.category('SELECT count(*) AS matches FROM friendbook_cf_documents'),'known_diagnostic_probe')
        self.assertEqual(p.category('EXPLAIN INSERT INTO friendbook_cf_documents VALUES (?)'),'schema_inspection')
        self.assertEqual(p.category('SELECT name FROM sqlite_schema'),'schema_inspection')
        self.assertEqual(p.category('DROP INDEX idx_example'),'schema_change')
        self.assertEqual(p.category('INSERT INTO quota_index_probe_'+('a'*32)+' VALUES (?)'),'known_diagnostic_billing_probe')
        self.assertEqual(p.category('DROP TABLE IF EXISTS quota_index_probe_'+('a'*32)),'known_diagnostic_billing_probe')
        self.assertEqual(p.category('INSERT INTO friendbook_cf_social_guards SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM friendbook_cf_documents) THEN 1 END'),'application_or_unattributed')
        self.assertEqual(p.category('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?'),'application_or_unattributed')

    def test_private_sql_never_appears_in_report_or_total(self):
        sql="SELECT document_json FROM friendbook_cf_firestore_documents WHERE parent_path='users' AND path='users/PRIVATE_USER_123'"
        row={'sum':{'rowsRead':12,'rowsWritten':0},'count':3,'dimensions':{'query':sql}}
        report=p.summarize_shapes([row],'rowsRead')
        self.assertEqual(report['observed_top_shape_subtotals'],{'application_or_unattributed':12})
        self.assertNotIn('PRIVATE_USER_123',json.dumps(report))
        self.assertNotIn('document_json',json.dumps(report))
        self.assertEqual(report['largest_shapes'][0]['tables'],['canonical_documents'])
        self.assertEqual(p.parse_groups(payload([row])),[{'sum':row['sum'],'count':3}])

    def test_top_n_coverage_not_assumed_complete(self):
        row={'sum':{'rowsRead':1,'rowsWritten':0},'count':1,'dimensions':{'query':'SELECT 1'}}
        self.assertTrue(p.summarize_shapes([row]*60,'rowsRead')['may_be_truncated'])
        self.assertFalse(p.summarize_shapes([row],'rowsRead')['may_be_truncated'])
        with self.assertRaises(ValueError): p.summarize_shapes([],'invalid')

    def test_hot_comment_shapes_have_private_data_free_labels(self):
        shape=p.safe_shape("SELECT 1 AS types_json, 2 AS total_comments, 3 AS preview_json WHERE path='private'")
        self.assertIn('engagement_preview',shape['features'])
        self.assertIn('engagement_comment_total',shape['features'])
        self.assertIn('reaction_totals',shape['features'])
        self.assertNotIn('private',json.dumps(shape))

    def test_ten_shapes_available_for_followup_without_sql_output(self):
        rows=[{'sum':{'rowsRead':i,'rowsWritten':0},'count':1,
               'dimensions':{'query':'SELECT secret FROM private_table'}} for i in range(15)]
        result=p.summarize_shapes(rows,'rowsRead')
        self.assertEqual(len(result['largest_shapes']),10)
        self.assertNotIn('private_table',json.dumps(result))

    def test_rows_returned_distinguishes_legitimate_result_volume_from_amplification(self):
        row={'sum':{'rowsRead':185,'rowsWritten':0,'rowsReturned':92},'count':1,
             'dimensions':{'query':'SELECT f.* FROM friendbook_cf_chat_participants m JOIN friendbook_cf_firestore_documents f ON f.path=m.chat_path'}}
        parsed=p.parse_groups(payload([row]),True)
        self.assertEqual(p.summarize_shapes(parsed,'rowsRead')['largest_shapes'][0]['rowsReturned'],92)
        del row['sum']['rowsReturned']
        self.assertIsNone(p.summarize_shapes(p.parse_groups(payload([row]),True),'rowsRead')['largest_shapes'][0]['rowsReturned'])
        for bad in (True,-1,'92'):
            row['sum']['rowsReturned']=bad
            with self.assertRaises(ValueError):p.parse_groups(payload([row]),True)

    def test_multiline_upserts_are_not_mislabeled_as_plain_inserts(self):
        shape=p.safe_shape('INSERT INTO friendbook_cf_documents VALUES (?,?) ON\n   CONFLICT(path) DO UPDATE SET document_json=?')
        self.assertIn('upsert',shape['features'])
        self.assertEqual(shape['tables'],['legacy_mirror'])

    def test_each_shape_gets_a_bounded_notice_instead_of_truncated_mega_annotation(self):
        row={'sum':{'rowsRead':1,'rowsWritten':0,'rowsReturned':1},'count':1,
             'dimensions':{'query':"SELECT document_json FROM friendbook_cf_documents WHERE path='SECRET'"}}
        window={'minutes':5,'start':'start','end':'end','all_query_totals':None,
                'rowsRead':p.summarize_shapes([row]*10,'rowsRead'),
                'rowsWritten':p.summarize_shapes([row]*10,'rowsWritten')}
        lines=p.notice_lines(window)
        self.assertEqual(len(lines),21)
        self.assertTrue(all(len(line)<4000 for line in lines))
        self.assertNotIn('SECRET',''.join(lines))
        self.assertNotIn('largest_shapes',lines[0])

    def test_allowlisted_writer_family_survives_without_user_or_arbitrary_alias_data(self):
        shape=p.safe_shape("INSERT INTO friendbook_cf_documents AS quota_writer_document_users(path) VALUES ('SECRET')")
        self.assertEqual(shape['writer_families'],['document:users'])
        self.assertNotIn('SECRET',str(shape))
        unknown=p.safe_shape('INSERT INTO friendbook_cf_documents AS quota_writer_PRIVATE_users(path) VALUES (?)')
        self.assertNotIn('writer_families',unknown)
        self.assertNotIn('PRIVATE',str(unknown))

    def test_inbox_sort_is_distinct_from_generic_membership_lookup(self):
        q="WITH quota_inbox_candidates AS (SELECT f.* FROM friendbook_cf_chat_participants m JOIN friendbook_cf_firestore_documents f ON f.path=m.chat_path WHERE m.participant_uid='SECRET') SELECT * FROM quota_inbox_candidates ORDER BY inbox_activity DESC LIMIT 10"
        shape=p.safe_shape(q)
        self.assertIn('chat_membership',shape['features'])
        self.assertIn('inbox_activity_sort',shape['features'])
        self.assertNotIn('SECRET',str(shape))

    def test_shared_seek_has_its_own_sanitized_feature(self):
        shape=p.safe_shape("SELECT * FROM friendbook_cf_firestore_documents f INDEXED BY idx_cf_chat_activity_seek WHERE f.path='SECRET'")
        self.assertIn('inbox_shared_activity_seek',shape['features'])
        self.assertNotIn('SECRET',str(shape))
