#!/usr/bin/env python3
"""Back up the retired status tables before the DROP migration.

Writes one JSONL file per existing table, then re-reads every table and
compares a checksum of the second pass with the first. Only aggregate counts
and checksums are printed; row contents stay inside the backup files that the
workflow uploads as an artifact.
"""
import hashlib
import json
import pathlib
import sys

from scripts.loadtest.retired_table_preflight import TABLES, remote_query

PAGE = 250


def existing_tables():
    names, _ = remote_query(
        'SELECT name FROM sqlite_master WHERE type=? AND name IN ('
        + ','.join('?' for _ in TABLES) + ')', ('table', *TABLES))
    found = [row['name'] for row in names if row.get('name') in TABLES]
    return [name for name in TABLES if name in found]


def checksum_pages(table):
    """Stable checksum over rowid order plus the row count."""
    digest = hashlib.sha256()
    rows = 0
    offset = 0
    while True:
        page, _ = remote_query(f'SELECT * FROM {table} ORDER BY rowid LIMIT ? OFFSET ?', (PAGE, offset))
        if not page:
            break
        # json.dumps with sorted keys is deterministic for identical row sets.
        for row in page:
            digest.update(json.dumps(row, sort_keys=True, separators=(',', ':')).encode())
            digest.update(b'\n')
        rows += len(page)
        offset += len(page)
        if len(page) < PAGE:
            break
    return rows, digest.hexdigest()


def write_backup(table, directory):
    path = directory / f'{table}.jsonl'
    rows = 0
    offset = 0
    with path.open('w', encoding='utf-8') as handle:
        while True:
            page, _ = remote_query(f'SELECT * FROM {table} ORDER BY rowid LIMIT ? OFFSET ?', (PAGE, offset))
            if not page:
                break
            for row in page:
                handle.write(json.dumps(row, sort_keys=True, separators=(',', ':')) + '\n')
            rows += len(page)
            offset += len(page)
            if len(page) < PAGE:
                break
    return rows


def main():
    directory = pathlib.Path('retired-backup')
    directory.mkdir(exist_ok=True)
    try:
        tables = existing_tables()
    except Exception as error:  # noqa: BLE001 - never echo provider text
        print('::error:: Could not list retired tables: ' + type(error).__name__)
        return 2
    if not tables:
        print('::error:: No retired table was found; refusing to continue without a backup.')
        return 2
    report = []
    ok = True
    for table in tables:
        try:
            written = write_backup(table, directory)
            rechecked_rows, rechecked = checksum_pages(table)
            stored = hashlib.sha256((directory / f'{table}.jsonl').read_bytes()).hexdigest()
            digest_ok = written == rechecked_rows
            report.append({'table': table, 'rows': written, 'reread_rows': rechecked_rows,
                           'content_sha256': stored[:16], 'reread_sha256': rechecked[:16],
                           'rows_match': digest_ok, 'content_matches_reread': stored == rechecked})
            ok = ok and digest_ok and stored == rechecked
        except Exception as error:  # noqa: BLE001
            report.append({'table': table, 'failed': type(error).__name__})
            ok = False
    print('::notice:: Retired table backup ' + json.dumps(report))
    total = sum(entry.get('rows', 0) for entry in report)
    print('::notice:: Backed-up rows total: ' + str(total))
    if not ok:
        print('::error:: Backup verification failed; the DROP must not run.')
        return 1
    (directory / 'manifest.json').write_text(json.dumps(report, indent=1), encoding='utf-8')
    print('::notice:: Every backed-up table re-read with an identical checksum.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
