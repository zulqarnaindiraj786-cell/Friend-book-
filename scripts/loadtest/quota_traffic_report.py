#!/usr/bin/env python3
"""GraphQL-only quota report. No D1 SQL, app requests, scans, or deployments.

Separate recognizable maintenance/probe shapes from unattributed traffic.
Top-N shape subtotals are NOT complete totals and must never be subtracted from
account billing. Unknown/empty/error analytics are never reported as zero usage.
"""
import argparse
import ast
from functools import lru_cache
import datetime as dt
import json
import os
from pathlib import Path
import re
import urllib.request

ACCOUNT='92f3d04598822de2e49e14561218d090'
DATABASE='e1c605ca-a50b-46c6-9deb-56dd1f70d163'


def query_text(start,end,metric=None):
    if metric not in (None,'rowsRead','rowsWritten'): raise ValueError('Invalid metric')
    ordering='' if metric is None else f', orderBy:[sum_{metric}_DESC]'
    dimensions='' if metric is None else 'dimensions { query }'
    return '''{ viewer { accounts(filter:{accountTag:"%s"}) {
      d1QueriesAdaptiveGroups(limit:%d%s, filter:{databaseId:"%s",datetime_geq:"%s",datetime_leq:"%s"}) {
        %s sum { rowsRead rowsWritten rowsReturned } count
      }
    } } }'''%(ACCOUNT,1 if metric is None else 60,ordering,DATABASE,start,end,dimensions)


def database_query_text(start,end):
    return '''{ viewer { accounts(filter:{accountTag:"%s"}) {
      d1QueriesAdaptiveGroups(limit:100, orderBy:[sum_rowsRead_DESC],
        filter:{datetime_geq:"%s",datetime_leq:"%s"}) {
        dimensions { databaseId } sum { rowsRead rowsWritten } count
      }
    } } }'''%(ACCOUNT,start,end)


def parse_databases(payload):
    if payload.get('errors'): raise ValueError('Database analytics rejected')
    accounts=payload['data']['viewer']['accounts']
    if len(accounts)!=1: raise ValueError('Database analytics account unavailable')
    groups=accounts[0]['d1QueriesAdaptiveGroups']
    if not isinstance(groups,list) or len(groups)>100: raise ValueError('Invalid database groups')
    results=[]
    for row in groups:
        database=row.get('dimensions',{}).get('databaseId')
        if not isinstance(database,str) or not re.fullmatch(r'[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}',database):
            raise ValueError('Invalid database identifier')
        values=row.get('sum',{})
        if any(type(values.get(k)) is not int or values[k]<0 for k in ('rowsRead','rowsWritten')) or type(row.get('count')) is not int or row['count']<0:
            raise ValueError('Invalid database totals')
        results.append({'database_id':database.lower(),'primary_application':database.lower()==DATABASE,
                        'rowsRead':values['rowsRead'],'rowsWritten':values['rowsWritten'],'executions':row['count']})
    if len({r['database_id'] for r in results})!=len(results):raise ValueError('Duplicate database group')
    return {'status':'observations_available' if groups else 'no_observations_available',
            'may_be_truncated':len(groups)==100,'databases':results}


def parse_groups(payload,shapes=False):
    if payload.get('errors'): raise ValueError('Analytics rejected')
    accounts=payload['data']['viewer']['accounts']
    if len(accounts)!=1: raise ValueError('Analytics account unavailable')
    groups=accounts[0]['d1QueriesAdaptiveGroups']
    if not isinstance(groups,list) or (not shapes and len(groups)>1): raise ValueError('Invalid groups')
    for row in groups:
        if any(type(row['sum'].get(k)) is not int or row['sum'][k]<0 for k in ('rowsRead','rowsWritten')):
            raise ValueError('Invalid aggregate totals')
        if type(row.get('count')) is not int or row['count']<0: raise ValueError('Invalid execution count')
        if shapes and not isinstance(row.get('dimensions',{}).get('query'),str): raise ValueError('Missing query shape')
    for row in groups:
        if 'rowsReturned' in row['sum'] and (type(row['sum']['rowsReturned']) is not int or row['sum']['rowsReturned']<0):
            raise ValueError('Invalid returned-row count')
    return [{'sum':{**{k:r['sum'][k] for k in ('rowsRead','rowsWritten')},
                     **({'rowsReturned':r['sum']['rowsReturned']} if shapes and 'rowsReturned' in r['sum'] else {})},'count':r['count'],
             **({'dimensions':{'query':r['dimensions']['query']}} if shapes else {})} for r in groups]



def normalize_shape(sql):
    # Ignore formatting only, not punctuation, bind slots or literal values.
    tokens=re.findall(r"'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|`[^`]*`|\[[^\]]*\]|\?[0-9]*|[A-Za-z_][A-Za-z0-9_]*|[0-9]+(?:\.[0-9]+)?|<>|!=|<=|>=|\|\||[^\s]",sql)
    if tokens and tokens[-1]==';':tokens.pop()
    return ' '.join(t if t.startswith(("'",'"','`','[')) else t.lower() for t in tokens)


@lru_cache(maxsize=1)
def maintenance_shapes():
    # Exact repo SQL matches only; never infer migration work from table names.
    # If the script/template changes unexpectedly, leave the shape unattributed.
    source=(Path(__file__).resolve().parent/'promote_legacy_roots.mjs').read_text()
    fields=re.search(r"const FIELDS = \[(.*?)\];",source)
    insert=re.search(r"sql: `([^`]+ON CONFLICT\(path\) DO NOTHING RETURNING path)`",source)
    known={}
    if fields and insert:
        names=re.findall(r"'([a-z_]+)'",fields.group(1))
        query=insert.group(1).replace("${FIELDS.join(',')}",','.join(names))
        if names and '${' not in query:
            known[normalize_shape(query)]='known_legacy_root_promotion'
    for name in ('PAGE','INVENTORY'):
        template=re.search(r'(?:export )?const '+name+r' = `([^`]+)`;',source)
        if template and '${' not in template.group(1):
            known[normalize_shape(template.group(1))]='known_legacy_root_inventory'
    # The canonical semantic inventory deliberately reads both stores in
    # bounded pages. Attribute only its exact reviewed query template so an
    # audit run is not mistaken for an application mirror caller.
    semantic=(Path(__file__).resolve().parent/'canonical_semantic_inventory.mjs').read_text()
    page_query=re.search(r"query\(`(SELECT m\.path AS path,[^`]+LIMIT \?)`\s*,\s*\[cursor,pageSize\]\)",semantic)
    if page_query and '${' not in page_query.group(1):
        known[normalize_shape(page_query.group(1))]='exact_canonical_semantic_inventory_template'
    # Read constants via AST, never import maintenance modules (imports can
    # initialize remote clients). Only these reviewed SQL constants qualify.
    templates={
        'canonical_inventory.py':(('SQL',),'exact_coverage_inventory_template'),
        'verify_root_backfill.py':(('LEGACY_SQL','CANONICAL_SQL','HISTORICAL_CANONICAL_SQL'),'exact_coverage_inventory_template'),
        'notification_index_preflight.py':(('SQL',),'exact_index_preflight_template'),
        'legacy_comment_preflight.py':(('SQL',),'exact_index_preflight_template'),
        'friend_guard_index_preflight.py':(('SQL',),'exact_index_preflight_template'),
        'follow_membership_preflight.py':(('SQL',),'exact_index_preflight_template'),
        'follow_fast_exception_preflight.py':(('SQL',),'exact_index_preflight_template'),
    }
    for filename,(names,label) in templates.items():
        tree=ast.parse((Path(__file__).resolve().parent/filename).read_text())
        for node in tree.body:
            if isinstance(node,ast.Assign) and isinstance(node.value,ast.Constant) and isinstance(node.value.value,str):
                if any(isinstance(target,ast.Name) and target.id in names for target in node.targets):
                    known[normalize_shape(node.value.value)]=label
    # Historical e305b41 backup/checksum template, replaced by rowid seeks in
    # 7f76bb4. Attribution only: these strings are never submitted to D1.
    for table in ('friendbook_cf_presence','friendbook_cf_presence_sessions',
                  'friendbook_cf_presence_sessions_fullcopy','friendbook_cf_presence_sessions_copy',
                  'friendbook_cf_typing','friendbook_cf_typing_sessions','friendbook_cf_typing_sessions_fullcopy'):
        known[normalize_shape(f'SELECT * FROM {table} ORDER BY rowid LIMIT ? OFFSET ?')]='exact_historical_retired_backup_template'
    return known


def category(sql):
    exact=maintenance_shapes().get(normalize_shape(sql))
    if exact:return exact
    q=re.sub(r'\s+',' ',sql).strip().lower()
    if re.search(r'\b(?:into|from|table|index)\s+(?:if\s+(?:not\s+)?exists\s+)?quota_index_probe_[a-f0-9]{32}(?:_(?:old|new))?\b',q): return 'known_diagnostic_billing_probe'
    if re.match(r'^(explain\b|(?:select|with)\b.*\bsqlite_(?:schema|master)\b)',q): return 'schema_inspection'
    if re.match(r'^(create|drop|alter)\b',q): return 'schema_change'
    if q.startswith('with ') and all(k in q for k in ('canonical_sample','legacy_sample','bad_wrapped')):
        return 'known_diagnostic_preflight'
    if q.startswith('select ') and 'friendbook_cf_' in q:
        if re.match(r'^select count\(\*\) as (?:candidates|matches) from ',q): return 'known_diagnostic_probe'
        if re.search(r'\bas (?:allowed|dangerous|candidates|matches)\s*;?$',q): return 'known_diagnostic_probe'
    return 'application_or_unattributed'



def redacted_inventory_structure(sql):
    # Diagnostic grammar from a fixed vocabulary, NOT raw SQL. Literals,
    # arbitrary identifiers, numeric values and comments never pass through.
    vocabulary=set("resource update views friendbook_cf_view_counters event_key created_at friendbook_cf_fcm_device_claims friendbook_cf_chat_participants chat_path uid friendbook_cf_trusted_friendships uid_low uid_high friend_path select count sum coalesce case when then else end as from where set and or not is null left join on indexed by limit order group having asc desc with materialized exists distinct in sampled legacy_only path collection parent_path doc_id document_json owner_uid updated_at update_time sample l f s a friendbook_cf_documents friendbook_cf_firestore_documents idx_friendbook_cf_documents_collection_updated idx_friendbook_cf_firestore_parent".split())
    pattern=r"'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|`[^`]*`|\[[^\]]*\]|--[^\n]*|/\*[\s\S]*?\*/|\?[0-9]*|[A-Za-z_][A-Za-z0-9_]*|[0-9]+(?:\.[0-9]+)?|<>|!=|<=|>=|[^\s]"
    vocabulary.update(('rowid','_rowid_','offset','friendbook_cf_presence','friendbook_cf_presence_sessions',
        'friendbook_cf_presence_sessions_fullcopy','friendbook_cf_presence_sessions_copy',
        'friendbook_cf_typing','friendbook_cf_typing_sessions','friendbook_cf_typing_sessions_fullcopy'))
    result=[]
    for token in re.findall(pattern,sql):
        if token.startswith(('--','/*')):continue
        lower=token.lower()
        # Quoted identifiers may expose only this same reviewed vocabulary.
        if token[:1] in ('\"','`','[') and token[-1:] in ('\"','`',']'):
            lower=token[1:-1].lower()
        if lower in vocabulary:result.append(lower)
        elif token in ('(',')',',','.','=','<','>','<>','!=','<=','>=','*','+','-','/'):result.append(token)
        else:result.append('?')
    return ' '.join(result)[:1800]


def safe_shape(sql):
    # Fixed vocabulary only; never emit SQL, bindings, IDs or document values.
    q=re.sub(r'\s+',' ',sql).strip().lower()
    op=re.match(r'\s*(select|with|insert|update|delete|create|drop|alter|explain)\b',q)
    roots=[r for r in ('users','posts','comments','friends','friend_requests','chats','notifications','reels','lives')
           if re.search(r"\b(?:parent_path|collection|collection_id)\s*=\s*'"+r+"'",q)]
    features=[label for token,label in [('friendbook_cf_social_guards','transaction_guard'),
        ('friendbook_cf_chat_participants','chat_membership'),('quota_inbox_candidates','inbox_activity_sort'),('idx_cf_chat_activity_seek','inbox_shared_activity_seek'),('friendbook_cf_comment','comment'),
        ('idx_cf_friend_participant_0','indexed_friend_guard'),('where path','path_lookup'),('as preview_json','engagement_preview'),
        ('as total_comments','engagement_comment_total'),('as types_json','reaction_totals'),
        ('idx_cf_canonical_comment_page_','historical_comment_page'),
        ('count(*) as n from friendbook_cf_firestore_documents','historical_comment_count'),
        ('on conflict','upsert'),('do nothing','conflict_do_nothing'),
        ('values (case when','snapshot_guarded_insert'),
        ('count(','count_aggregate'),('sum(','sum_aggregate'),('group by','grouped'),
        ('left join','left_join'),('not exists','anti_join'),(' union ','compound_select'),
        ('limit ','row_limit'),('json_each(','json_array_iteration'),
        ('json_extract(','json_field_predicate')]
        if token in q]
    # Fixed SQL index identifiers only; do not emit query text or bind values.
    for index,label in (
        ('idx_cf_feed_exceptions','feed_exception_probe'),
        ('idx_cf_recent_people','recent_people_index'),
        ('idx_cf_people_default_page','people_default_index'),
        ('idx_cf_feed_high_rank_exceptions','timestamp_head_guard'),
        ('idx_cf_interaction_resource_state_cover','interaction_state_cover'),
        ('idx_cf_posts_feed_page','posts_feed_seek'),
        ('idx_cf_posts_author_page','author_post_seek'),
        ('idx_cf_group_posts_page','group_post_seek'),
        ('idx_cf_group_pending_page','group_pending_seek'),
        ('idx_cf_group_posts_exceptions','group_exception_probe'),
        ('idx_cf_video_post_page','video_post_seek'),
        ('idx_cf_story_author_page','author_story_seek'),
        ('idx_cf_story_author_exceptions','author_story_exception_probe'),
        ('idx_cf_story_expiry_page','story_expiry_seek'),
        ('idx_cf_story_expiry_exceptions','story_expiry_exception_probe')):
        if re.search(r'\bindexed\s+by\s+'+index+r'\b',q):features.append(label)
    if re.search(r'\bfriendbook_cf_post_search\s+match\b',q):features.append('post_tag_fts')
    if re.search(r'\bfrom\s+friendbook_cf_legacy_comment_epoch\b',q):features.append('legacy_comment_epoch_read')
    if re.search(r'\bquota_comment_candidates_[0-9]+\s+as\s+materialized\b',q):features.append('legacy_comment_candidate_probe')
    if re.search(r'\bfrom\s+friendbook_cf_interaction_resources\b',q) and re.search(r'\bwhere\s+resource\s+in\s*\(',q):features.append('feed_interaction_state_batch')
    tables=[label for table,label in (
        ('friendbook_cf_firestore_documents','canonical_documents'),
        ('friendbook_cf_documents','legacy_mirror'),
        ('friendbook_cf_social_guards','transaction_guards'),
        ('friendbook_cf_fcm_devices','fcm_devices'),
        ('friendbook_cf_fcm_device_claims_v2','fcm_claims'),
        ('friendbook_cf_comments','comments'),
        ('friendbook_cf_reactions','reactions'),
        ('friendbook_cf_view_counters','view_counters'),
        ('friendbook_cf_fcm_device_claims','legacy_fcm_claims'),
        ('friendbook_cf_chat_participants','chat_participants'),
        ('friendbook_cf_trusted_friendships','trusted_friendships')) if re.search(r'\b'+table+r'\b',q)]
    # Recognize only aliases emitted from the Worker's fixed vocabulary.
    # Never echo an arbitrary SQL identifier or assume it identifies a device.
    callers=[]
    for writer in ('document','chat_root','chat_message','broadcast','legacy','batch','other'):
        for root in ('users','posts','reels','stories','chats','messages','groups','pages','notifications','lives','other'):
            if re.search(r'\bas quota_writer_'+writer+'_'+root+r'\b',q):
                callers.append(writer+':'+root)
    result={'operation':op.group(1).upper() if op else 'OTHER','tables':tables,'roots':roots,'features':features}
    # The create-only notification branch predates writer aliases. Recognize
    # only its complete fixed column/VALUES structure, not arbitrary literals.
    # This is a source-family fingerprint, never a device/action count.
    notification_create = re.search(
        r"^insert into friendbook_cf_firestore_documents\s*\(path,\s*parent_path,\s*collection_id,\s*doc_id,\s*owner_uid,\s*document_json,\s*create_time,\s*update_time\)\s*"
        r"values\s*\(case when not exists\s*\(select 1 from friendbook_cf_firestore_documents where path=\?1\)\s*"
        r"then \?1 else null end,\s*"
        r"'notifications',\s*'notifications',\s*\?2,\s*\?3,\s*\?4,\s*\?5,\s*\?5\)\s*;?$", q)
    if notification_create:
        features.append('create_only_notification')
        roots.append('notifications')
        callers.append('document:notifications')
    if callers: result['writer_families']=callers
    # The Worker labels generic collection scans with reviewed, constant
    # aliases. Do not derive roots from bind values or arbitrary identifiers.
    scan_roots=('users','posts','reels','stories','pages','groups','chats','friend_requests','comments','likes',
                'followers','following','saves','payments','subscriptions','reports','feedback','gifts','lives','messages','other','nested')
    for root in scan_roots:
        suffixes=('_count','_recent','_name','_stars','_membership','_filtered','_page','_list','') if root=='users' else ('',)
        for suffix in suffixes:
            if re.search(r'\bas quota_root_'+root+suffix+r'\b',q):result['collection_scan_family']=root+suffix
    # Fixed diagnostic labels only, never arbitrary aliases or SQL text.
    aliases=[name for name in ('canonical','legacy','current','mirror','sampled','legacy_only',
                              'canonical_rows','legacy_rows','canonical_count','legacy_count','remaining','total','n')
             if re.search(r'\bas '+name+r'\b',q)]
    if aliases:result['recognized_aliases']=aliases
    if set(('sampled','legacy_only')) <= set(aliases) and set(('canonical_documents','legacy_mirror')) <= set(tables):
        result['redacted_inventory_structure']=redacted_inventory_structure(sql)
    if result['operation'] in ('SELECT','WITH') and 'legacy_mirror' in tables:
        # High-volume mirror readers are the key physical-retirement blocker.
        # Emit only the fixed-vocabulary SQL grammar so query families can be
        # matched to diagnostics without exposing arbitrary columns/identifiers.
        result['redacted_mirror_select_structure']=redacted_inventory_structure(sql)
    if result['operation']=='UPDATE':
        # Attribute the remaining high-volume D1 write family without exporting
        # bound values or arbitrary SQL identifiers.
        result['redacted_update_structure']=redacted_inventory_structure(sql)
    if result['operation']=='SELECT' and not (set(tables)&{'canonical_documents','legacy_mirror','comments','reactions'}):
        result['redacted_unmapped_select_structure']=redacted_inventory_structure(sql)
    # Help attribute a high-volume otherwise unrecognized canonical SELECT.
    # The allowlisted grammar never emits literals or arbitrary identifiers;
    # this describes query structure, not an actor or source attribution.
    if result['operation']=='SELECT' and tables==['canonical_documents'] and not features and not roots:
        result['redacted_unmapped_canonical_structure']=redacted_inventory_structure(sql)
    return result


def summarize_shapes(groups,metric):
    if metric not in ('rowsRead','rowsWritten'): raise ValueError('Invalid metric')
    buckets={}
    for row in groups:
        key=category(row['dimensions']['query'])
        buckets[key]=buckets.get(key,0)+row['sum'][metric]
    top=sorted(groups,key=lambda r:r['sum'][metric],reverse=True)[:10]
    return {'observed_top_shape_subtotals':buckets,'shape_count':len(groups),
            'may_be_truncated':len(groups)>=60,
            'largest_shapes':[{'category':category(r['dimensions']['query']),**safe_shape(r['dimensions']['query']),
                               'rowsRead':r['sum']['rowsRead'],'rowsWritten':r['sum']['rowsWritten'],
                               'rowsReturned':r['sum'].get('rowsReturned'),'executions':r['count']}
                              for r in top]}


def notice_lines(window):
    # GitHub truncates long annotation messages. Keep the summary and each
    # already-sanitized shape separate so write attribution is not cut off.
    header={**window}
    breakdown=header.pop('account_database_breakdown',None)
    for metric in ('rowsRead','rowsWritten'):
        header[metric]={k:v for k,v in window[metric].items() if k!='largest_shapes'}
    lines=['Sanitized quota traffic window '+json.dumps(header,sort_keys=True)]
    if breakdown is not None:
        lines.append('Sanitized database breakdown status '+json.dumps({'minutes':window['minutes'],**{k:v for k,v in breakdown.items() if k!='databases'}},sort_keys=True))
        for database in breakdown['databases']:
            lines.append('Sanitized database traffic '+json.dumps({'minutes':window['minutes'],'start':window['start'],'end':window['end'],**database},sort_keys=True))
    for metric in ('rowsRead','rowsWritten'):
        for rank,shape in enumerate(window[metric]['largest_shapes'],1):
            lines.append('Sanitized quota shape '+json.dumps({
                'minutes':window['minutes'],'start':window['start'],'end':window['end'],
                'ranked_by':metric,'rank':rank,**shape},sort_keys=True))
    return lines


def cross_window_totals(windows):
    results=[]
    for window in windows:
        split=window.get('account_database_breakdown',{})
        databases=split.get('databases',[])
        known=split.get('status')=='observations_available'
        def subtotal(primary):
            return {metric:sum(row[metric] for row in databases if row['primary_application']==primary)
                    for metric in ('rowsRead','rowsWritten')} if known else None
        results.append({'minutes':window['minutes'],'end':window['end'],'primary_totals':window['all_query_totals'],
                        'database_split_status':split.get('status','unavailable'),
                        'observed_database_count':len(databases) if known else None,
                        'may_be_truncated':split.get('may_be_truncated'),
                        'observed_primary_database_subtotals':subtotal(True),'observed_other_database_subtotals':subtotal(False)})
    return results



def priority_shape_notices(windows):
    # A single historical window gets a wider investigation: ten read shapes,
    # six write shapes, two per notice, plus the totals notice = nine slots.
    # Multiple recent windows retain two shapes each within CI's notice budget.
    lines=[]
    for window in windows:
        for metric in ('rowsRead','rowsWritten'):
            depth=(10 if metric=='rowsRead' else 6) if len(windows)==1 else 2
            selected=window[metric]['largest_shapes'][:depth]
            for offset in range(0,len(selected),2):
                shapes=[dict(shape) for shape in selected[offset:offset+2]]
                for shape in shapes:
                    for key in ('redacted_inventory_structure','redacted_unmapped_canonical_structure','redacted_unmapped_select_structure','redacted_mirror_select_structure'):
                        if key in shape:shape[key]=shape[key][:700]
                lines.append('Sanitized priority shapes '+json.dumps({
                    'minutes':window['minutes'],'end':window['end'],'ranked_by':metric,
                    'rank_start':offset+1,'may_be_truncated':window[metric]['may_be_truncated'],
                    'shapes':shapes},sort_keys=True))
    return lines


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--window-minutes',type=int,nargs='+',default=[5,15,60],
                        help='Analytics lookback windows, 1 to 1440 minutes; never executes D1 SQL')
    args=parser.parse_args()
    if any(m<1 or m>1440 for m in args.window_minutes):
        parser.error('window minutes must be between 1 and 1440')
    token=os.environ['CLOUDFLARE_API_TOKEN']
    def fetch(query,shapes=False,databases=False):
        req=urllib.request.Request('https://api.cloudflare.com/client/v4/graphql',data=json.dumps({'query':query}).encode(),
            headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=30) as response: payload=json.load(response)
        return parse_databases(payload) if databases else parse_groups(payload,shapes)
    now=dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    fmt=lambda t:t.strftime('%Y-%m-%dT%H:%M:%SZ')
    report={'queried_at':fmt(now),'source':'Cloudflare delayed/adaptive query analytics',
            'warning':'Not account billing, device attribution, matched-session savings or a guaranteed quota balance. Top-shape subtotals may be incomplete.',
            'windows':[]}
    for minutes in dict.fromkeys(args.window_minutes):
        start,end=fmt(now-dt.timedelta(minutes=minutes)),fmt(now)
        totals=fetch(query_text(start,end))
        window={'minutes':minutes,'start':start,'end':end,'all_query_totals':totals[0] if totals else None,
                'status':'observations_available' if totals else 'no_observations_available'}
        for metric in ('rowsRead','rowsWritten'):
            window[metric]=summarize_shapes(fetch(query_text(start,end,metric),True),metric)
        try:
            window['account_database_breakdown']=fetch(database_query_text(start,end),databases=True)
        except (OSError,ValueError,KeyError,TypeError):
            window['account_database_breakdown']={'status':'analytics_unavailable_not_zero','databases':[],'may_be_truncated':None}
            print('::warning::Account database split unavailable; do not interpret missing databases as zero usage.')
        report['windows'].append(window)
    # CI can cap annotations per step: put ALL window totals first, rather
    # than losing 15/60-minute evidence behind the five-minute shape notices.
    print('::notice::Sanitized cross-window totals '+json.dumps(cross_window_totals(report['windows']),sort_keys=True))
    for line in priority_shape_notices(report['windows']): print('::notice::'+line)
    for window in report['windows']:
        for line in notice_lines(window): print('::notice::'+line)
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')


if __name__=='__main__':
    main()
