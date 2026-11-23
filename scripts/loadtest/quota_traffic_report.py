#!/usr/bin/env python3
"""GraphQL-only quota report. No D1 SQL, app requests, scans, or deployments.

Separate recognizable maintenance/probe shapes from unattributed traffic.
Top-N shape subtotals are NOT complete totals and must never be subtracted from
account billing. Unknown/empty/error analytics are never reported as zero usage.
"""
import argparse
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


def category(sql):
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


def safe_shape(sql):
    # Fixed vocabulary only; never emit SQL, bindings, IDs or document values.
    q=re.sub(r'\s+',' ',sql).strip().lower()
    op=re.match(r'\s*(select|with|insert|update|delete|create|drop|alter|explain)\b',q)
    roots=[r for r in ('users','posts','comments','friends','friend_requests','chats','notifications','reels','lives')
           if re.search(r"\b(?:parent_path|collection|collection_id)\s*=\s*'"+r+"'",q)]
    features=[label for token,label in [('friendbook_cf_social_guards','transaction_guard'),
        ('friendbook_cf_chat_participants','chat_membership'),('quota_inbox_candidates','inbox_activity_sort'),('friendbook_cf_comment','comment'),
        ('idx_cf_friend_participant_0','indexed_friend_guard'),('where path','path_lookup'),('as preview_json','engagement_preview'),
        ('as total_comments','engagement_comment_total'),('as types_json','reaction_totals'),
        ('idx_cf_canonical_comment_page_','historical_comment_page'),
        ('count(*) as n from friendbook_cf_firestore_documents','historical_comment_count'),
        ('on conflict','upsert'),('do nothing','conflict_do_nothing'),
        ('values (case when','snapshot_guarded_insert')]
        if token in q]
    tables=[label for table,label in (
        ('friendbook_cf_firestore_documents','canonical_documents'),
        ('friendbook_cf_documents','legacy_mirror'),
        ('friendbook_cf_social_guards','transaction_guards'),
        ('friendbook_cf_fcm_devices','fcm_devices'),
        ('friendbook_cf_fcm_device_claims_v2','fcm_claims'),
        ('friendbook_cf_comments','comments'),
        ('friendbook_cf_reactions','reactions')) if re.search(r'\b'+table+r'\b',q)]
    # Recognize only aliases emitted from the Worker's fixed vocabulary.
    # Never echo an arbitrary SQL identifier or assume it identifies a device.
    callers=[]
    for writer in ('document','chat_root','chat_message','broadcast','legacy','batch','other'):
        for root in ('users','posts','reels','stories','chats','messages','groups','pages','notifications','lives','other'):
            if re.search(r'\bas quota_writer_'+writer+'_'+root+r'\b',q):
                callers.append(writer+':'+root)
    result={'operation':op.group(1).upper() if op else 'OTHER','tables':tables,'roots':roots,'features':features}
    if callers: result['writer_families']=callers
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
    for metric in ('rowsRead','rowsWritten'):
        header[metric]={k:v for k,v in window[metric].items() if k!='largest_shapes'}
    lines=['Sanitized quota traffic window '+json.dumps(header,sort_keys=True)]
    for metric in ('rowsRead','rowsWritten'):
        for rank,shape in enumerate(window[metric]['largest_shapes'],1):
            lines.append('Sanitized quota shape '+json.dumps({
                'minutes':window['minutes'],'start':window['start'],'end':window['end'],
                'ranked_by':metric,'rank':rank,**shape},sort_keys=True))
    return lines


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    token=os.environ['CLOUDFLARE_API_TOKEN']
    def fetch(query,shapes=False):
        req=urllib.request.Request('https://api.cloudflare.com/client/v4/graphql',data=json.dumps({'query':query}).encode(),
            headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=30) as response: payload=json.load(response)
        return parse_groups(payload,shapes)
    now=dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    fmt=lambda t:t.strftime('%Y-%m-%dT%H:%M:%SZ')
    report={'queried_at':fmt(now),'source':'Cloudflare delayed/adaptive query analytics',
            'warning':'Not account billing, device attribution, matched-session savings or a guaranteed quota balance. Top-shape subtotals may be incomplete.',
            'windows':[]}
    for minutes in (5,15,60):
        start,end=fmt(now-dt.timedelta(minutes=minutes)),fmt(now)
        totals=fetch(query_text(start,end))
        window={'minutes':minutes,'start':start,'end':end,'all_query_totals':totals[0] if totals else None,
                'status':'observations_available' if totals else 'no_observations_available'}
        for metric in ('rowsRead','rowsWritten'):
            window[metric]=summarize_shapes(fetch(query_text(start,end,metric),True),metric)
        report['windows'].append(window)
        for line in notice_lines(window): print('::notice::'+line)
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')


if __name__=='__main__':
    main()
