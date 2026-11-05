"""Point-in-time migration observation, NOT CDC or proof of a safe live cutover.
Firebase is read-only; D1 records are append-only. No application row changes.
"""
from collections import Counter
import json
import math
import os
from pathlib import Path
import sys
import uuid
from copy_interaction_snapshot import PREFIX, Snapshot, BackupError, canonical, digest, d1_query
from import_interaction_staging import ensure_row, statements
from plan_interaction_import import fields, load_snapshot, plan


def snapshot_hash(docs):
    return digest(canonical([[name, digest(text)] for name, text in sorted(docs.items())]))


def differences(before, after):
    result = []
    for name in sorted(set(before) | set(after)):
        if before.get(name) == after.get(name): continue
        operation = 'created' if name not in before else 'absent' if name not in after else 'changed'
        result.append({'source_path': name[len(PREFIX) + 1:], 'operation': operation,
                       'before_sha256': digest(before[name]) if name in before else None,
                       'document_json': after.get(name, 'null'),
                       'after_sha256': digest(after[name]) if name in after else None})
    return result


def verify_reconstruction(before, changes, after):
    rebuilt = dict(before)
    for row in changes:
        name = PREFIX + '/' + row['source_path']
        if row['operation'] == 'absent':
            if row['before_sha256'] != digest(rebuilt[name]):
                raise BackupError('Observation before-image mismatch')
            # In-memory comparison only. No database/source deletion is performed.
            rebuilt.pop(name)
        else:
            if (digest(rebuilt[name]) if name in rebuilt else None) != row['before_sha256']:
                raise BackupError('Observation before-image mismatch')
            if digest(row['document_json']) != row['after_sha256']:
                raise BackupError('Observation after-image mismatch')
            rebuilt[name] = row['document_json']
    if rebuilt != after:
        raise BackupError('Observation cannot reconstruct the new point-in-time snapshot')


def counter_ledger(docs, rows):
    reactions = Counter(r['resource'] for r in rows.get('reactions', []))
    comments = Counter(r['resource'] for r in rows.get('comments', []))
    result = []
    for name, raw in sorted(docs.items()):
        path = name[len(PREFIX) + 1:]
        parts = path.split('/')
        if len(parts) != 2 or parts[0] not in ('posts', 'reels'): continue
        data = fields(json.loads(raw))
        for metric, derived in [('likes', reactions[path]), ('comments', comments[path])]:
            # Missing is preserved distinctly from a genuine zero.
            original = {'present': metric in data, 'value': data.get(metric)}
            value = data.get(metric)
            valid = isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0 and int(value) == value
            status = ('counter_absent' if metric not in data else 'unsupported_counter' if not valid else
                      'matches' if value == derived else 'underreported_counter' if value < derived else
                      'unattributed_counter_surplus')
            result.append({'source_path':path, 'metric':metric,
                           'original_json':canonical(original), 'identity_count':derived,
                           'status':status, 'source_document_sha256':digest(raw)})
    return result


def store_observation(session, observation, batch, read_time, before, after, rows, report):
    changes = differences(before, after)
    verify_reconstruction(before, changes, after)
    ledger = counter_ledger(after, rows)
    schema = (Path(__file__).parents[2] / 'cloudflare/migrations/0007_additive_migration_observations.sql').read_text()
    for sql in statements(schema):
        clean = '\n'.join(line for line in sql.splitlines() if not line.lstrip().startswith('--')).strip().upper()
        if not clean.startswith('CREATE TABLE IF NOT EXISTS '):
            raise BackupError('Observation schema must be additive CREATE only')
        d1_query(session, sql)
    for change in changes:
        row = {'observation_id':observation, **change}
        ensure_row(session,'friendbook_cf_migration_observations',tuple(row),('observation_id','source_path'),row)
    for entry in ledger:
        row = {'observation_id':observation, **entry}
        ensure_row(session,'friendbook_cf_migration_counter_ledger',tuple(row),('observation_id','source_path','metric'),row)
    # Re-read every value, including both hashes and private document JSON.
    for table, wanted in [('friendbook_cf_migration_observations',changes),
                          ('friendbook_cf_migration_counter_ledger',ledger)]:
        actual = []; offset = 0
        while True:
            order = 'source_path,metric' if table.endswith('counter_ledger') else 'source_path'
            page = d1_query(session,f'SELECT * FROM {table} WHERE observation_id=? ORDER BY {order} LIMIT 100 OFFSET ?', (observation,offset))
            for item in page:
                if item.pop('observation_id') != observation: raise BackupError('Unexpected observation identity')
                actual.append(item)
            if len(page) < 100: break
            offset += len(page)
        if sorted(map(canonical,actual)) != sorted(map(canonical,wanted)):
            raise BackupError('Append-only observation read-back mismatch')
    result = {'observation_id':observation,'baseline_batch':batch,'read_time':read_time,
              'baseline_documents':len(before),'observed_documents':len(after),
              'changes':dict(Counter(r['operation'] for r in changes)),
              'counter_classifications':dict(Counter(r['status'] for r in ledger)),
              'observed_plan_blockers':report['blockers'],
              'baseline_snapshot_hash':snapshot_hash(before),'observed_snapshot_hash':snapshot_hash(after),
              'observation_verified':True,'is_continuous_change_capture':False,
              'application_rows_modified':0,'firebase_writes':0,'deletions':0,'safe_to_cutover':False}
    # Seal last. A partially saved observation must never be mistaken for verified.
    manifest = {'observation_id':observation,'baseline_batch':batch,'read_time':read_time,
                'manifest_json':canonical(result),'sha256':digest(canonical(result))}
    ensure_row(session,'friendbook_cf_migration_observation_manifests',tuple(manifest),('observation_id',),manifest)
    stored = d1_query(session,'SELECT manifest_json,sha256 FROM friendbook_cf_migration_observation_manifests WHERE observation_id=?',(observation,))
    if stored != [{'manifest_json':manifest['manifest_json'],'sha256':manifest['sha256']}]:
        raise BackupError('Observation seal verification failed')
    return result


def main():
    try:
        import requests
        from google.oauth2 import service_account
        from google.auth.transport.requests import AuthorizedSession
        info = json.loads(os.environ['FIREBASE_ADMIN_SERVICE_ACCOUNT'])
        if info.get('project_id') != 'friendbook-17551' or info.get('type') != 'service_account':
            raise BackupError('Wrong Firebase credential type/project')
        credential = service_account.Credentials.from_service_account_info(info,scopes=['https://www.googleapis.com/auth/datastore'])
        with requests.Session() as destination:
            destination.headers['Authorization'] = 'Bearer '+os.environ['CLOUDFLARE_API_TOKEN']
            batch, before = load_snapshot(destination)
            ready = d1_query(destination,"SELECT COUNT(*) AS n FROM friendbook_cf_interaction_resources WHERE state!='staging'")
            if ready != [{'n':0}]: raise BackupError('Unexpected live resource marker: observation stopped')
            with AuthorizedSession(credential) as source:
                snapshot = Snapshot(source); after = snapshot.export()
            rows, report = plan(after)
            result = store_observation(destination,str(uuid.uuid4()),batch,snapshot.time,before,after,rows,report)
        print('::notice::'+canonical(result))
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as out:
                out.write('Verified administrative observation, NOT CDC/live cutover.\n```json\n'+canonical(result)+'\n```\n')
        return 0
    except Exception as error:
        if isinstance(error,BackupError): print('::error::'+str(error))
        print(f'::error::Migration observation stopped ({type(error).__name__}); no application/Firebase writes or deletion. Private details suppressed.')
        return 1

if __name__ == '__main__': sys.exit(main())
