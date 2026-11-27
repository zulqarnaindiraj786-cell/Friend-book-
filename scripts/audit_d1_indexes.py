#!/usr/bin/env python3
"""Inventory the migrated local SQLite schema. Never contacts Cloudflare.

This inventories tables and indexes, not all possible dynamically generated queries.
Run cloudflare/test/index_plan_test.py separately for representative query plans.
"""
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]


def main():
    with sqlite3.connect(':memory:') as db:
        for file in sorted((ROOT / 'cloudflare/migrations').glob('*.sql')):
            db.executescript(file.read_text())
        tables = db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").fetchall()
        explicit = db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL").fetchone()[0]
        print(f'Local migrated schema: {len(tables)} tables (including FTS and its shadow tables); {explicit} explicitly created indexes.')
        print('Primary-key and UNIQUE autoindexes are listed but excluded from the explicit count.\n')
        for (table,) in tables:
            indexes = [row[1] for row in db.execute('PRAGMA index_list("' + table.replace('"', '""') + '")')]
            if not indexes and table == 'friendbook_cf_social_guards':
                description = '(transient guard table; constraint/trigger use, not a lookup table)'
            elif not indexes and table.startswith('friendbook_cf_post_search'):
                description = '(FTS virtual/shadow table; FTS manages its own access paths)'
            else:
                description = ', '.join(indexes) if indexes else '(no secondary index; review usage)'
            print(f'{table}: {description}')


if __name__ == '__main__':
    main()
