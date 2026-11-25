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

# Preserve the old exact template solely for historical analytics attribution;
# it must never execute during current coverage checks.
HISTORICAL_CANONICAL_SQL = '''SELECT parent_path AS collection, COUNT(*) AS canonical_rows
FROM friendbook_cf_firestore_documents GROUP BY parent_path'''

# Only these root counts are consumed below. Seek the existing parent index
# instead of scanning/grouping every nested message collection in the database.
CANONICAL_SQL = '''SELECT parent_path AS collection, COUNT(*) AS canonical_rows
FROM friendbook_cf_firestore_documents
WHERE parent_path IN (?,?,?,?,?,?,?,?,?,?,?,?,?) GROUP BY parent_path'''



def validate_aggregates(rows, canonical=False):
    """Reject malformed aggregate proof instead of coercing missing data to zero."""
    if not isinstance(rows, list):
        raise ValueError('Invalid coverage response')
    result = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('Invalid coverage row')
        name = row.get('collection')
        if not isinstance(name, str) or not name or name in result:
            raise ValueError('Invalid coverage collection')
        fields = ('canonical_rows',) if canonical else ('legacy_rows', 'legacy_only')
        if any(type(row.get(field)) is not int or row[field] < 0 for field in fields):
            raise ValueError('Invalid coverage count')
        if canonical:
            if name not in COLLECTIONS or row['canonical_rows'] == 0:
                raise ValueError('Unexpected canonical group')
        elif row['legacy_rows'] == 0 or row['legacy_only'] > row['legacy_rows']:
            raise ValueError('Inconsistent legacy coverage')
        result[name] = row
    return result


def main():
    try:
        legacy_rows, _ = remote_query(LEGACY_SQL, ())
        canonical_rows, _ = remote_query(CANONICAL_SQL, COLLECTIONS)
        legacy = validate_aggregates(legacy_rows)
        canonical = validate_aggregates(canonical_rows, canonical=True)
    except Exception as error:  # noqa: BLE001 - never echo provider text
        print('::error:: Coverage query failed: ' + type(error).__name__ + ' (no changes made).')
        return 2
    checked = []
    for collection in COLLECTIONS:
        row = legacy.get(collection, {})
        checked.append({
            'collection': collection,
            'legacy_rows': row.get('legacy_rows', 0),
            'legacy_only': row.get('legacy_only', 0),
            'canonical_rows': canonical.get(collection, {}).get('canonical_rows', 0),
        })
    table = ', '.join(f"{r['collection']} {r['legacy_rows']}/{r['legacy_only']}/{r['canonical_rows']}" for r in checked)
    print('::notice:: Coverage (legacy rows / legacy-only / canonical rows): ' + table)
    remaining = [r for r in checked if r['legacy_only']]
    # Non-root/custom collection names may be private; expose only aggregates.
    other_missing = sum(row['legacy_only'] for name, row in legacy.items() if name not in COLLECTIONS)
    other_legacy = [{'collection': 'other', 'legacy_only': other_missing}] if other_missing else []
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
