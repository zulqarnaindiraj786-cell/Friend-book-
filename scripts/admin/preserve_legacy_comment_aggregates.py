"""Preserve existing visible historical totals without inventing actor identities.
INSERT-only metadata; source/canonical interaction records/gates are not modified.
"""
import json
import os
from pathlib import Path
import sys
from copy_interaction_snapshot import BackupError, canonical, digest, d1_query
from import_interaction_staging import ensure_row, statements
from observe_interaction_changes import snapshot_hash
from plan_interaction_import import load_snapshot, plan
from reconcile_interaction_counters import comment_counters, legacy_aggregate_rows, verify_identity_tables


def main():
    try:
        import requests
        with requests.Session() as session:
            session.headers['Authorization']='Bearer '+os.environ['CLOUDFLARE_API_TOKEN']
            batch,docs=load_snapshot(session)
            manifests=d1_query(session,'SELECT manifest_json,sha256 FROM friendbook_cf_migration_observation_manifests ORDER BY read_time DESC LIMIT 1')
            if len(manifests)!=1 or digest(manifests[0]['manifest_json'])!=manifests[0]['sha256']:
                raise BackupError('Verified observation seal required')
            observed=json.loads(manifests[0]['manifest_json'])
            if not observed.get('observation_verified') or observed['baseline_batch']!=batch or observed['observed_snapshot_hash']!=snapshot_hash(docs):
                raise BackupError('Observed changes require replay before aggregate preservation')
            rows,report=plan(docs)
            allowed={'comment_reaction_counter_mismatch','reaction_missing_resource','resource_comment_counter_mismatch','resource_reaction_counter_mismatch'}
            if set(report['blockers'])-allowed: raise BackupError('Unresolved mapping/identity blockers')
            verify_identity_tables(session,rows)
            wanted=legacy_aggregate_rows(comment_counters(docs,rows),batch)
            sql=(Path(__file__).parents[2]/'cloudflare/migrations/0008_additive_legacy_comment_totals.sql').read_text()
            for statement in statements(sql):
                clean='\n'.join(line for line in statement.splitlines() if not line.lstrip().startswith('--')).strip().upper()
                if not clean.startswith('CREATE TABLE IF NOT EXISTS '): raise BackupError('Non-additive aggregate schema')
                d1_query(session,statement)
            for item in wanted:
                ensure_row(session,'friendbook_cf_comment_legacy_aggregates',tuple(item),('resource','id'),item)
            actual=d1_query(session,'SELECT resource,id,unattributed_count,source_path,source_sha256,snapshot_batch FROM friendbook_cf_comment_legacy_aggregates ORDER BY resource,id')
            if sorted(map(canonical,actual))!=sorted(map(canonical,wanted)):
                raise BackupError('Historical aggregate metadata read-back failed; preserve all rows')
            result={'snapshot_batch':batch,'historical_comment_aggregate_rows_verified':len(wanted),
                    'historical_unattributed_total_preserved':sum(r['unattributed_count'] for r in wanted),
                    'actor_records_fabricated':0,'firebase_writes':0,'deletions':0,'safe_to_cutover':False}
        print('::notice::'+canonical(result))
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as out: out.write('Historical aggregate provenance retained; identities remain unknown.\n```json\n'+canonical(result)+'\n```\n')
        return 0
    except Exception as error:
        if isinstance(error,BackupError): print('::error::'+str(error))
        print(f'::error::Historical aggregate preservation stopped ({type(error).__name__}); no source changes/deletions, keep gates OFF.')
        return 1

if __name__=='__main__': sys.exit(main())
