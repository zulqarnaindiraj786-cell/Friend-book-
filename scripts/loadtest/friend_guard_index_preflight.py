#!/usr/bin/env python3
"""Capped, aggregate-only D1 investigation for an unshipped index experiment.

This runs one read-only SQL statement, returns no IDs or document values, and
never creates indexes or changes app data. LIMIT bounds source rows sampled,
not billed D1 reads or future migration cost. A stale result is not a permit to
deploy; traffic can change immediately after the snapshot.
"""
import json
import os
import sys
import urllib.error
import urllib.request

CAP = 50001
SQL = """
WITH canonical AS (SELECT parent_path,document_json FROM friendbook_cf_firestore_documents LIMIT 50001),
legacy AS (SELECT collection,document_json FROM friendbook_cf_documents LIMIT 50001),
cstats AS (SELECT COUNT(*) AS sampled,
  COALESCE(SUM(CASE WHEN parent_path='friends' THEN 1 ELSE 0 END),0) AS friends,
  COALESCE(SUM(CASE WHEN parent_path='friends' AND NOT json_valid(document_json) THEN 1 ELSE 0 END),0) AS invalid,
  COALESCE(SUM(CASE WHEN parent_path='friends' AND json_valid(document_json) THEN
    CASE WHEN json_type(document_json,'$.participants')='object' OR json_array_length(document_json,'$.participants')>2
      OR json_type(document_json,'$.fields.participants.arrayValue.values') IS NOT NULL THEN 1 ELSE 0 END ELSE 0 END),0) AS exceptions,
  COALESCE(SUM(CASE WHEN parent_path='friends' AND json_valid(document_json) THEN
    CASE WHEN EXISTS (SELECT 1 FROM json_each(c.document_json,'$.fields.participants.arrayValue.values') e
      WHERE typeof(e.value)='text' AND json_valid(e.value)=0) THEN 1 ELSE 0 END ELSE 0 END),0) AS bad_wrapped
  FROM canonical c),
lstats AS (SELECT COUNT(*) AS sampled,
  COALESCE(SUM(CASE WHEN collection='friends' THEN 1 ELSE 0 END),0) AS friends,
  COALESCE(SUM(CASE WHEN collection='friends' AND NOT json_valid(document_json) THEN 1 ELSE 0 END),0) AS invalid,
  COALESCE(SUM(CASE WHEN collection='friends' AND json_valid(document_json) THEN
    CASE WHEN json_type(document_json,'$.fields')='object' OR json_type(document_json,'$.participants')='object'
      OR json_array_length(document_json,'$.participants')>2 THEN 1 ELSE 0 END ELSE 0 END),0) AS exceptions,
  COALESCE(SUM(CASE WHEN collection='friends' AND json_valid(document_json) THEN
    CASE WHEN EXISTS (SELECT 1 FROM json_each(l.document_json,'$.fields.participants.arrayValue.values') e
      WHERE typeof(e.value)='text' AND json_valid(e.value)=0) THEN 1 ELSE 0 END ELSE 0 END),0) AS bad_wrapped
  FROM legacy l)
SELECT c.sampled AS canonical_sample,l.sampled AS legacy_sample,
  c.friends AS canonical_friends,l.friends AS legacy_friends,
  c.invalid AS canonical_invalid_json,l.invalid AS legacy_invalid_json,
  c.exceptions AS canonical_exceptions,l.exceptions AS legacy_exceptions,
  c.bad_wrapped AS canonical_bad_wrapped,l.bad_wrapped AS legacy_bad_wrapped
FROM cstats c CROSS JOIN lstats l
"""
KEYS = ('canonical_sample', 'legacy_sample', 'canonical_friends', 'legacy_friends',
        'canonical_invalid_json', 'legacy_invalid_json', 'canonical_exceptions',
        'legacy_exceptions', 'canonical_bad_wrapped', 'legacy_bad_wrapped')


def summarize(payload):
    if not payload.get('success') or len(payload.get('result', [])) != 1:
        raise ValueError('D1 returned an incomplete response')
    result = payload['result'][0]
    if not result.get('success') or len(result.get('results', [])) != 1:
        raise ValueError('D1 returned an incomplete aggregate')
    row = result['results'][0]
    if any(type(row.get(k)) is not int or row[k] < 0 for k in KEYS):
        raise ValueError('D1 returned invalid aggregate counts')
    for prefix in ('canonical', 'legacy'):
        total, friends = row[prefix + '_sample'], row[prefix + '_friends']
        if total > CAP or friends > total or any(row[prefix + '_' + k] > friends
                for k in ('invalid_json', 'exceptions', 'bad_wrapped')):
            raise ValueError('D1 returned inconsistent aggregate counts')
    summary = {k: row[k] for k in KEYS}
    summary['truncated'] = row['canonical_sample'] == CAP or row['legacy_sample'] == CAP
    summary['d1_reported_rows_read'] = (result.get('meta') or {}).get('rows_read')
    return summary


def within_index_build_bounds(summary):
    # Bound complete source-table scans and potential new index entries. This
    # snapshot is not a quota guarantee: account analytics can lag and tables
    # can change, so recheck headroom immediately before and after this query.
    return (not summary['truncated']
            and summary['canonical_sample'] + summary['legacy_sample'] <= 30_000
            and summary['canonical_exceptions'] + summary['legacy_exceptions'] <= 1_000
            and summary['canonical_invalid_json'] == 0 and summary['legacy_invalid_json'] == 0
            and type(summary['d1_reported_rows_read']) is int
            and 0 <= summary['d1_reported_rows_read'] <= 60_000)


def main():
    token = os.environ.get('CLOUDFLARE_API_TOKEN')
    if not token:
        raise SystemExit('Cloudflare token unavailable; no production changes made.')
    account = '92f3d04598822de2e49e14561218d090'
    database = 'e1c605ca-a50b-46c6-9deb-56dd1f70d163'
    req = urllib.request.Request(
        f'https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{database}/query',
        data=json.dumps({'sql': SQL}).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            summary = summarize(json.load(response))
    except (urllib.error.URLError, ValueError, TimeoutError, KeyError, TypeError):
        raise SystemExit('Read-only preflight unavailable or incomplete; no production changes made.')
    print('::notice:: Aggregate-only friendship index preflight ' + json.dumps(summary, sort_keys=True))
    if summary['truncated']:
        print('::warning:: Sampling cap reached; production table size and index cost remain unknown.')
    if summary['canonical_invalid_json'] or summary['legacy_invalid_json']:
        print('::warning:: Invalid JSON found; do not create indexes before investigation.')
    print('::notice:: No migration was attempted; billed quota headroom and index-build cost are not verified by this preflight.')
    if '--require-index-build-safe' in sys.argv and not within_index_build_bounds(summary):
        raise SystemExit('Friendship index preflight exceeded conservative bounds; no migration attempted.')


if __name__ == '__main__':
    main()
