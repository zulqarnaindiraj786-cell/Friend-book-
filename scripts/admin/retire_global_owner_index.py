#!/usr/bin/env python3
"""Retire exactly one redundant owner-only index, preserving scoped live/chat keys.

No user-row reads/writes, Worker deployment, migration sweep, or schedule change.
EXPLAIN compiles a synthetic insert but never executes it. Opcode reductions are
not billed-write measurements; ordinary-traffic analytics is still required.
"""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
INDEX='idx_friendbook_cf_firestore_owner'
REQUIRED=('idx_cf_live_chat_sender_time','idx_cf_chat_root_updated')
URL='https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query'
PROBES=[
    ("SELECT path FROM friendbook_cf_firestore_documents WHERE collection_id='chat' AND parent_path=? AND owner_uid=? AND create_time>? AND json_extract(document_json,'$.type')='gift'",['lives/__quota_probe__/chat','__quota_probe__',1]),
    ("SELECT path FROM friendbook_cf_firestore_documents WHERE collection_id='chat' AND parent_path=? AND owner_uid=? AND create_time>? AND json_extract(document_json,'$.type') IN ('chat','qa')",['lives/__quota_probe__/chat','__quota_probe__',1]),
    ('SELECT owner_uid,document_json FROM friendbook_cf_firestore_documents WHERE path=?',['notifications/__quota_probe__']),
    ("SELECT path FROM friendbook_cf_firestore_documents WHERE collection_id='chats' AND parent_path='chats' ORDER BY update_time DESC LIMIT ?",[20]),
]
INSERT="INSERT INTO friendbook_cf_firestore_documents VALUES ('notifications/__quota_probe__','notifications','notifications','__quota_probe__','__quota_probe__','{}',1,1)"


def validate_indexes(rows):
    indexes={r['name']:r['sql'] for r in rows}
    if any(not indexes.get(name) for name in REQUIRED):
        raise ValueError('Required scoped indexes missing; no retirement allowed')
    live=re.sub(r'\s+','',indexes[REQUIRED[0]]).lower()
    if "(parent_path,owner_uid,create_timedesc)wherecollection_id='chat'" not in live:
        raise ValueError('Live throttle index differs; no retirement allowed')
    chat=re.sub(r'\s+','',indexes[REQUIRED[1]]).lower()
    if "(parent_path,update_timedesc)wherecollection_id='chats'andparent_path='chats'" not in chat:
        raise ValueError('Chat-root replacement index differs; no retirement allowed')
    if INDEX in indexes:
        definition=re.sub(r'\s+','',indexes[INDEX]).lower().rstrip(';')
        expected='createindex'+INDEX+'onfriendbook_cf_firestore_documents(owner_uid)'
        if definition != expected:
            raise ValueError('Owner index differs from reviewed schema; no retirement allowed')
    return INDEX in indexes


def validate_plans(plans):
    for plan in plans[:2]:
        if not any('idx_cf_live_chat_sender_time' in p and 'owner_uid=?' in p and 'create_time>?' in p for p in plan):
            raise ValueError('Live throttle must retain scoped sender/time search')
    if not any('path=?' in p and 'SEARCH' in p for p in plans[2]):
        raise ValueError('Path authorization must retain primary-key search')
    if not any('idx_cf_chat_root_updated' in p for p in plans[3]):
        raise ValueError('Chat ordering must use the reviewed root-only replacement index')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--retire-reviewed-index',action='store_true',required=True)
    parser.parse_args()
    def query(sql,params=()):
        req=urllib.request.Request(URL,data=json.dumps({'sql':sql,'params':list(params)}).encode(),
            headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=45) as response: payload=json.load(response)
        if not payload.get('success') or len(payload.get('result',[]))!=1 or not payload['result'][0].get('success'):
            raise ValueError('Incomplete D1 response')
        result=payload['result'][0]
        meta=result.get('meta',{})
        print('Statement metadata '+json.dumps({k:meta.get(k) for k in ('rows_read','rows_written','changes','changed_db')},sort_keys=True))
        return result.get('results',[]),meta
    names=(*REQUIRED,INDEX)
    rows,_=query("SELECT name,sql FROM sqlite_schema WHERE type='index' AND name IN (?,?,?)",names)
    exists=validate_indexes(rows)
    summary=[]
    for stage in ('before','after'):
        plans=[]
        for sql,params in PROBES:
            rows,meta=query('EXPLAIN QUERY PLAN '+sql+' /* owner-index-'+stage+' */',params)
            if meta.get('rows_written')!=0: raise ValueError('EXPLAIN must not mutate data')
            plans.append([r['detail'] for r in rows])
        validate_plans(plans)
        # Distinct texts avoid stale EXPLAIN caches across the schema change.
        opcodes,meta=query('EXPLAIN '+INSERT+' /* owner-index-'+stage+' */')
        if meta.get('rows_written')!=0: raise ValueError('EXPLAIN must not mutate data')
        summary.append({'stage':stage,'plans':plans,'index_insert_opcodes':sum(r['opcode']=='IdxInsert' for r in opcodes)})
        if stage=='before':
            # ONLY mutation; never execute the compiled INSERT above.
            _,meta=query((ROOT/'cloudflare/migrations/0089_remove_redundant_global_owner_index.sql').read_text())
            print('::notice::Owner-index retirement cost '+json.dumps(meta,sort_keys=True))
    if exists and summary[1]['index_insert_opcodes']!=summary[0]['index_insert_opcodes']-1:
        raise ValueError('Unexpected insert plan; do not claim write improvement')
    rows,_=query("SELECT name,sql FROM sqlite_schema WHERE type='index' AND name IN (?,?,?)",names)
    if validate_indexes(rows): raise ValueError('Owner index still exists')
    print('::notice::Scoped-query and compiled-insert verification '+json.dumps(summary,sort_keys=True))
    print('::notice::No document mutation or Worker deployment performed; actual billed write reduction remains to be observed')


if __name__=='__main__':
    main()
