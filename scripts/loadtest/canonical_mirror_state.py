#!/usr/bin/env python3
"""Read-only, schema-only diagnosis for a reappeared application mirror."""
import json
from scripts.loadtest.retired_table_preflight import remote_query

TABLES = (
    'friendbook_cf_documents',
    'friendbook_cf_documents_retired_backup',
    'friendbook_cf_documents_retired_backup_schema',
    'friendbook_cf_documents_reappeared_0106',
)


def inspect(query):
    marks = ','.join('?' for _ in TABLES)
    rows, meta = query(
        f"SELECT name FROM sqlite_master WHERE type='table' AND name IN ({marks}) ORDER BY name",
        TABLES,
    )
    names = {row.get('name') for row in rows}
    if not names.issubset(set(TABLES)):
        raise ValueError('Unexpected schema response')
    nonempty = None
    reads = meta.get('rows_read')
    if type(reads) is not int or reads < 0:
        raise ValueError('D1 read accounting unavailable')
    if 'friendbook_cf_documents' in names:
        found, cost = query('SELECT 1 AS present FROM friendbook_cf_documents LIMIT 1', ())
        nonempty = bool(found)
        extra = cost.get('rows_read')
        if type(extra) is not int or extra < 0:
            raise ValueError('D1 read accounting unavailable')
        reads += extra
    return {
        'mirror_table_exists': 'friendbook_cf_documents' in names,
        'mirror_has_rows': nonempty,
        'backup_exists': 'friendbook_cf_documents_retired_backup' in names,
        'backup_schema_exists': 'friendbook_cf_documents_retired_backup_schema' in names,
        'reappeared_snapshot_exists': 'friendbook_cf_documents_reappeared_0106' in names,
        'rows_read': reads,
    }


if __name__ == '__main__':
    try:
        print('::notice:: Canonical mirror schema state ' + json.dumps(inspect(remote_query), sort_keys=True))
    except Exception as error:
        if str(error) in ('D1 read accounting unavailable', 'Unexpected schema response'):
            print('::error:: Canonical mirror schema diagnosis: ' + str(error))
        else:
            print('::error:: Canonical mirror schema diagnosis unavailable')
        raise SystemExit(1)
