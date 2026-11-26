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
    # Explain the old snapshot-only mismatch without returning document paths,
    # identities or payloads. Match its rows to the canonical D1 collection by
    # path, then classify whether the JSON payload agrees. This is read-only.
    classification_sql = f'''WITH snapshot_only AS (
        SELECT {columns} FROM friendbook_cf_documents_retired_backup
        EXCEPT SELECT {columns} FROM friendbook_cf_documents
    )
    SELECT classified.collection, classified.classification, COUNT(*) AS n
    FROM (
      SELECT CASE WHEN snapshot_only.collection IN
          ('users','posts','reels','stories','pages','groups','chats','friend_requests','comments','likes','followers','following','saves','payments','subscriptions','reports','feedback','gifts','lives','messages','notifications')
          THEN snapshot_only.collection ELSE 'other' END AS collection,
        CASE
          WHEN canonical.path IS NULL THEN 'no_canonical_path'
          WHEN canonical.document_json = snapshot_only.document_json THEN 'canonical_payload_exact'
          ELSE 'canonical_payload_differs'
        END AS classification
      FROM snapshot_only LEFT JOIN friendbook_cf_firestore_documents AS canonical
        ON canonical.path = snapshot_only.path
    ) AS classified
    GROUP BY classified.collection, classified.classification
    ORDER BY classified.collection, classified.classification'''
    classifications, metadata = query(classification_sql, ())
    classified = {}
    allowed_collections = {'users','posts','reels','stories','pages','groups','chats','friend_requests','comments','likes','followers','following','saves','payments','subscriptions','reports','feedback','gifts','lives','messages','notifications','other'}
    for row in classifications:
        collection, category, count = row.get('collection'), row.get('classification'), row.get('n')
        if collection not in allowed_collections or category not in {'no_canonical_path', 'canonical_payload_exact', 'canonical_payload_differs'} \
                or type(count) is not int or count < 0:
            raise ValueError('invalid canonical match classification')
        classified[f'{collection}:{category}'] = count
    if sum(classified.values()) != result['snapshot_only']:
        raise ValueError('canonical match classification incomplete')
    reads = metadata.get('rows_read')
    if type(reads) is not int or reads < 0:
        raise ValueError('D1 read accounting unavailable')
    result['snapshot_only_canonical_match'] = classified

    current_only_sql = f'''WITH current_only AS (
        SELECT {columns} FROM friendbook_cf_documents
        EXCEPT SELECT {columns} FROM friendbook_cf_documents_retired_backup
    )
    SELECT current_only.collection, CASE
        WHEN canonical.path IS NULL THEN 'no_canonical_path'
        WHEN canonical.document_json = current_only.document_json THEN 'canonical_payload_exact'
        ELSE 'canonical_payload_differs'
      END AS classification, COUNT(*) AS n
    FROM current_only LEFT JOIN friendbook_cf_firestore_documents AS canonical
      ON canonical.path = current_only.path
    GROUP BY current_only.collection, classification ORDER BY current_only.collection, classification'''
    # collection is already a legacy fixed collection label; normalize unknown
    # values before logging so this report remains aggregate and sanitized.
    current_rows, current_meta = query(current_only_sql, ())
    current_classified = {}
    for row in current_rows:
        collection = row.get('collection')
        if collection not in allowed_collections:
            collection = 'other'
        category, count = row.get('classification'), row.get('n')
        if category not in {'no_canonical_path', 'canonical_payload_exact', 'canonical_payload_differs'} \
                or type(count) is not int or count < 0:
            raise ValueError('invalid current-only canonical classification')
        key = f'{collection}:{category}'
        current_classified[key] = current_classified.get(key, 0) + count
    if sum(current_classified.values()) != result['current_only']:
        raise ValueError('current-only canonical classification incomplete')
    current_reads = current_meta.get('rows_read')
    if type(current_reads) is not int or current_reads < 0:
        raise ValueError('D1 read accounting unavailable')
    result['current_only_canonical_match'] = current_classified
    total_reads += reads + current_reads
    result['rows_read'] = total_reads
    print('::notice:: Canonical mirror snapshot diff ' + json.dumps(result, sort_keys=True))
    return result


def inspect(query):
    rows, meta = query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name IN (?,?,?,?,?) ORDER BY name",
        ('friendbook_cf_documents', 'friendbook_cf_documents_retired_backup',
         'friendbook_cf_documents_retired_backup_schema', 'friendbook_cf_documents_reappeared_0106',
         'friendbook_cf_documents_reappeared_archive_0109'))
    names = {row.get('name') for row in rows}
    if 'friendbook_cf_documents' in names:
        raise ValueError('Legacy application mirror still exists')
    required = {'friendbook_cf_documents_retired_backup',
                'friendbook_cf_documents_retired_backup_schema',
                'friendbook_cf_documents_reappeared_0106',
                'friendbook_cf_documents_reappeared_archive_0109'}
    if not required <= names:
        raise ValueError('Rollback snapshot or schema metadata missing')
    # These tables are immutable after the guarded migration. A COUNT(*) on the
    # 11k-row rollback snapshot made every routine deploy rescan the full table;
    # schema + indexed existence probes are sufficient here. Full parity scans
    # remain available only through the separately authorized --diff path.
    snapshot, snapshot_meta = query('SELECT 1 AS present FROM friendbook_cf_documents_retired_backup LIMIT 1', ())
    if len(snapshot) != 1 or snapshot[0].get('present') != 1:
        raise ValueError('Rollback snapshot is empty')
    schema, schema_meta = query("SELECT 1 AS present FROM friendbook_cf_documents_retired_backup_schema WHERE type='table' AND name='friendbook_cf_documents' AND sql IS NOT NULL LIMIT 1", ())
    if len(schema) != 1 or schema[0].get('present') != 1:
        raise ValueError('Original schema not retained')
    archived, archive_meta = query('SELECT 1 AS present FROM friendbook_cf_documents_reappeared_archive_0109 LIMIT 1', ())
    if len(archived) != 1 or archived[0].get('present') != 1:
        raise ValueError('Reappeared mirror archive unavailable')
    read_counts=[meta.get('rows_read'),snapshot_meta.get('rows_read'),schema_meta.get('rows_read'),archive_meta.get('rows_read')]
    if any(type(value) is not int or value < 0 for value in read_counts):
        raise ValueError('D1 read accounting unavailable')
    return {'mirror_table_absent': True, 'rollback_snapshot_present': True,
            'schema_retained': True, 'archive_present': True,
            'rows_read': sum(read_counts)}


if __name__ == '__main__':
    try:
        if '--diff' in sys.argv:
            diff(safe_query)
        else:
            print('::notice:: Canonical mirror retirement ' + json.dumps(inspect(safe_query), sort_keys=True))
    except Exception as error:
        allowed = {'Legacy application mirror still exists', 'Rollback snapshot or schema metadata missing',
                   'Invalid rollback snapshot count', 'Original schema not retained', 'Reappeared mirror archive unavailable', 'source or snapshot table missing',
                   'invalid aggregate diff', 'D1 read accounting unavailable'}
        message = str(error) if str(error) in allowed or isinstance(error, SanitizedHttpError) else type(error).__name__
        print('::error:: Canonical mirror audit: ' + message)
        raise SystemExit('Canonical mirror retirement verification failed: ' + message)
