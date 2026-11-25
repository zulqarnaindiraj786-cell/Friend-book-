#!/usr/bin/env python3
"""Gate one shared chat index on an isolated D1 write-cost probe. No user DML."""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[2]
BASE='https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163'
NAME='idx_cf_chat_activity_seek'

def index_sql():
    sql='\n'.join(line for line in (ROOT/'cloudflare/migrations/0091_chat_activity_seek.sql').read_text().splitlines() if not line.lstrip().startswith('--')).strip()
    if not sql.startswith('CREATE INDEX IF NOT EXISTS '+NAME+'\nON friendbook_cf_firestore_documents(') or sql.count(';')!=1 or not sql.endswith("WHERE path='chats/'||doc_id;"):
        raise ValueError('Unexpected index migration')
    return sql

def normalize(sql):
    return re.sub(r'\s+','',sql).lower().replace('ifnotexists','').rstrip(';')

def isolated_probe(query,suffix):
    if not re.fullmatch('[a-f0-9]{32}',suffix):raise ValueError('Unsafe probe name')
    table='quota_index_probe_'+suffix
    if query('SELECT count(*) n FROM sqlite_schema WHERE name=?',(table,))['results']!=[{'n':0}]:raise ValueError('Probe collision')
    stages={};created=False
    try:
        created=True
        query(f'CREATE TABLE {table}(path TEXT PRIMARY KEY NOT NULL,doc_id TEXT NOT NULL,document_json TEXT NOT NULL)')
        for stage in ('before','after'):
            if stage=='after':query(index_sql().replace(NAME,table+'_new').replace('friendbook_cf_firestore_documents',table))
            values={}
            for kind,members,path in [('profile',2,'users/synthetic'),('message',2,'chats/synthetic/messages/m'),('direct',2,'chats/direct'),('group',50,'chats/group')]:
                doc={'participants':['synthetic'+str(i) for i in range(members)],'lastMessageTime':{'__type':'timestamp','millis':1}}
                query(f'INSERT INTO {table} VALUES (?,?,?)',(path,path.split('/')[-1],json.dumps(doc)))
                time=query(f"UPDATE {table} SET document_json=json_set(document_json,'$.lastMessageTime.millis',2) WHERE path=?",(path,))['meta']['rows_written']
                metadata=query(f"UPDATE {table} SET document_json=json_set(document_json,'$.lastMessage','new') WHERE path=?",(path,))['meta']['rows_written']
                values[kind]={'activity_update':time,'metadata_update':metadata}
            stages[stage]=values
            query(f'DELETE FROM {table}')
    finally:
        if created:
            query(f'DROP TABLE IF EXISTS {table}')
            if query('SELECT count(*) n FROM sqlite_schema WHERE name=?',(table,))['results']!=[{'n':0}]:raise ValueError('Cleanup not verified')
    delta={kind:{op:stages['after'][kind][op]-stages['before'][kind][op] for op in ('activity_update','metadata_update')} for kind in stages['before']}
    # A global shared index must never cause per-member fanout. D1 measures
    # index maintenance; do not infer it from local total_changes().
    if any(delta[kind][op]!=0 for kind in ('profile','message') for op in ('activity_update','metadata_update')):raise ValueError('Non-chat write cost changed')
    if any(not 0<=delta[kind][op]<=2 for kind in ('direct','group') for op in ('activity_update','metadata_update')):raise ValueError('Shared index write cost too high')
    if delta['direct']!=delta['group']:raise ValueError('Unexpected membership-dependent write amplification')
    return {'before':stages['before'],'after':stages['after'],'additional_billed_rows_written':delta,'cleanup_verified':True,'daily_savings_proven':False}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--probe-and-build-reviewed-index',action='store_true',required=True)
    p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    costs=[]
    def call(sql=None,params=()):
        req=urllib.request.Request(BASE+('/query' if sql is not None else ''),data=None if sql is None else json.dumps({'sql':sql,'params':list(params)}).encode(),headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=90) as r:payload=json.load(r)
        if not payload.get('success'):raise ValueError('D1 request unsuccessful')
        if sql is None:return payload['result']
        if len(payload.get('result',[]))!=1 or not payload['result'][0].get('success'):raise ValueError('D1 result incomplete')
        result=payload['result'][0];meta=result.get('meta',{})
        if any(type(meta.get(k)) is not int or meta[k]<0 for k in ('rows_read','rows_written')):raise ValueError('D1 billing counters unavailable')
        costs.append({k:meta[k] for k in ('rows_read','rows_written')})
        return result
    report={'index':NAME,'app_deployed':False,'user_dml_statements':0}
    try:
        size=call().get('file_size')
        if type(size) is not int or not 0<size<=80*1024*1024:raise ValueError('Database size outside reviewed bound')
        existing=call('SELECT sql FROM sqlite_schema WHERE type=\'index\' AND name=?',(NAME,))['results']
        if existing and (len(existing)!=1 or normalize(existing[0]['sql'])!=normalize(index_sql())):raise ValueError('Existing index definition differs')
        report['isolated_billing']=isolated_probe(call,uuid.uuid4().hex)
        if not existing:report['index_build_meta']=call(index_sql())['meta']
        expression=index_sql().split('ON friendbook_cf_firestore_documents(',1)[1].rsplit(',doc_id DESC)',1)[0]
        # expression includes the DESC direction from the index definition.
        plan=call(f"EXPLAIN QUERY PLAN SELECT path,doc_id FROM friendbook_cf_firestore_documents INDEXED BY {NAME} WHERE path='chats/'||doc_id ORDER BY {expression},doc_id DESC LIMIT ?",(64,))['results']
        details=[row['detail'] for row in plan]
        if not any(NAME in row for row in details) or any('TEMP B-TREE' in row for row in details):raise ValueError('Ordered shared seek plan not established')
        # Force bounded canonical-document and membership reads, but return
        # aggregates only: never send user document JSON/IDs to CI output.
        bounded=call(f"SELECT COUNT(*) AS candidates, SUM(length(document_json)+has_member) AS forced_materialization FROM (SELECT f.document_json, EXISTS(SELECT 1 FROM friendbook_cf_chat_participants m WHERE m.participant_uid=? AND m.chat_path=f.path AND m.source=1) AS has_member FROM friendbook_cf_firestore_documents f INDEXED BY {NAME} WHERE f.path='chats/'||f.doc_id ORDER BY {expression},f.doc_id DESC LIMIT 64)",('__quota_shared_seek__',))
        candidates=bounded['results'][0]['candidates']
        if type(candidates) is not int or not 0<=candidates<=64:raise ValueError('Bounded read probe returned unexpected count')
        report['bounded_read_probe']={'candidates':candidates,**{k:bounded['meta'][k] for k in ('rows_read','rows_written')}}
        if bounded['meta']['rows_written']!=0:raise ValueError('Read-only probe unexpectedly wrote rows')
        if bounded['meta']['rows_read']>256:raise ValueError('Shared read probe exceeded reviewed bounded cost')
        report.update({'plan':details,'index_installed':True,'database_file_size':size})
    finally:
        report['operation_cost']={k:sum(row[k] for row in costs) for k in ('rows_read','rows_written')}
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
        print('::notice::Shared inbox index evidence '+json.dumps(report,sort_keys=True))

if __name__=='__main__':main()
