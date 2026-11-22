#!/usr/bin/env python3
"""Bounded aggregate-only preflight before dropping the retired status tables.

No application documents are read or changed. A cap and account analytics are
risk controls, not a prediction of D1 billing for DROP or a live quota guarantee.
"""
import json
import os
import urllib.error
import urllib.request

TABLES = ('friendbook_cf_presence', 'friendbook_cf_presence_sessions',
          'friendbook_cf_presence_sessions_fullcopy', 'friendbook_cf_presence_sessions_copy',
          'friendbook_cf_typing', 'friendbook_cf_typing_sessions', 'friendbook_cf_typing_sessions_fullcopy')
CAP = 5001
MAX_TOTAL = 5010
# Conservative proxy for row/index maintenance; D1 DROP billing is not known.
MAX_WEIGHT = 30_000


class SampleLimit(ValueError):
    def __init__(self, counts, reads):
        super().__init__('Retired-table sample exceeds the removal cap')
        self.counts = counts
        self.reads = reads


def inspect(query):
    metadata, meta = query("SELECT name FROM sqlite_master WHERE type='table' AND name IN (" +
                           ','.join('?' for _ in TABLES) + ')', TABLES)
    names = [row.get('name') for row in metadata]
    if any(name not in TABLES for name in names) or len(set(names)) != len(names):
        raise ValueError('Invalid schema response')
    reads = meta.get('rows_read')
    if type(reads) is not int or reads < 0:
        raise ValueError('D1 read accounting unavailable')
    counts = {}
    for name in TABLES:
        if name not in names:
            counts[name] = 0
            continue
        # Identifiers are from the fixed allowlist, never from the response.
        rows, cost = query(f'SELECT count(*) AS n FROM (SELECT 1 FROM "{name}" LIMIT {CAP})', ())
        if len(rows) != 1 or type(rows[0].get('n')) is not int or not 0 <= rows[0]['n'] <= CAP:
            raise ValueError('Invalid bounded count')
        if type(cost.get('rows_read')) is not int or cost['rows_read'] < 0:
            raise ValueError('D1 read accounting unavailable')
        reads += cost['rows_read']
        counts[name] = rows[0]['n']
    if any(count == CAP for count in counts.values()) or sum(counts.values()) > MAX_TOTAL:
        raise SampleLimit(counts, reads)
    placeholders = ','.join('?' for _ in TABLES)
    objects, cost = query(f"SELECT type,tbl_name,COUNT(*) AS n FROM sqlite_master WHERE type IN ('index','trigger') AND tbl_name IN ({placeholders}) GROUP BY type,tbl_name", TABLES)
    if type(cost.get('rows_read')) is not int or cost['rows_read'] < 0:
        raise ValueError('D1 read accounting unavailable')
    reads += cost['rows_read']
    index_counts = dict.fromkeys(TABLES, 0)
    for item in objects:
        name, kind, number = item.get('tbl_name'), item.get('type'), item.get('n')
        if name not in TABLES or kind not in ('index', 'trigger') or type(number) is not int or not 0 <= number <= 20:
            raise ValueError('Invalid schema response')
        if kind == 'trigger' and number:
            raise ValueError('Unexpected retired-table trigger')
        if kind == 'index': index_counts[name] = number
    references, cost = query(f"SELECT COUNT(*) AS n FROM sqlite_master WHERE type='table' AND name NOT IN ({placeholders}) AND (" +
        ' OR '.join('instr(lower(sql), ?) > 0' for _ in TABLES) + ')', (*TABLES, *TABLES))
    if (type(cost.get('rows_read')) is not int or cost['rows_read'] < 0 or len(references) != 1
            or references[0].get('n') != 0):
        raise ValueError('Unexpected referencing schema')
    reads += cost['rows_read']
    weight = sum(counts[name] * (1 + index_counts[name]) for name in TABLES)
    if weight > MAX_WEIGHT:
        raise ValueError('Retired-table index weight exceeds the removal cap')
    return {'table_counts': counts, 'index_counts': index_counts, 'weighted_rows': weight,
            'rows_read': reads, 'cap': MAX_TOTAL}


def remote_query(sql, params):
    token = os.environ.get('CLOUDFLARE_API_TOKEN')
    if not token:
        raise ValueError('Cloudflare token unavailable')
    url = ('https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090'
           '/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query')
    req = urllib.request.Request(url, data=json.dumps({'sql': sql, 'params': params}).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=45) as response:
        payload = json.load(response)
    if not payload.get('success') or len(payload.get('result', [])) != 1:
        raise ValueError('D1 request failed')
    item = payload['result'][0]
    if not item.get('success') or not isinstance(item.get('results'), list) or not isinstance(item.get('meta'), dict):
        raise ValueError('Incomplete D1 response')
    return item['results'], item['meta']


def main():
    try:
        report = inspect(remote_query)
    except SampleLimit as exc:
        print('::warning:: Retired-table sample exceeds safe cap ' + json.dumps({
            'table_counts': exc.counts, 'rows_read': exc.reads, 'cap': MAX_TOTAL}, sort_keys=True))
        raise SystemExit('Retired-table preflight failed; no migration attempted.')
    except (ValueError, KeyError, TypeError, urllib.error.URLError, TimeoutError) as exc:
        allowed = {'Invalid schema response', 'D1 read accounting unavailable', 'Invalid bounded count',
                   'Unexpected retired-table trigger', 'Unexpected referencing schema',
                   'Retired-table index weight exceeds the removal cap',
                   'Cloudflare token unavailable', 'D1 request failed', 'Incomplete D1 response'}
        detail = str(exc) if str(exc) in allowed else 'request unavailable'
        print('::error:: Retired-table preflight: ' + detail)
        raise SystemExit('Retired-table preflight failed; no migration attempted.')
    print('::notice:: Bounded retired-table preflight ' + json.dumps(report, sort_keys=True))
    print('::notice:: DROP billing and concurrent changes remain unknown; cap is not a live quota guarantee.')


if __name__ == '__main__':
    main()
