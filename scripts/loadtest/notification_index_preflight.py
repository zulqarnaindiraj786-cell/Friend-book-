#!/usr/bin/env python3
"""Bounded, aggregate-only notification-index candidate inspection.

No document values, IDs or mutations. The source-row cap is not a guarantee of
D1 billed rows, index-drop cost or future headroom. Never use this alone as a
permit to alter production schema.
"""
import json
import os
import sys
import urllib.error
import urllib.request

CAP = 5001
SQL = """
SELECT
  (SELECT COUNT(*) FROM (SELECT 1 FROM friendbook_cf_firestore_documents
    INDEXED BY idx_cf_firestore_inbox_recipient WHERE parent_path='notifications' LIMIT 5001)) AS canonical_entries,
  (SELECT COUNT(*) FROM (SELECT 1 FROM friendbook_cf_documents
    INDEXED BY idx_cf_legacy_inbox_recipient WHERE collection='notifications' LIMIT 5001)) AS legacy_entries,
  (SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN
    ('idx_cf_notif_page','idx_cf_notif_unread','idx_cf_legacy_notif_page','idx_cf_legacy_notif_unread')) AS replacement_indexes
"""


def summarize(payload):
    if payload.get('success') is not True or len(payload.get('result', [])) != 1:
        raise ValueError('Incomplete response')
    result = payload['result'][0]
    if result.get('success') is not True or len(result.get('results', [])) != 1:
        raise ValueError('Incomplete aggregate')
    row = result['results'][0]
    keys = ('canonical_entries', 'legacy_entries', 'replacement_indexes')
    if any(type(row.get(k)) is not int or row[k] < 0 for k in keys):
        raise ValueError('Invalid aggregate')
    if any(row[k] > CAP for k in keys[:2]) or row['replacement_indexes'] > 4:
        raise ValueError('Invalid aggregate range')
    meta = result.get('meta') or {}
    reads, writes = meta.get('rows_read'), meta.get('rows_written')
    if type(reads) is not int or reads < 0 or type(writes) is not int or writes != 0:
        raise ValueError('Missing or unexpected D1 metadata')
    return {**{k: row[k] for k in keys}, 'truncated': any(row[k] == CAP for k in keys[:2]),
            'd1_reported_rows_read': reads, 'd1_reported_rows_written': writes}


def within_preflight_bounds(summary):
    # Conservative preflight thresholds, not an estimate of migration cost.
    return (not summary['truncated'] and summary['replacement_indexes'] == 4
            and summary['canonical_entries'] + summary['legacy_entries'] <= 10000
            and summary['d1_reported_rows_read'] <= 12000)


def main():
    token = os.environ.get('CLOUDFLARE_API_TOKEN')
    if not token:
        raise SystemExit('Cloudflare token unavailable; no production changes made.')
    account = '92f3d04598822de2e49e14561218d090'
    database = 'e1c605ca-a50b-46c6-9deb-56dd1f70d163'
    request = urllib.request.Request(
        f'https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{database}/query',
        data=json.dumps({'sql': SQL}).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            summary = summarize(json.load(response))
    except (urllib.error.URLError, ValueError, TimeoutError, KeyError, TypeError):
        raise SystemExit('Read-only preflight unavailable or incomplete; no production changes made.')
    print('::notice:: Aggregate-only notification index preflight ' + json.dumps(summary, sort_keys=True))
    print('::notice:: No migration attempted. Counts do not establish index-drop cost or live budget.')
    if '--require-bounds' in sys.argv and not within_preflight_bounds(summary):
        raise SystemExit('Notification preflight exceeded bounds; no migration attempted.')


if __name__ == '__main__':
    main()
