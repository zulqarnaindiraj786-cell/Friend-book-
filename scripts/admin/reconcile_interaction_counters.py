"""Append-only staging reconciliation review. Never changes counters, app rows or gates."""
from collections import Counter
import json
import math
import os
import sys
import uuid
from copy_interaction_snapshot import PREFIX, BackupError, canonical, digest, d1_query
from import_interaction_staging import ensure_row
from observe_interaction_changes import counter_ledger, snapshot_hash
from plan_interaction_import import fields, load_snapshot, plan


def decision(entry):
    original = json.loads(entry['original_json'])
    status = entry['status']
    value = original['value']
    if status == 'matches': result='matches'
    elif status == 'underreported_counter': result='known_identities_with_original_counter_preserved'
    elif status == 'counter_absent': result='known_identities_with_absence_preserved'
    elif status == 'unsupported_counter' and isinstance(value,(int,float)) and not isinstance(value,bool):
        result='known_identities_with_invalid_numeric_counter_preserved'
    elif status == 'unsupported_counter' and not isinstance(value,(dict,list)):
        # Android Format.intOf does not treat Boolean/String/null as a numeric count.
        result='known_identities_with_non_numeric_scalar_preserved'
    else: result='requires_identity_or_schema_review'
    return {**entry,'source_value_kind':type(value).__name__,'resolution':result}


def comment_counters(docs, rows):
    by_key={(r['resource'],r['id']):r for r in rows.get('comments',[])}
    result=[]
    def add(source_path, resource, cid, data, raw):
        row=by_key.get((resource,cid))
        if not row: return
        value=data.get('likes'); amount=len(row['reactions'])
        valid=isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value) and value>=0 and value==int(value)
        status='counter_absent' if 'likes' not in data else 'unsupported_counter' if not valid else 'matches' if value==amount else 'underreported_counter' if value<amount else 'unattributed_counter_surplus'
        result.append({'source_path':source_path,'resource':resource,'comment_id':cid,'metric':'comment_likes',
                       'original_json':canonical({'present':'likes' in data,'value':value}),
                       'identity_count':amount,'status':status,'source_document_sha256':digest(raw)})
    for name,raw in sorted(docs.items()):
        path=name[len(PREFIX)+1:]; parts=path.split('/'); data=fields(json.loads(raw))
        if len(parts)==4 and parts[0] in ('posts','reels') and parts[2]=='comments':
            add(path,'/'.join(parts[:2]),parts[3],data,raw)
        elif len(parts)==2 and parts[0]=='comments':
            target=data.get('postId',data.get('post_id',''))
            candidates=[(r,cid) for r,cid in by_key if cid==parts[1] and
                        (r=='posts/'+str(target) or (str(target).startswith('reel_') and r=='reels/'+str(target)[5:]))]
            if len(candidates)==1: add(path,*candidates[0],data,raw)
        elif len(parts)==2 and parts[0] in ('posts','reels'):
            for cid,item in (data.get('commentsMap') or {}).items():
                add(path+'#commentsMap/'+cid,path,cid,item,raw)
            for index,item in enumerate(data.get('commentsList') or []):
                add(path+'#commentsList/'+str(index),path,item.get('id'),item,raw)
            item=data.get('lastComment')
            if isinstance(item,dict): add(path+'#lastComment',path,item.get('id'),item,raw)
    return result


def select_comment_counter_sources(entries):
    grouped={}
    def priority(entry):
        path=entry['source_path']; resource=entry['resource']; cid=entry['comment_id']
        rank=(5 if path==resource+'/comments/'+cid else 4 if path.endswith('#lastComment')
              else 3 if '#commentsMap/' in path else 2 if '#commentsList/' in path else 1)
        index=int(path.rsplit('/',1)[1]) if '#commentsList/' in path else 0
        return (-rank,index,path)
    for entry in entries:
        key=entry['resource'],entry['comment_id']
        if key not in grouped or priority(entry)<priority(grouped[key]): grouped[key]=entry
    return grouped


def legacy_aggregate_rows(entries,batch):
    result=[]
    for (resource,cid),entry in sorted(select_comment_counter_sources(entries).items()):
        value=json.loads(entry['original_json'])['value']
        if isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value) and value>=0:
            residual=max(0,int(value)-entry['identity_count'])
            if residual:
                result.append({'resource':resource,'id':cid,'unattributed_count':residual,
                               'source_path':entry['source_path'],'source_sha256':entry['source_document_sha256'],
                               'snapshot_batch':batch})
    return result


def verify_identity_tables(session, rows):
    expected={
        'friendbook_cf_interaction_resources':[{k:r[k] for k in ('resource','state')} for r in rows['resources']],
        'friendbook_cf_reactions':[{k:r[k] for k in ('resource','uid','type','updated_at')} for r in rows['reactions']],
        'friendbook_cf_comments':[{k:r[k] for k in ('resource','id','uid','text','parent_id','created_at')} for r in rows['comments']],
        'friendbook_cf_comment_reactions':[{'resource':r['resource'],'id':r['id'],'uid':actor,'type':kind}
                                         for r in rows['comments'] for actor,kind in r['reactions'].items()],
    }
    for table,wanted in expected.items():
        columns=tuple(wanted[0]) if wanted else ('resource',)
        actual=[]; offset=0
        while True:
            page=d1_query(session,f'SELECT {",".join(columns)} FROM {table} ORDER BY {",".join(columns)} LIMIT 100 OFFSET ?',(offset,))
            actual.extend(page)
            if len(page)<100: break
            offset+=len(page)
        if sorted(map(canonical,actual))!=sorted(map(canonical,wanted)):
            raise BackupError('Staging identity/content mismatch; no counter resolution saved')


def main():
    try:
        import requests
        with requests.Session() as session:
            session.headers['Authorization']='Bearer '+os.environ['CLOUDFLARE_API_TOKEN']
            batch,docs=load_snapshot(session)
            manifests=d1_query(session,'SELECT observation_id,manifest_json,sha256 FROM friendbook_cf_migration_observation_manifests ORDER BY read_time DESC LIMIT 1')
            if len(manifests)!=1: raise BackupError('A sealed post-snapshot observation is required')
            manifest=manifests[0]; text=manifest['manifest_json']; observation=json.loads(text)
            if digest(text)!=manifest['sha256'] or not observation.get('observation_verified') or observation.get('baseline_batch')!=batch:
                raise BackupError('Observation seal mismatch')
            if observation['observed_snapshot_hash']!=snapshot_hash(docs):
                raise BackupError('Observed changes require explicit replay before baseline counter review')
            rows,report=plan(docs)
            hard=set(report['blockers'])-{'comment_reaction_counter_mismatch','resource_comment_counter_mismatch','resource_reaction_counter_mismatch','reaction_missing_resource'}
            if hard: raise BackupError('Unresolved identity/mapping blockers')
            verify_identity_tables(session,rows)
            comment_entries=comment_counters(docs,rows)
            authorities=select_comment_counter_sources(comment_entries)
            desired_aggregates={(r['resource'],r['id']):r for r in legacy_aggregate_rows(comment_entries,batch)}
            available=d1_query(session,"SELECT name FROM sqlite_master WHERE type='table' AND name='friendbook_cf_comment_legacy_aggregates'")
            decisions=[decision(r) for r in counter_ledger(docs,rows)+comment_entries]
            for item in decisions:
                if item['metric']!='comment_likes' or item['resolution']!='requires_identity_or_schema_review': continue
                key=item['resource'],item['comment_id']
                if item['source_path']!=authorities[key]['source_path']:
                    item['resolution']='non_authoritative_cached_variant_preserved_in_archive'
                    continue
                wanted=desired_aggregates.get(key)
                if available and wanted:
                    actual=d1_query(session,'SELECT resource,id,unattributed_count,source_path,source_sha256,snapshot_batch FROM friendbook_cf_comment_legacy_aggregates WHERE resource=? AND id=?',key)
                    if actual==[wanted]: item['resolution']='historical_unattributed_aggregate_preserved_without_fabricated_actors'
            unresolved=sum(r['resolution']=='requires_identity_or_schema_review' for r in decisions)
            summary={'review_policy_version':2,'baseline_batch':batch,'observation_id':manifest['observation_id'],
                     'observed_read_time':observation['read_time'],'staging_identity_verified':True,
                     'decisions':dict(Counter(r['resolution'] for r in decisions)),
                     'unsupported_source_value_types':dict(Counter(r['source_value_kind'] for r in decisions if r['status']=='unsupported_counter')),
                     'unresolved_counter_cases':unresolved,
                     'orphan_reactions_preserved':report['blockers'].get('reaction_missing_resource',0),
                     'old_writer_fence_deployed':False,'application_rows_modified':0,
                     'firebase_writes':0,'deletions':0,'safe_to_cutover':False}
            d1_query(session,'''CREATE TABLE IF NOT EXISTS friendbook_cf_counter_reconciliation_reviews (
                review_id TEXT PRIMARY KEY NOT NULL, baseline_batch TEXT NOT NULL, observation_id TEXT NOT NULL,
                review_json TEXT NOT NULL CHECK(json_valid(review_json)), sha256 TEXT NOT NULL)''')
            payload=canonical({'summary':summary,'decisions':decisions})
            row={'review_id':str(uuid.uuid4()),'baseline_batch':batch,'observation_id':manifest['observation_id'],
                 'review_json':payload,'sha256':digest(payload)}
            ensure_row(session,'friendbook_cf_counter_reconciliation_reviews',tuple(row),('review_id',),row)
            actual=d1_query(session,'SELECT review_json,sha256 FROM friendbook_cf_counter_reconciliation_reviews WHERE review_id=?',(row['review_id'],))
            if actual!=[{'review_json':payload,'sha256':digest(payload)}]: raise BackupError('Counter review read-back failed')
        print('::notice::'+canonical(summary))
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as out: out.write('Staging counter reconciliation review (no applied live changes).\n```json\n'+canonical(summary)+'\n```\n')
        return 0
    except Exception as error:
        if isinstance(error,BackupError): print('::error::'+str(error))
        print(f'::error::Counter review stopped ({type(error).__name__}); private details suppressed; no app/Firebase mutations.')
        return 1

if __name__=='__main__': sys.exit(main())
