#!/usr/bin/env python3
"""Read-only per-collection coverage check for the legacy root backfill.

Prints only aggregate counts: collection, legacy rows, legacy-only rows and
canonical rows. Never prints a path, id or document value.
"""
import json
import sys

from scripts.loadtest.retired_table_preflight import remote_query

COLLECTIONS = ('chats', 'messages', 'notifications', 'posts', 'comments',
               'users', 'friends', 'friend_requests')

SQL = '''SELECT ?1 AS collection,
  (SELECT COUNT(*) FROM friendbook_cf_documents WHERE collection=?1) AS legacy_rows,
  (SELECT COUNT(*) FROM friendbook_cf_documents l WHERE l.collection=?1
     AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=l.path)) AS legacy_only,
  (SELECT COUNT(*) FROM friendbook_cf_firestore_documents WHERE parent_path=?1) AS canonical_rows'''


def main():
    rows = []
    for collection in COLLECTIONS:
        result, meta = remote_query(SQL, (collection,))
        if not result:
            raise SystemExit('inventory unavailable')
        row = result[0]
        read = meta.get('rows_read')
        rows.append({
            'collection': collection,
            'legacy_rows': row.get('legacy_rows'),
            'legacy_only': row.get('legacy_only'),
            'canonical_rows': row.get('canonical_rows'),
            'rows_read': read,
        })
    table = ' | '.join(f"{r['collection']} {r['legacy_rows']}/{r['legacy_only']}/{r['canonical_rows']}"
                       for r in rows)
    print('::notice:: Coverage (legacy rows / legacy-only / canonical rows): ' + table)
    remaining = [r for r in rows if r['legacy_only']]
    remaining_total = sum(r['legacy_only'] for r in rows)
    print('::notice:: ' + json.dumps({'collections_checked': len(rows),
                                      'legacy_only_total': remaining_total,
                                      'collections_with_fallback': [r['collection'] for r in remaining]}))
    if remaining_total:
        print('::warning:: Legacy fallback is still needed for at least one checked collection.')
        return 1
    print('::notice:: All checked collections have zero legacy-only root rows.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
