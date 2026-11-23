#!/usr/bin/env python3
"""Tiny D1 billing probe in an isolated disposable table, never app/user tables.

Creates at most two synthetic rows per stage, then drops ONLY its unique table.
This measures index-maintenance cost, not whole-app savings or daily compliance.
"""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request
import uuid

URL='https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query'


def probe(query,suffix):
    if not re.fullmatch('[a-f0-9]{32}',suffix):raise ValueError('Unsafe probe identifier')
    table='quota_index_probe_'+suffix
    result={'environment':'isolated synthetic D1 table; not application workload','before':{},'after':{},'cleanup_verified':False}
    available=query("SELECT count(*) AS remaining FROM sqlite_schema WHERE name=?",(table,))
    if available['results']!=[{'remaining':0}]:raise ValueError('Probe name already exists')
    created=False
    try:
        # The unique reserved name was confirmed absent. Attempt cleanup even
        # if CREATE commits but its HTTP response/metadata is lost.
        created=True
        query(f'CREATE TABLE {table} (path TEXT PRIMARY KEY NOT NULL,parent_path TEXT NOT NULL,collection_id TEXT NOT NULL,update_time INTEGER NOT NULL)')
        query(f'CREATE INDEX {table}_old ON {table}(collection_id,update_time DESC)')
        insert=f'INSERT INTO {table}(path,parent_path,collection_id,update_time) VALUES (?,?,?,?)'
        for scope in ('notifications','chats'):
            result['before'][scope]=query(insert,(scope+'/synthetic',scope,scope,1))['meta']['rows_written']
        query(f'DELETE FROM {table}')
        query(f"CREATE INDEX {table}_new ON {table}(parent_path,update_time DESC) WHERE collection_id='chats' AND parent_path='chats'")
        query(f'DROP INDEX {table}_old')
        for scope in ('notifications','chats'):
            result['after'][scope]=query(insert,(scope+'/synthetic',scope,scope,1))['meta']['rows_written']
    finally:
        if created:
            query(f'DROP TABLE IF EXISTS {table}')
            check=query("SELECT count(*) AS remaining FROM sqlite_schema WHERE name=?",(table,))
            if check['results']!=[{'remaining':0}]:raise ValueError('Probe cleanup not confirmed')
            result['cleanup_verified']=True
    for values in (result['before'],result['after']):
        if any(type(n) is not int or n<0 for n in values.values()):raise ValueError('Billing data unavailable')
    result['nonchat_index_write_reduction_observed']=result['after']['notifications']<result['before']['notifications']
    result['root_chat_writes_not_increased']=result['after']['chats']<=result['before']['chats']
    result['daily_application_savings_proven']=False
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-isolated-two-row-probe',action='store_true',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    costs=[]
    def query(sql,params=()):
        request=urllib.request.Request(URL,data=json.dumps({'sql':sql,'params':list(params)}).encode(),
            headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=30) as response: payload=json.load(response)
        if not payload.get('success') or len(payload.get('result',[]))!=1 or not payload['result'][0].get('success'):
            raise ValueError('Isolated probe response incomplete')
        value=payload['result'][0]
        meta=value.get('meta',{})
        if any(type(meta.get(k)) is not int or meta[k]<0 for k in ('rows_read','rows_written')):
            raise ValueError('Isolated billing counters unavailable')
        costs.append({k:meta[k] for k in ('rows_read','rows_written')})
        return value
    report=probe(query,uuid.uuid4().hex)
    report['one_time_probe_cost']={k:sum(c[k] for c in costs) for k in ('rows_read','rows_written')}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
    print('::notice::Isolated D1 billing proof '+json.dumps(report,sort_keys=True))
    if not report['nonchat_index_write_reduction_observed'] or not report['root_chat_writes_not_increased']:
        raise SystemExit('Observed billing did not establish the expected index improvement')


if __name__=='__main__':main()
