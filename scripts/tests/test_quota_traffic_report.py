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
    def test_create_only_notification_source_fingerprint_is_exact_and_private(self):
        source=(ROOT/'cloudflare/firestore-documents.js').read_text()
        marker="if (body.createOnly===true && splitPath(docPath)[0]==='notifications')"
        branch=source[source.index(marker):]
        branch=branch[:branch.index('// PATCH /v1/firestore/document')]
        start=branch.index('INSERT INTO friendbook_cf_firestore_documents(')
        sql=branch[start:branch.index('`',start)]
        shape=p.safe_shape(sql)
        self.assertIn('create_only_notification',shape['features'])
        self.assertEqual(shape['writer_families'],['document:notifications'])
        self.assertEqual(shape['roots'],['notifications'])
        self.assertNotIn('INSERT INTO friendbook_cf_documents',branch,
                         'create-only notifications must not pay for a duplicate legacy mirror write')
        for changed in (sql.replace("'notifications'","'users'"),
                        sql+' ON CONFLICT(path) DO NOTHING',
                        sql.replace('WHERE path=?1','WHERE path=?9')):
            self.assertNotIn('create_only_notification',p.safe_shape(changed)['features'])
        self.assertNotIn(sql,json.dumps(shape))

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

    def test_canonical_semantic_inventory_query_is_exactly_attributed(self):
        source=(ROOT/'scripts/loadtest/canonical_semantic_inventory.mjs').read_text()
        start=source.index('query(`SELECT m.path AS path,')+len('query(`')
        end=source.index('`',start)
        sql=source[start:end]
        self.assertIn('LIMIT ?',sql)
        self.assertEqual(p.category(sql),'exact_canonical_semantic_inventory_template')
        self.assertEqual(p.category(sql.replace('LIMIT ?','LIMIT 999')),'application_or_unattributed')
        report=p.summarize_shapes([{'sum':{'rowsRead':123,'rowsWritten':0},'count':1,'dimensions':{'query':sql}}],'rowsRead')
        self.assertEqual(report['observed_top_shape_subtotals'],{'exact_canonical_semantic_inventory_template':123})

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

    def test_mirror_reader_structure_is_allowlisted_and_private(self):
        sql="SELECT f.document_json FROM friendbook_cf_firestore_documents f LEFT JOIN friendbook_cf_documents m ON m.path=f.path WHERE f.owner_uid='PRIVATE_UID' AND m.secret_column='PRIVATE_VALUE'"
        shape=p.safe_shape(sql)
        self.assertIn('redacted_mirror_select_structure',shape)
        grammar=shape['redacted_mirror_select_structure']
        self.assertIn('left join',grammar)
        self.assertIn('friendbook_cf_documents',grammar)
        for secret in ('PRIVATE_UID','secret_column','PRIVATE_VALUE'):
            self.assertNotIn(secret.lower(),json.dumps(shape).lower())
        self.assertNotIn(sql,json.dumps(shape))

    def test_unmapped_canonical_structure_is_allowlisted_and_private(self):
        sql="SELECT SECRET_COLUMN, path FROM friendbook_cf_firestore_documents WHERE parent_path='SECRET_PATH' AND mystery='PRIVATE_VALUE'"
        shape=p.safe_shape(sql)
        grammar=shape['redacted_unmapped_canonical_structure']
        self.assertIn('friendbook_cf_firestore_documents',grammar)
        for secret in ('SECRET_COLUMN','SECRET_PATH','PRIVATE_VALUE','mystery'):
            self.assertNotIn(secret.lower(),json.dumps(shape).lower())
        self.assertNotIn('redacted_unmapped_canonical_structure',p.safe_shape("SELECT * FROM friendbook_cf_firestore_documents WHERE path='secret'"))

    def test_scan_family_only_accepts_exact_fixed_worker_aliases(self):
        for suffix in ('','_count','_recent','_name','_stars','_membership','_filtered','_page','_list'):
            q="SELECT path FROM friendbook_cf_firestore_documents AS quota_root_users"+suffix+" WHERE parent_path=?"
            self.assertEqual(p.safe_shape(q)['collection_scan_family'],'users'+suffix)
        q="SELECT path FROM friendbook_cf_firestore_documents AS quota_root_nested WHERE parent_path=?"
        self.assertEqual(p.safe_shape(q)['collection_scan_family'],'nested')
        for alias in ('quota_root_private','quota_root_users_private','quota_root_nested_private'):
            q='SELECT path FROM friendbook_cf_firestore_documents AS '+alias+' WHERE parent_path=?'
            self.assertNotIn('collection_scan_family',p.safe_shape(q))
            self.assertNotIn(alias,json.dumps(p.safe_shape(q)))

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

    def test_bounded_post_and_story_seek_labels_are_allowlisted_and_private(self):
        shape=p.safe_shape("SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_feed_exceptions WHERE parent_path='posts' AND path='SECRET'")
        self.assertIn('feed_exception_probe',shape['features'])
        self.assertNotIn('SECRET',json.dumps(shape))
        self.assertIn('author_story_seek',p.safe_shape('SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_story_author_page')['features'])
        self.assertIn('recent_people_index',p.safe_shape('SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_recent_people')['features'])
        self.assertIn('people_default_index',p.safe_shape('SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_people_default_page')['features'])
        self.assertIn('post_tag_fts',p.safe_shape('SELECT * FROM friendbook_cf_post_search WHERE friendbook_cf_post_search MATCH ?')['features'])
        shape=p.safe_shape('SELECT resource,state FROM friendbook_cf_interaction_resources INDEXED BY idx_cf_interaction_resource_state_cover WHERE resource IN (?,?)')
        self.assertIn('interaction_state_cover',shape['features']);self.assertIn('feed_interaction_state_batch',shape['features'])
        self.assertIn('legacy_comment_epoch_read',p.safe_shape('SELECT generation,revision FROM friendbook_cf_legacy_comment_epoch WHERE id=1')['features'])
        self.assertIn('legacy_comment_candidate_probe',p.safe_shape('WITH quota_comment_candidates_0 AS MATERIALIZED (SELECT 1) SELECT 1')['features'])
        self.assertNotIn('feed_exception_probe',p.safe_shape('SELECT idx_cf_feed_exceptions_not_real FROM private')['features'])

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

    def test_database_split_has_no_primary_database_filter_and_validates_totals(self):
        query=p.database_query_text('start','end')
        self.assertIn('dimensions { databaseId }',query)
        self.assertNotIn('databaseId:',query)
        row={'dimensions':{'databaseId':p.DATABASE},'sum':{'rowsRead':9,'rowsWritten':2},'count':3}
        payload={'data':{'viewer':{'accounts':[{'d1QueriesAdaptiveGroups':[row]}]}}}
        result=p.parse_databases(payload)
        self.assertTrue(result['databases'][0]['primary_application'])
        self.assertEqual(result['databases'][0]['rowsRead'],9)
        row['sum']['rowsWritten']=True
        with self.assertRaises(ValueError):p.parse_databases(payload)
        row['sum']['rowsWritten']=2;row['dimensions']['databaseId']='PRIVATE SQL'
        with self.assertRaises(ValueError):p.parse_databases(payload)

    def test_empty_database_split_is_not_zero_usage(self):
        result=p.parse_databases({'data':{'viewer':{'accounts':[{'d1QueriesAdaptiveGroups':[]}]}}})
        self.assertEqual(result['status'],'no_observations_available')
        with self.assertRaises(ValueError):p.parse_databases({'errors':[{'message':'no access'}]})

    def test_cross_window_summary_keeps_other_database_usage_and_unknowns_distinct(self):
        windows=[]
        for minutes in (5,15,60):
            windows.append({'minutes':minutes,'end':'end','all_query_totals':{'sum':{'rowsRead':7,'rowsWritten':2}},
              'account_database_breakdown':{'status':'observations_available','may_be_truncated':False,
               'databases':[{'primary_application':True,'rowsRead':7,'rowsWritten':2},
                            {'primary_application':False,'rowsRead':9,'rowsWritten':4}]}})
        result=p.cross_window_totals(windows)
        self.assertEqual([r['minutes'] for r in result],[5,15,60])
        self.assertEqual(result[2]['observed_other_database_subtotals'],{'rowsRead':9,'rowsWritten':4})
        self.assertLess(len(str(result)),4000)
        windows[0]['account_database_breakdown']={'status':'analytics_unavailable_not_zero','databases':[]}
        self.assertIsNone(p.cross_window_totals(windows)[0]['observed_other_database_subtotals'])


    def test_priority_shapes_reserve_each_window_before_detail_without_raw_sql(self):
        windows=[]
        groups=[{'dimensions':{'query':"SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='PRIVATE'"},
                 'sum':{'rowsRead':100-i,'rowsWritten':i},'count':2} for i in range(5)]
        for minutes in (5,15,60):
            windows.append({'minutes':minutes,'end':'end',
              **{metric:p.summarize_shapes(groups,metric) for metric in ('rowsRead','rowsWritten')}})
        lines=p.priority_shape_notices(windows)
        self.assertEqual(len(lines),6)
        self.assertNotIn('PRIVATE',str(lines))
        self.assertNotIn('SELECT document_json',str(lines))
        for i,line in enumerate(lines):
            self.assertLess(len(line),3000)
            data=json.loads(line.removeprefix('Sanitized priority shapes '))
            self.assertEqual(data['minutes'],(5,15,60)[i//2])
            self.assertEqual(len(data['shapes']),2)
            self.assertEqual(data['ranked_by'],('rowsRead','rowsWritten')[i%2])
        source=(ROOT/'scripts/loadtest/quota_traffic_report.py').read_text()
        self.assertLess(source.index("for line in priority_shape_notices(report['windows'])"),source.index("for line in notice_lines(window)"))


    def test_legacy_promotion_requires_exact_reviewed_sql_not_just_table_names(self):
        shapes=p.maintenance_shapes()
        self.assertGreaterEqual(len(shapes),3)
        promotion=next(q for q,label in shapes.items() if label=='known_legacy_root_promotion')
        self.assertEqual(p.category(promotion),'known_legacy_root_promotion')
        self.assertEqual(p.category(promotion.upper()+';'),'known_legacy_root_promotion')
        self.assertEqual(p.category(promotion.replace('returning path','returning doc_id')),'application_or_unattributed')
        self.assertEqual(p.category('INSERT INTO friendbook_cf_firestore_documents SELECT * FROM friendbook_cf_documents'),'application_or_unattributed')
        for query,label in shapes.items():
            self.assertEqual(p.category(query),label)
        self.assertNotIn('document_json',str(p.safe_shape(promotion)))


    def test_coverage_templates_are_parsed_without_import_or_remote_calls(self):
        shapes=p.maintenance_shapes()
        coverage=[q for q,label in shapes.items() if label=='exact_coverage_inventory_template']
        preflights=[q for q,label in shapes.items() if label=='exact_index_preflight_template']
        self.assertEqual(len(coverage),4)
        self.assertEqual(len(preflights),5)
        for query in coverage+preflights:
            self.assertNotEqual(p.category(query),'application_or_unattributed')
            if query in coverage:
                self.assertEqual(p.category(query+' AND PRIVATE_DIFFERENCE=1'),'application_or_unattributed')
        self.assertEqual(p.category('SELECT COUNT(*) FROM friendbook_cf_firestore_documents'),'application_or_unattributed')


    def test_structural_scan_labels_never_echo_arbitrary_aliases_or_literals(self):
        shape=p.safe_shape("SELECT COUNT(*) AS canonical_count, SUM(x) AS PRIVATE_ALIAS FROM friendbook_cf_documents LEFT JOIN friendbook_cf_firestore_documents ON 1=1 WHERE x='PRIVATE_VALUE' GROUP BY x LIMIT 1")
        self.assertEqual(shape['recognized_aliases'],['canonical_count'])
        self.assertTrue({'count_aggregate','sum_aggregate','grouped','left_join','row_limit'} <= set(shape['features']))
        self.assertNotIn('PRIVATE',str(shape))


    def test_exact_template_normalization_ignores_formatting_but_preserves_values(self):
        self.assertEqual(p.normalize_shape('SELECT count(*) AS sampled FROM t WHERE a=?1;'),
                         p.normalize_shape('select COUNT ( * ) as sampled from t where a = ?1'))
        self.assertNotEqual(p.normalize_shape("SELECT 'A b'"),p.normalize_shape("SELECT 'A  b'"))
        self.assertNotEqual(p.normalize_shape("SELECT 'Case'"),p.normalize_shape("SELECT 'case'"))
        self.assertNotEqual(p.normalize_shape('SELECT ?1'),p.normalize_shape('SELECT ?2'))


    def test_inventory_structure_never_exposes_values_identifiers_or_comments(self):
        sql="SELECT COUNT(*) AS sampled, SUM(CASE WHEN f.path IS NULL THEN 1 ELSE 0 END) AS legacy_only FROM friendbook_cf_documents PRIVATE_TABLE LEFT JOIN friendbook_cf_firestore_documents f ON f.path='SECRET_ID' WHERE a=123 -- SECRET_COMMENT"
        shape=p.safe_shape(sql)
        grammar=shape['redacted_inventory_structure']
        self.assertIn('count ( * ) as sampled',grammar)
        for private in ('PRIVATE_TABLE','SECRET_ID','123','SECRET_COMMENT'):
            self.assertNotIn(private,grammar)
        self.assertNotIn('SECRET',p.redacted_inventory_structure("SELECT 'a--SECRET', \"SECRET\", `SECRET`, [SECRET] /*SECRET*/"))
        self.assertLessEqual(len(p.redacted_inventory_structure(sql*100)),1800)


    def test_single_historical_window_exposes_ten_read_and_six_write_shapes(self):
        shape={'category':'application_or_unattributed','operation':'SELECT','tables':[],
               'features':[],'rowsRead':1,'rowsWritten':0,'executions':1}
        window={'minutes':1440,'end':'end',**{metric:{'may_be_truncated':True,'largest_shapes':[dict(shape) for _ in range(10)]}
                for metric in ('rowsRead','rowsWritten')}}
        lines=p.priority_shape_notices([window])
        self.assertEqual(len(lines),8)
        counts={'rowsRead':0,'rowsWritten':0}
        for line in lines:
            data=json.loads(line.removeprefix('Sanitized priority shapes '))
            counts[data['ranked_by']]+=len(data['shapes'])
            self.assertLess(len(line),3500)
        self.assertEqual(counts,{'rowsRead':10,'rowsWritten':6})


    def test_unmapped_select_structure_keeps_only_fixed_vocabulary(self):
        result=p.safe_shape("SELECT SECRET_COLUMN FROM SECRET_TABLE WHERE uid='SECRET_UID' LIMIT 250")
        grammar=result['redacted_unmapped_select_structure']
        self.assertNotIn('SECRET',grammar)
        self.assertNotIn('250',grammar)
        self.assertIn('limit ?',grammar)
        views=p.safe_shape('SELECT resource, views FROM friendbook_cf_view_counters ORDER BY views DESC LIMIT ?')
        self.assertEqual(views['tables'],['view_counters'])
        self.assertIn('views desc limit ?',views['redacted_unmapped_select_structure'])


    def test_retired_table_quoted_identifiers_are_fixed_vocabulary_only(self):
        grammar=p.redacted_inventory_structure('SELECT * FROM "friendbook_cf_presence" ORDER BY "rowid" LIMIT 250 OFFSET 500')
        self.assertEqual(grammar,'select * from friendbook_cf_presence order by rowid limit ? offset ?')
        self.assertNotIn('SECRET',p.redacted_inventory_structure('SELECT * FROM "SECRET" ORDER BY "SECRET" LIMIT 250'))


    def test_retired_backup_attribution_requires_exact_historical_template(self):
        query='SELECT * FROM friendbook_cf_presence_sessions ORDER BY rowid LIMIT ? OFFSET ?'
        self.assertEqual(p.category(query),'exact_historical_retired_backup_template')
        for altered in (query.replace('SELECT *','SELECT uid'),query.replace('LIMIT ?', 'LIMIT 250'),
                        query.replace('presence_sessions','private_table')):
            self.assertNotEqual(p.category(altered),'exact_historical_retired_backup_template')
