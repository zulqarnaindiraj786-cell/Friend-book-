#!/usr/bin/env python3
"""Verify and report D1 table names only, without reading application rows."""
import json
import re
import urllib.error
from scripts.loadtest.retired_table_preflight import TABLES, remote_query


def inspect(query):
    rows, meta = query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name LIMIT 501", ())
    names = [row.get('name') for row in rows]
    if (len(names) >= 501 or any(not isinstance(n, str) or not re.fullmatch(r'[A-Za-z0-9_]{1,128}', n) for n in names)
            or len(set(names)) != len(names) or type(meta.get('rows_read')) is not int):
        raise ValueError('Invalid or truncated schema listing')
    if any(name in names for name in TABLES):
        raise ValueError('Retired tables remain')
    if not {'friendbook_cf_firestore_documents', 'friendbook_cf_documents'}.issubset(names):
        raise ValueError('Document table missing')
    return {'tables': names, 'retired_tables_remaining': 0, 'rows_read': meta['rows_read']}


def main():
    try:
        result = inspect(remote_query)
    except (ValueError, KeyError, TypeError, urllib.error.URLError, TimeoutError):
        raise SystemExit('Schema verification failed; inspect before deploying.')
    print('::notice:: Verified D1 table list ' + json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
