#!/usr/bin/env python3
"""Bounded read-only preflight before preserving the reappeared mirror by rename."""
import json
from scripts.loadtest.retired_table_preflight import remote_query

MIRROR = 'friendbook_cf_documents'
ARCHIVE = 'friendbook_cf_documents_reappeared_archive_0109'
REQUIRED = (MIRROR, 'friendbook_cf_documents_retired_backup',
            'friendbook_cf_documents_retired_backup_schema',
            'friendbook_cf_documents_reappeared_0106')
MAX_ROWS = 2000


def inspect(query):
    marks = ','.join('?' for _ in (*REQUIRED, ARCHIVE))
    rows, meta = query(
        f"SELECT name FROM sqlite_master WHERE type='table' AND name IN ({marks}) ORDER BY name",
        (*REQUIRED, ARCHIVE))
    names = {row.get('name') for row in rows}
    if not set(REQUIRED) <= names or ARCHIVE in names:
        raise ValueError('Unexpected archive/source schema state')
    count, cost = query(f'SELECT COUNT(*) AS n FROM {MIRROR}', ())
    if len(count) != 1 or type(count[0].get('n')) is not int or count[0]['n'] <= 0:
        raise ValueError('Live mirror row count unavailable')
    if count[0]['n'] > MAX_ROWS:
        raise ValueError('Live mirror exceeds reviewed archive cap')
    for report in (meta, cost):
        if type(report.get('rows_read')) is not int or report['rows_read'] < 0:
            raise ValueError('D1 read accounting unavailable')
    return {'source_present': True, 'archive_absent': True,
            'retained_snapshots_present': True, 'live_mirror_rows': count[0]['n'],
            'max_rows': MAX_ROWS, 'rows_read': meta['rows_read'] + cost['rows_read']}


if __name__ == '__main__':
    try:
        print('::notice:: Mirror archive rename preflight ' + json.dumps(inspect(remote_query), sort_keys=True))
    except Exception as error:
        allowed = {'Unexpected archive/source schema state', 'Live mirror row count unavailable',
                   'Live mirror exceeds reviewed archive cap', 'D1 read accounting unavailable'}
        message = str(error) if str(error) in allowed else type(error).__name__
        print('::error:: Mirror archive preflight: ' + message)
        raise SystemExit('Mirror archive preflight refused; no migration attempted.')
