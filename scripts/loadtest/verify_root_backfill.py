#!/usr/bin/env python3
"""Read-only per-collection coverage check for the legacy root backfill.

Prints only aggregate counts: collection, legacy rows, legacy-only rows and
canonical rows. Never prints a path, id or document value.
"""
import json
import sys

from scripts.loadtest.retired_table_preflight import remote_query

COLLECTIONS = ('chats', 'messages', 'notifications', 'posts', 'comments',
               'users', 'friends', 'friend_requests',
               'groups', 'lives', 'reels', 'stories', 'live_chat')

# One scan of the legacy table: a per-collection count and how many of those
# paths have no canonical row yet.
LEGACY_SQL = '''SELECT l.collection AS collection, COUNT(*) AS legacy_rows,
  SUM(CASE WHEN f.path IS NULL THEN 1 ELSE 0 END) AS legacy_only
FROM friendbook_cf_documents l
LEFT JOIN friendbook_cf_firestore_documents f ON f.path=l.path
GROUP BY l.collection'''

CANONICAL_SQL = '''SELECT parent_path AS collection, COUNT(*) AS canonical_rows
FROM friendbook_cf_firestore_documents GROUP BY parent_path'''


def main():
    try:
        legacy_rows, _ = remote_query(LEGACY_SQL, ())
        canonical_rows, _ = remote_query(CANONICAL_SQL, ())
    except Exception as error:  # noqa: BLE001 - never echo provider text
        print('::error:: Coverage query failed: ' + type(error).__name__ + ' (no changes made).')
        return 2
    legacy = {row.get('collection'): row for row in legacy_rows if isinstance(row, dict)}
    canonical = {row.get('collection'): row.get('canonical_rows', 0) for row in canonical_rows if isinstance(row, dict)}
    checked = []
    for collection in COLLECTIONS:
        row = legacy.get(collection, {})
        checked.append({
            'collection': collection,
            'legacy_rows': int(row.get('legacy_rows') or 0),
            'legacy_only': int(row.get('legacy_only') or 0),
            'canonical_rows': int(canonical.get(collection) or 0),
        })
    table = ', '.join(f"{r['collection']} {r['legacy_rows']}/{r['legacy_only']}/{r['canonical_rows']}" for r in checked)
    print('::notice:: Coverage (legacy rows / legacy-only / canonical rows): ' + table)
    remaining = [r for r in checked if r['legacy_only']]
    other_legacy = [{'collection': name, 'legacy_only': int(row.get('legacy_only') or 0)}
                    for name, row in legacy.items() if name and name not in COLLECTIONS and row.get('legacy_only')]
    print('::notice:: ' + json.dumps({'collections_checked': len(checked),
                                      'legacy_only_total': sum(r['legacy_only'] for r in checked),
                                      'collections_with_fallback': [r['collection'] for r in remaining],
                                      'other_collections_with_legacy_only': other_legacy}))
    if remaining:
        print('::warning:: Legacy fallback is still needed for at least one checked collection.')
        return 1
    print('::notice:: All checked collections have zero legacy-only root rows.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
