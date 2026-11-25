#!/usr/bin/env python3
"""Bounded, read-only legacy document inventory; never logs paths or content."""
import json
import urllib.error
from scripts.loadtest.retired_table_preflight import remote_query

CAP = 5001
# A single statement gives a consistent coverage snapshot. If this cap is
# reached the result is explicitly incomplete; do not use it for cutover.
FULL_CAP = 25001
SQL = '''WITH sample AS MATERIALIZED (
  SELECT path, collection FROM friendbook_cf_documents ORDER BY path LIMIT ?
)
SELECT sample.collection AS collection, COUNT(*) AS sampled,
       SUM(CASE WHEN f.path IS NULL THEN 1 ELSE 0 END) AS legacy_only
FROM sample LEFT JOIN friendbook_cf_firestore_documents f ON f.path=sample.path
GROUP BY sample.collection'''
ALLOWED = {'users', 'posts', 'reels', 'stories', 'chats', 'messages', 'friends',
           'friend_requests', 'follows', 'groups', 'pages', 'notifications',
           'official_broadcasts', 'officialBroadcasts', 'lives', 'pokes', 'payments',
           'pushDevices', 'reports', 'feedback', 'config', 'events', 'gifts',
           'referrals', 'subscriptions', 'comments', 'likes', 'followers', 'following', 'saves',
           'reactions', 'crashReports', 'sessions', 'chat'}


def inspect(query, cap=CAP):
    if type(cap) is not int or not 1 <= cap <= FULL_CAP:
        raise ValueError('Invalid inventory cap')
    rows, meta = query(SQL, (cap,))
    read = meta.get('rows_read')
    if type(read) is not int or read < 0:
        raise ValueError('D1 read accounting unavailable')
    collections = {}
    for row in rows:
        sampled, missing = row.get('sampled'), row.get('legacy_only')
        if type(sampled) is not int or type(missing) is not int or not 0 <= missing <= sampled <= cap:
            raise ValueError('Invalid inventory')
        name = row.get('collection') if row.get('collection') in ALLOWED else 'other'
        entry = collections.setdefault(name, {'sampled': 0, 'legacy_only': 0})
        entry['sampled'] += sampled
        entry['legacy_only'] += missing
    total = sum(x['sampled'] for x in collections.values())
    if total > cap:
        raise ValueError('Invalid inventory size')
    return {'collections': collections, 'sampled': total, 'truncated': total == cap,
            'rows_read': read, 'cap': cap}


def inspect_users(query):
    rows, meta = query('''WITH sample AS MATERIALIZED (
      SELECT path FROM friendbook_cf_documents INDEXED BY idx_friendbook_cf_documents_collection_updated
      WHERE collection='users' LIMIT 1001
    ) SELECT COUNT(*) AS sampled, COALESCE(SUM(CASE WHEN f.path IS NULL THEN 1 ELSE 0 END),0) AS legacy_only
      FROM sample LEFT JOIN friendbook_cf_firestore_documents f ON f.path=sample.path''', ())
    if len(rows) != 1:
        raise ValueError('Invalid user inventory')
    sampled, missing = rows[0].get('sampled'), rows[0].get('legacy_only')
    read = meta.get('rows_read')
    if (type(sampled) is not int or type(missing) is not int or not 0 <= missing <= sampled <= 1001
            or type(read) is not int or read < 0):
        raise ValueError('Invalid user inventory')
    return {'sampled': sampled, 'legacy_only': missing, 'truncated': sampled == 1001, 'rows_read': read}


def require_complete_canonical(result):
    if result.get('truncated') is not False:
        raise ValueError('Inventory is truncated or incomplete')
    collections = result.get('collections', {})
    if any(type(row.get('legacy_only')) is not int or row['legacy_only'] != 0
           for row in collections.values()):
        raise ValueError('Legacy-only documents remain')
    if 'legacy_only' in result and result['legacy_only'] != 0:
        raise ValueError('Legacy-only users remain')
    return result


def main():
    import sys
    users_only = '--users' in sys.argv
    require_canonical = '--require-no-legacy-only' in sys.argv
    try:
        result = inspect_users(remote_query) if users_only else inspect(remote_query, FULL_CAP if '--full' in sys.argv or require_canonical else CAP)
        if require_canonical:
            require_complete_canonical(result)
    except (ValueError, KeyError, TypeError, urllib.error.URLError, TimeoutError):
        raise SystemExit('Inventory unavailable or incomplete; no changes made.')
    print('::notice:: Bounded ' + ('user' if users_only else 'document') + ' inventory ' + json.dumps(result, sort_keys=True))
    if result['truncated']:
        print('::warning:: Inventory is truncated; it does not prove complete coverage.')


if __name__ == '__main__':
    main()
