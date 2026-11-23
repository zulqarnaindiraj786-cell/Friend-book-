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
