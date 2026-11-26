#!/usr/bin/env python3
"""Read-only schema and backup verification after canonical mirror retirement."""
import json
import sys
import urllib.error
from scripts.loadtest.retired_table_preflight import remote_query


class SanitizedHttpError(Exception):
    def __init__(self, status, codes=(), reason=''):
        suffix = ' error codes ' + ','.join(map(str, codes)) if codes else ''
        safe_reasons = {'no such column', 'no such table', 'syntax error',
                        'misuse of aggregate', 'too many', 'query timed out',
                        'not authorized'}
        reason = next((candidate for candidate in safe_reasons if candidate in reason.lower()), '')
        suffix += ' ' + reason if reason else ''
        super().__init__('Cloudflare D1 HTTP ' + str(status) + suffix)


def safe_query(sql, params):
    try:
        return remote_query(sql, params)
    except urllib.error.HTTPError as error:
        codes, reasons = [], []
        try:
            body = json.loads(error.read())
            errors = list(body.get('errors', []))
            for result in body.get('result', []):
                errors.extend(result.get('errors', []))
            codes = [item.get('code') for item in errors if type(item.get('code')) is int][:5]
            reasons = [item.get('message', '') for item in errors]
        except (ValueError, TypeError, AttributeError):
            pass
        raise SanitizedHttpError(error.code, codes, ' '.join(reasons)) from None


def diff(query):
    rows, _ = query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name IN (?,?)",
        ('friendbook_cf_documents', 'friendbook_cf_documents_retired_backup'))
    found = {row.get('name') for row in rows}
    if not {'friendbook_cf_documents', 'friendbook_cf_documents_retired_backup'} <= found:
        raise ValueError('source or snapshot table missing')

    # Use the same set comparison semantics as the guarded retirement migration.
    # Splitting each bounded aggregate avoids one planner-heavy multi-join query.
    columns = 'path,collection,doc_id,owner_uid,document_json,updated_at'
    statements = {
        'current_rows': f'SELECT COUNT(*) AS n FROM friendbook_cf_documents',
        'snapshot_rows': f'SELECT COUNT(*) AS n FROM friendbook_cf_documents_retired_backup',
        'current_only': f'SELECT COUNT(*) AS n FROM (SELECT {columns} FROM friendbook_cf_documents EXCEPT SELECT {columns} FROM friendbook_cf_documents_retired_backup)',
        'snapshot_only': f'SELECT COUNT(*) AS n FROM (SELECT {columns} FROM friendbook_cf_documents_retired_backup EXCEPT SELECT {columns} FROM friendbook_cf_documents)',
    }
    result = {}
    total_reads = 0
    for key, sql in statements.items():
        result_rows, meta = query(sql, ())
        if len(result_rows) != 1 or type(result_rows[0].get('n')) is not int or result_rows[0]['n'] < 0:
            raise ValueError('invalid aggregate diff')
        reads = meta.get('rows_read')
        if type(reads) is not int or reads < 0:
            raise ValueError('D1 read accounting unavailable')
        result[key] = result_rows[0]['n']
        total_reads += reads
    result['rows_read'] = total_reads
    print('::notice:: Canonical mirror snapshot diff ' + json.dumps(result, sort_keys=True))
    return result


def inspect(query):
    rows, meta = query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name IN (?,?,?,?) ORDER BY name",
        ('friendbook_cf_documents', 'friendbook_cf_documents_retired_backup',
         'friendbook_cf_documents_retired_backup_schema', 'friendbook_cf_documents_reappeared_0106'))
    names = {row.get('name') for row in rows}
    if 'friendbook_cf_documents' in names:
        raise ValueError('Legacy application mirror still exists')
    required = {'friendbook_cf_documents_retired_backup',
                'friendbook_cf_documents_retired_backup_schema',
                'friendbook_cf_documents_reappeared_0106'}
    if not required <= names:
        raise ValueError('Rollback snapshot or schema metadata missing')
    counts, _ = query('SELECT COUNT(*) AS n FROM friendbook_cf_documents_retired_backup', ())
    if len(counts) != 1 or type(counts[0].get('n')) is not int:
        raise ValueError('Invalid rollback snapshot count')
    schema, _ = query("SELECT COUNT(*) AS n FROM friendbook_cf_documents_retired_backup_schema WHERE type='table' AND name='friendbook_cf_documents' AND sql IS NOT NULL", ())
    if len(schema) != 1 or schema[0].get('n') != 1:
        raise ValueError('Original schema not retained')
    return {'mirror_table_absent': True, 'rollback_snapshot_present': True,
            'schema_retained': True, 'snapshot_rows': counts[0]['n'],
            'rows_read': meta.get('rows_read')}


if __name__ == '__main__':
    try:
        if '--diff' in sys.argv:
            diff(safe_query)
        else:
            print('::notice:: Canonical mirror retirement ' + json.dumps(inspect(safe_query), sort_keys=True))
    except Exception as error:
        allowed = {'Legacy application mirror still exists', 'Rollback snapshot or schema metadata missing',
                   'Invalid rollback snapshot count', 'Original schema not retained', 'source or snapshot table missing',
                   'invalid aggregate diff', 'D1 read accounting unavailable'}
        message = str(error) if str(error) in allowed or isinstance(error, SanitizedHttpError) else type(error).__name__
        print('::error:: Canonical mirror audit: ' + message)
        raise SystemExit('Canonical mirror retirement verification failed: ' + message)
