#!/usr/bin/env python3
"""Targeted canonical index retirement. Metadata/EXPLAIN only before DROP."""
import argparse
import json
import os
from pathlib import Path
import re
import sqlite3
import urllib.request

ACCOUNT='92f3d04598822de2e49e14561218d090'
DATABASE='e1c605ca-a50b-46c6-9deb-56dd1f70d163'
OLD='idx_cf_firestore_inbox_recipient'
PAGE='idx_cf_notif_page'
ROOT=Path(__file__).resolve().parents[2]

def normalize(sql):
    return re.sub(r'\s+','',sql).lower().rstrip(';')

def expected():
    with sqlite3.connect(':memory:') as db:
        for name in ['0014_additive_firestore_documents.sql','0025_inbox_recipient_indexes.sql','0055_notification_page_indexes.sql']:
            if name=='0025_inbox_recipient_indexes.sql':
                db.execute('CREATE TABLE friendbook_cf_documents(path TEXT,collection TEXT,doc_id TEXT,document_json TEXT)')
            db.executescript((ROOT/'cloudflare/migrations'/name).read_text())
        return {name:normalize(sql) for name,sql in db.execute('SELECT name,sql FROM sqlite_master WHERE name IN (?,?)',(OLD,PAGE))}

def validate(rows):
    actual={row['name']:normalize(row['sql']) for row in rows}
    required=expected()
    if PAGE not in actual or actual[PAGE]!=required[PAGE]:
        raise ValueError('Replacement index is missing or not the exact reviewed definition')
    if OLD in actual and actual[OLD]!=required[OLD]:
        raise ValueError('Old index definition differs; refusing retirement')
    return OLD in actual

def query(sql,params=()):
    req=urllib.request.Request(f'https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/d1/database/{DATABASE}/query',
        data=json.dumps({'sql':sql,'params':list(params)}).encode(),
        headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'},method='POST')
    with urllib.request.urlopen(req,timeout=60) as response: data=json.load(response)
    if data.get('success') is not True or len(data.get('result',[]))!=1 or data['result'][0].get('success') is not True:
        raise RuntimeError('D1 operation failed; no response data logged')
    result=data['result'][0]
    meta=result.get('meta',{})
    print(json.dumps({'operation':'metadata_or_plan' if sql.startswith(('SELECT','EXPLAIN')) else 'targeted_drop',
        'rows_read':meta.get('rows_read'),'rows_written':meta.get('rows_written')}))
    return result.get('results',[])

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    metadata="SELECT name,sql FROM sqlite_master WHERE type='index' AND name IN (?,?)"
    rows=query(metadata,(OLD,PAGE));present=validate(rows)
    plan=query("EXPLAIN QUERY PLAN SELECT path FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_notif_page WHERE parent_path='notifications' AND json_extract(document_json,'$.toUserId')=?",('__quota_plan_probe__',))
    details=' '.join(row.get('detail','') for row in plan)
    if not ('SEARCH' in details and PAGE in details and '<expr>=?' in details) or 'SCAN' in details:
        raise ValueError('Recipient seek not proven; refusing retirement')
    print('Exact replacement DDL and recipient seek verified; no notification records scanned.')
    if args.apply and present:
        query('DROP INDEX IF EXISTS idx_cf_firestore_inbox_recipient')
        if validate(query(metadata,(OLD,PAGE))):raise RuntimeError('Retirement not confirmed')
        print('Canonical prefix retired; ordered replacement retained; legacy indexes unchanged.')
    elif not present: print('Canonical prefix already absent; replacement verified; no mutation.')

if __name__=='__main__':main()
