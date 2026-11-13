"""INSERT-only normalized STAGING import. NEVER marks resources verified or enables D1.
Original source counters and orphan records remain archived and separately preserved.
"""
from collections import Counter
import os
from pathlib import Path
import sqlite3
import sys
from copy_interaction_snapshot import PREFIX, BackupError, canonical, digest, d1_query
from plan_interaction_import import fields, load_snapshot, plan
import json

PENDING = {'comment_reaction_counter_mismatch', 'reaction_missing_resource',
           'resource_comment_counter_mismatch', 'resource_reaction_counter_mismatch'}


def statements(text):
    buffer = ''
    for line in text.splitlines(True):
        buffer += line
        if sqlite3.complete_statement(buffer):
            yield buffer
            buffer = ''
    if buffer.strip(): raise BackupError('Incomplete additive schema')


def ensure_row(session, table, columns, key_columns, row):
    if not table.startswith('friendbook_cf_'): raise BackupError('Unexpected import table')
    where = ' AND '.join(column + '=?' for column in key_columns)
    keys = tuple(row[c] for c in key_columns)
    existing = d1_query(session, f'SELECT {",".join(columns)} FROM {table} WHERE {where}', keys)
    expected = {c: row[c] for c in columns}
    if existing:
        if len(existing) != 1 or existing[0] != expected:
            raise BackupError('Existing destination row differs; no overwrites permitted')
        return
    d1_query(session, f'INSERT INTO {table} ({",".join(columns)}) VALUES ({",".join("?" for _ in columns)})', tuple(row[c] for c in columns))


def import_rows(session, batch, docs, rows, report):
    unhandled = set(report['blockers']) - PENDING
    if unhandled: raise BackupError('Unresolved mapping/identity/link conflicts prevent staging import')
    # Only reviewed additive SQL; never apply any destructive migration command.
    for filename in ('0003_additive_interactions.sql', '0004_additive_comment_details.sql',
                     '0005_additive_reaction_history.sql', '0006_additive_comment_history.sql'):
        text = (Path(__file__).parents[2] / 'cloudflare/migrations' / filename).read_text()
        for sql in statements(text):
            clean = '\n'.join(line for line in sql.splitlines() if not line.lstrip().startswith('--')).strip().upper()
            if not clean.startswith(('CREATE TABLE IF NOT EXISTS ', 'CREATE INDEX IF NOT EXISTS ', 'CREATE TRIGGER IF NOT EXISTS ')):
                raise BackupError('Migration is not an approved additive statement')
            d1_query(session, sql)
    d1_query(session, '''CREATE TABLE IF NOT EXISTS friendbook_cf_import_preservation (
        batch_id TEXT NOT NULL, source_path TEXT NOT NULL, kind TEXT NOT NULL,
        original_json TEXT NOT NULL CHECK(json_valid(original_json)), sha256 TEXT NOT NULL,
        PRIMARY KEY(batch_id,source_path,kind))''')
    preservation = []
    for name, raw in sorted(docs.items()):
        path = name[len(PREFIX) + 1:]; parts = path.split('/')
        if len(parts) == 2 and parts[0] in ('posts', 'reels'):
            kind = 'original_resource_document_and_counters'
        elif len(parts) == 4 and parts[2] == 'reactions' and '/'.join(parts[:2]) not in {r['resource'] for r in rows['resources']}:
            kind = 'orphan_reaction_quarantine'
        else: continue
        item = {'batch_id': batch, 'source_path': path, 'kind': kind, 'original_json': raw, 'sha256': digest(raw)}
        ensure_row(session, 'friendbook_cf_import_preservation', tuple(item), ('batch_id','source_path','kind'), item)
        preservation.append(item)
    specs = [
        ('resources','friendbook_cf_interaction_resources', ('resource','state'), ('resource',)),
        ('reactions','friendbook_cf_reactions', ('resource','uid','type','updated_at'), ('resource','uid')),
        ('reaction_history','friendbook_cf_reaction_history', ('resource','uid','timestamp_known'), ('resource','uid')),
    ]
    expected = {}
    for name, table, columns, key in specs:
        expected[table] = [{c:r[c] for c in columns} for r in rows.get(name, [])]
        for row in expected[table]: ensure_row(session, table, columns, key, row)
    # Insert parents before replies; no invented or silently discarded parent links.
    pending = list(rows.get('comments', [])); done = set()
    ordered = []
    while pending:
        ready = [r for r in pending if not r['parent_id'] or (r['resource'], r['parent_id']) in done]
        if not ready: raise BackupError('Unresolved reply graph; import stopped')
        for row in ready:
            ordered.append(row); done.add((row['resource'],row['id'])); pending.remove(row)
    expected['friendbook_cf_comments'] = []
    expected['friendbook_cf_comment_details'] = []
    expected['friendbook_cf_comment_reactions'] = []
    for row in ordered:
        columns = ('resource','id','uid','text','parent_id','created_at')
        item = {c:row[c] for c in columns}
        ensure_row(session,'friendbook_cf_comments',columns,('resource','id'),item)
        expected['friendbook_cf_comments'].append(item)
        item = {c:row[c] for c in ('resource','id','media_url','name','photo')}
        item.update(mentions=canonical(row['mentions']), edited=int(row['edited']), removed=int(row['removed']))
        ensure_row(session,'friendbook_cf_comment_details',tuple(item),('resource','id'),item)
        expected['friendbook_cf_comment_details'].append(item)
        for actor, kind in sorted(row['reactions'].items()):
            item = {'resource':row['resource'],'id':row['id'],'uid':actor,'type':kind}
            ensure_row(session,'friendbook_cf_comment_reactions',tuple(item),('resource','id','uid'),item)
            expected['friendbook_cf_comment_reactions'].append(item)
    for name, table, columns, key in [
        ('comment_history','friendbook_cf_comment_history', ('resource','id','created_at_known'),('resource','id')),
        ('pins','friendbook_cf_comment_pins', ('resource','id'),('resource',)),
    ]:
        expected[table] = [{c:r[c] for c in columns} for r in rows.get(name, [])]
        for item in expected[table]: ensure_row(session,table,columns,key,item)
    # Read back complete application tables. Extra/unexpected records fail, never get removed.
    for table, wanted in expected.items():
        columns = tuple(wanted[0]) if wanted else ('resource',)
        actual = []
        offset = 0
        while True:
            page = d1_query(session, f'SELECT {",".join(columns)} FROM {table} ORDER BY {",".join(columns)} LIMIT 100 OFFSET ?', (offset,))
            actual.extend(page)
            if len(page) < 100: break
            offset += len(page)
        if sorted(map(canonical,actual)) != sorted(map(canonical,wanted)):
            raise BackupError('Normalized destination identity/content verification failed; preserve all rows')
    totals = Counter((r['resource'],r['type']) for r in rows.get('reactions', []))
    actual = d1_query(session,'SELECT resource,type,total FROM friendbook_cf_reaction_totals WHERE total>0')
    if {(r['resource'],r['type']):r['total'] for r in actual} != dict(totals):
        raise BackupError('Trigger-derived reaction totals did not verify')
    for item in preservation:
        result = d1_query(session,'SELECT original_json,sha256 FROM friendbook_cf_import_preservation WHERE batch_id=? AND source_path=? AND kind=?', (batch,item['source_path'],item['kind']))
        if result != [{'original_json':item['original_json'],'sha256':item['sha256']}]:
            raise BackupError('Preservation/quarantine verification failed')
    return {'batch':batch, 'normalized_staging_verified': True,
            'table_rows_verified': {table:len(items) for table,items in expected.items()},
            'preservation_rows_verified':len(preservation),
            'unresolved_source_reconciliation':report['blockers'],
            'resources_marked_verified':0,'safe_to_cutover':False,
            'firebase_writes':0,'deletions':0}


def main():
    try:
        import requests
        with requests.Session() as session:
            session.headers['Authorization'] = 'Bearer ' + os.environ['CLOUDFLARE_API_TOKEN']
            batch, docs = load_snapshot(session)
            rows, report = plan(docs)
            result = import_rows(session,batch,docs,rows,report)
        print('::notice::' + canonical(result))
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as out:
                out.write('Normalized STAGING import only; resources remain unavailable to live D1 routing.\n```json\n'+canonical(result)+'\n```\n')
        return 0
    except Exception as error:
        if isinstance(error,BackupError): print('::error::'+str(error))
        print(f'::error::Staging import stopped ({type(error).__name__}); keep gates OFF and preserve partial rows. No Firebase writes/deletions.')
        return 1

if __name__ == '__main__': sys.exit(main())
