#!/usr/bin/env python3
"""Bounded, read-only legacy document inventory; never logs paths or content."""
import json
import urllib.error
from scripts.loadtest.retired_table_preflight import remote_query

CAP = 5001
SQL = '''WITH sample AS MATERIALIZED (
  SELECT path, collection FROM friendbook_cf_documents ORDER BY path LIMIT ?
)
SELECT sample.collection AS collection, COUNT(*) AS sampled,
       SUM(CASE WHEN f.path IS NULL THEN 1 ELSE 0 END) AS legacy_only
FROM sample LEFT JOIN friendbook_cf_firestore_documents f ON f.path=sample.path
GROUP BY sample.collection'''
ALLOWED = {'users', 'posts', 'reels', 'stories', 'chats', 'messages', 'friends',
           'friend_requests', 'follows', 'groups', 'pages', 'notifications',
           'official_broadcasts', 'lives', 'pokes', 'payments'}


def inspect(query):
    rows, meta = query(SQL, (CAP,))
    read = meta.get('rows_read')
    if type(read) is not int or read < 0:
        raise ValueError('D1 read accounting unavailable')
    collections = {}
    for row in rows:
        sampled, missing = row.get('sampled'), row.get('legacy_only')
        if type(sampled) is not int or type(missing) is not int or not 0 <= missing <= sampled <= CAP:
            raise ValueError('Invalid inventory')
        name = row.get('collection') if row.get('collection') in ALLOWED else 'other'
        entry = collections.setdefault(name, {'sampled': 0, 'legacy_only': 0})
        entry['sampled'] += sampled
        entry['legacy_only'] += missing
    total = sum(x['sampled'] for x in collections.values())
    if total > CAP:
        raise ValueError('Invalid inventory size')
    return {'collections': collections, 'sampled': total, 'truncated': total == CAP,
            'rows_read': read}


def main():
    try:
        result = inspect(remote_query)
    except (ValueError, KeyError, TypeError, urllib.error.URLError, TimeoutError):
        raise SystemExit('Inventory unavailable; no changes made.')
    print('::notice:: Bounded document inventory ' + json.dumps(result, sort_keys=True))
    if result['truncated']:
        print('::warning:: Inventory is truncated; it does not prove complete coverage.')


if __name__ == '__main__':
    main()
