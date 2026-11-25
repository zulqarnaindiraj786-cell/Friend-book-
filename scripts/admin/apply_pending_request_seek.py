#!/usr/bin/env python3
"""Apply exactly one additive pending-request index; never deploy Worker/app data.

Explicit CLI approval required. Preflight is capped at 20,001 legacy rows. Its
row cap bounds the sample, not a contractual Cloudflare billing limit. Fail closed
on unexpected responses, oversized tables, or invalid JSON. Print only aggregates,
query plans and costs, never document values or credentials.
"""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / 'cloudflare/migrations/0088_pending_duplicate_request_seek.sql'
URL = 'https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query'
CAP = 20001


def cleanup_sql():
    source = (ROOT / 'cloudflare/firestore-documents.js').read_text()
    match = re.search(r'const duplicateLegacy=db.prepare\(`(.*?)`\)\.bind', source, re.S)
    if not match:
        raise ValueError('Expected cleanup query missing')
    return match.group(1)


def probe_sql():
    # Exactly the production WHERE, without executing an UPDATE or returning IDs.
    predicate = cleanup_sql().split("WHERE collection='friend_requests'", 1)[1]
    return "SELECT count(*) AS matches FROM friendbook_cf_documents AS a WHERE collection='friend_requests'" + predicate


def validate_preflight(row):
    if set(row) != {'sampled', 'invalid_json'}:
        raise ValueError('Unexpected preflight schema')
    if type(row['sampled']) is not int or not 0 <= row['sampled'] < CAP:
        raise ValueError('Legacy table exceeds conservative index-build bound')
    if type(row['invalid_json']) is not int or row['invalid_json'] != 0:
        raise ValueError('Invalid JSON prevents safe index build')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply-additive-index', action='store_true', required=True)
    parser.parse_args()
    token = os.environ['CLOUDFLARE_API_TOKEN']

    def query(sql, params=()):
        req = urllib.request.Request(URL, data=json.dumps({'sql': sql, 'params': list(params)}).encode(),
            headers={'Authorization': 'Bearer '+token, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=60) as response:
            payload = json.load(response)
        if not payload.get('success') or len(payload.get('result', [])) != 1:
            raise ValueError('Incomplete D1 response')
        result = payload['result'][0]
        if not result.get('success'):
            raise ValueError('D1 statement failed')
        meta = result.get('meta', {})
        print('::notice::Statement cost '+json.dumps({k: meta.get(k) for k in ['rows_read', 'rows_written', 'changes', 'changed_db']}, sort_keys=True))
        return result.get('results', [])

    rows = query(f'''SELECT count(*) AS sampled, COALESCE(sum(NOT json_valid(document_json)),0) AS invalid_json
        FROM (SELECT document_json FROM friendbook_cf_documents LIMIT {CAP})''')
    if len(rows) != 1:
        raise ValueError('Incomplete bounded preflight')
    validate_preflight(rows[0])
    print('::notice::Bounded index preflight '+json.dumps(rows[0], sort_keys=True))
    # Reserved diagnostic identities, not real users. No app call or write occurs.
    params = ('__quota_probe_sender_7164__', '__quota_probe_recipient_7164__') * 2
    probe = probe_sql()
    for stage in ('before', 'after'):
        plan = query('EXPLAIN QUERY PLAN '+probe, params)
        print('::notice::'+stage+' pending cleanup plan '+json.dumps([r['detail'] for r in plan]))
        counts = query(probe, params)
        print('::notice::'+stage+' synthetic-pair aggregate '+json.dumps(counts))
        if stage == 'before':
            # This is the ONLY mutation: an idempotent additive schema index.
            query(MIGRATION.read_text())
        elif not any('SEARCH a USING INDEX idx_cf_legacy_pending_duplicate_pair' in r['detail'] for r in plan):
            raise ValueError('New index not selected; no savings claim permitted')
    print('::notice::Additive index applied and production query-plan seek verified; ordinary-traffic savings still require analytics')


if __name__ == '__main__':
    main()
