#!/usr/bin/env python3
"""Replace exactly the global timestamp index, never change user rows or deploy app.

One-time CREATE scans the table and costs quota. Metadata filesize is a coarse
safety bound, NOT a billed-row bound. No synthetic inserts or table preflight.
"""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
BASE='https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163'
OLD='idx_friendbook_cf_firestore_collection_updated'
NEW='idx_cf_chat_root_updated'
CHAT="SELECT path,doc_id,document_json,update_time AS updated_at,1 AS canonical FROM friendbook_cf_firestore_documents WHERE collection_id='chats' AND parent_path='chats' ORDER BY updated_at DESC LIMIT ?"
LIVE="SELECT 1 FROM friendbook_cf_firestore_documents WHERE collection_id='chat' AND parent_path=? AND owner_uid=? AND create_time>?"
RESTORE='CREATE INDEX IF NOT EXISTS '+OLD+' ON friendbook_cf_firestore_documents(collection_id,update_time DESC)'
CREATE="CREATE INDEX IF NOT EXISTS "+NEW+" ON friendbook_cf_firestore_documents(parent_path,update_time DESC) WHERE collection_id='chats' AND parent_path='chats'"


def normalized(sql):
    return re.sub(r'\s+','',sql).lower().replace('ifnotexists','').rstrip(';')


def validate_schema(rows):
    names={r['name']:r['sql'] for r in rows}
    if OLD in names and normalized(names[OLD])!=normalized(RESTORE): raise ValueError('Unexpected global index definition')
    if NEW in names and normalized(names[NEW])!=normalized(CREATE): raise ValueError('Unexpected chat index definition')
    if 'idx_cf_live_chat_sender_time' not in names or 'idx_friendbook_cf_firestore_parent' not in names:
        raise ValueError('Required live/parent indexes missing')
    return OLD in names,NEW in names


def validate_chat_plan(rows):
    details=[r['detail'] for r in rows]
    if not any(NEW in r and 'parent_path=?' in r for r in details) or any('TEMP B-TREE' in r for r in details):
        raise ValueError('Chat order must use new scoped index without sorting')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply-reviewed-replacement',action='store_true',required=True)
    parser.parse_args()
    token=os.environ['CLOUDFLARE_API_TOKEN']
    def call(suffix='',sql=None,params=()):
        request=urllib.request.Request(BASE+suffix,data=None if sql is None else json.dumps({'sql':sql,'params':list(params)}).encode(),
            headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=60) as response: value=json.load(response)
        if not value.get('success'): raise ValueError('Cloudflare response unsuccessful')
        if sql is None:return value['result']
        results=value.get('result',[])
        if len(results)!=1 or not results[0].get('success'):raise ValueError('Incomplete D1 response')
        result=results[0];meta=result.get('meta',{})
        print('::notice::Chat-index statement cost '+json.dumps({k:meta.get(k) for k in ('rows_read','rows_written','changes','changed_db')},sort_keys=True))
        return result.get('results',[])
    info=call()
    size=info.get('file_size')
    if type(size) is not int or not 0<size<=80*1024*1024:raise ValueError('Unknown/large database; no index build permitted')
    rows=call('/query',"SELECT name,sql FROM sqlite_schema WHERE type='index' AND name IN (?,?,?,?)",(OLD,NEW,'idx_cf_live_chat_sender_time','idx_friendbook_cf_firestore_parent'))
    old_exists,new_exists=validate_schema(rows)
    old_plan=call('/query','EXPLAIN QUERY PLAN '+CHAT+' /* chat-index-before */',(20,))
    before_live=call('/query','EXPLAIN QUERY PLAN '+LIVE,('lives/__quota_probe__/chat','__quota_probe__',1))
    if not any('idx_cf_live_chat_sender_time' in r['detail'] and 'owner_uid=?' in r['detail'] for r in before_live):
        raise ValueError('Scoped live throttle index not selected')
    if not new_exists:call('/query',CREATE)
    forced=CHAT.replace('FROM friendbook_cf_firestore_documents','FROM friendbook_cf_firestore_documents INDEXED BY '+NEW)
    validate_chat_plan(call('/query','EXPLAIN QUERY PLAN '+forced,(20,)))
    if old_exists:call('/query','DROP INDEX IF EXISTS '+OLD)
    try:
        after_plan=call('/query','EXPLAIN QUERY PLAN '+CHAT+' /* chat-index-after */',(20,))
        validate_chat_plan(after_plan)
        after_live=call('/query','EXPLAIN QUERY PLAN '+LIVE+' /* chat-index-after */',('lives/__quota_probe__/chat','__quota_probe__',1))
        if [r['detail'] for r in before_live]!=[r['detail'] for r in after_live]:raise ValueError('Live throttle plan changed')
    except Exception:
        if old_exists:call('/query',RESTORE)
        raise
    print('::notice::Chat timestamp index replacement verified '+json.dumps({'database_file_size':size,'before':[r['detail'] for r in old_plan],'after':[r['detail'] for r in after_plan],'user_row_mutations':0,'billed_daily_savings_proven':False},sort_keys=True))


if __name__=='__main__':main()
