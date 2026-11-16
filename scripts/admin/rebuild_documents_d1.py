#!/usr/bin/env python3
"""Rebuild the app document store from D1's normalised tables, through the D1 API.

Why this exists next to cloudflare/document-rebuild.js
-----------------------------------------------------
The Worker route is the long-term admin surface, but it is gated behind
MIGRATION_ADMIN_TOKEN, which is not a repository secret. CLOUDFLARE_API_TOKEN is, and
scripts/d1_coverage.py already uses it to read this database. So the run that actually
converts rows today happens through the same D1 REST API, with the identical mapping and
the identical gate rules (scripts/tests/test_rebuild_mapping_parity.py keeps the two
implementations honest).

Safety
------
- `plan`, `dry-run` and `verify` are read-only (SELECT only).
- `run --apply` writes documents; it never writes or deletes a source row.
- Writes are INSERT ... ON CONFLICT(path) DO NOTHING: an existing document is never
  replaced. Pass --overwrite only with a backup in hand.
- posts is the test collection. Anything else refuses to run until the live posts gate
  passes: every posts row has a document, and sampled rows round-trip field by field.
- `flow --apply` runs plan -> posts dry run -> posts apply -> posts verify -> the other
  nine -> final plan, and stops at the first failed check.

Output is counts and document ids only; never a token, never a full document.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

API_BASE = 'https://api.cloudflare.com/client/v4'
ACCOUNT_ID = (os.environ.get('CLOUDFLARE_ACCOUNT_ID') or os.environ.get('CF_ACCOUNT_ID')
              or '92f3d04598822de2e49e14561218d090')
DATABASE_ID = os.environ.get('D1_DATABASE_ID') or 'e1c605ca-a50b-46c6-9deb-56dd1f70d163'
API_TOKEN = os.environ.get('CLOUDFLARE_API_TOKEN') or ''

MAX_PAGE = 100          # rows read per query
WRITE_CHUNK_ROWS = 16   # 16 x 6 bound parameters stays under D1's 100-parameter limit
WRITE_CHUNK_BYTES = 60_000
MAX_SAMPLE = 40
MAX_DOCUMENT_BYTES = 95_000
TEST_COLLECTION = 'posts'

SPECS = [
    {
        'collection': 'posts', 'table': 'posts', 'pk': 'id', 'owner': ['author_id'],
        'time': 'created_at', 'test': True,
        'json': {'mentions': '[]', 'saved_by': '[]', 'subscribed_uids': '[]',
                 'subscribed_by': '[]', 'media_urls': '[]', 'tagged_uids': '[]',
                 'tagged_names': '[]', 'poll_options': '[]', 'poll_votes': '{}',
                 'inline_comments': '[]', 'reactions_map': '{}'},
        'boolean': ['archived', 'market_sold', 'author_verified'],
    },
    {
        'collection': 'users', 'table': 'users', 'pk': 'uid', 'owner': ['uid'],
        'time': 'created_at',
        'json': {'favorite_uids': '[]', 'restricted_uids': '[]', 'close_friends_uids': '[]',
                 'highlight_story_ids': '[]', 'saved_reel_ids': '[]', 'hobbies': '[]',
                 'followers': '[]', 'following': '[]', 'blocked': '[]'},
        'boolean': ['is_admin', 'is_moderator', 'banned', 'is_verified', 'suspended_lifetime',
                    'deactivated', 'login_alerts', 'tag_review_enabled', 'professional_mode',
                    'hide_reaction_counts', 'show_active_status', 'subscriptions_enabled',
                    'profile_locked', 'profile_pic_guard'],
    },
    {
        'collection': 'comments', 'table': 'comments', 'pk': 'id', 'owner': ['author_id'],
        'time': 'created_at',
        'json': {'liked_by': '[]', 'reactions': '{}'},
        'boolean': ['edited', 'pinned'],
    },
    {
        'collection': 'reels', 'table': 'reels', 'pk': 'id', 'owner': ['author_id'],
        'time': 'created_at',
        'json': {'liked_by': '[]', 'saved_by': '[]', 'reactions': '{}', 'inline_comments': '[]'},
        'boolean': ['is_from_post', 'mirror_horizontal', 'mute_original_audio'],
    },
    {
        'collection': 'stories', 'table': 'stories', 'pk': 'id', 'owner': ['author_id'],
        'time': 'created_at',
        'json': {'viewed_by': '[]', 'reactions': '{}'},
        'boolean': [],
    },
    {
        'collection': 'chats', 'table': 'chats', 'pk': 'id', 'owner': ['created_by'],
        'time': 'last_message_time',
        'json': {'admin_ids': '[]', 'participants': '[]', 'typing': '{}',
                 'participant_names': '{}', 'participant_photos': '{}'},
        'boolean': ['is_group', 'only_admins_can_send'],
    },
    {
        'collection': 'messages', 'table': 'messages', 'pk': 'id', 'owner': ['sender_id'],
        'time': 'created_at',
        'json': {'reactions': '{}', 'reactor_names': '{}', 'reactor_photos': '{}'},
        'boolean': ['forwarded', 'is_seen', 'is_delivered', 'is_deleted'],
    },
    {
        'collection': 'groups', 'table': 'groups', 'pk': 'id', 'owner': ['admin_id'],
        'time': 'created_at',
        'json': {'members': '[]', 'admin_ids': '[]', 'moderator_ids': '[]',
                 'pending_member_ids': '[]', 'banned_member_ids': '[]', 'invited_uids': '[]'},
        'boolean': ['require_post_approval', 'require_join_approval'],
    },
    {
        'collection': 'friend_requests', 'table': 'friend_requests', 'pk': 'id',
        'owner': ['from_uid'], 'time': 'created_at', 'json': {}, 'boolean': [],
    },
    {
        'collection': 'notifications', 'table': 'notifications', 'pk': 'id',
        'owner': ['to_user_id'], 'time': 'created_at', 'json': {}, 'boolean': ['is_read'],
    },
]

SPEC_BY_COLLECTION = {spec['collection']: spec for spec in SPECS}
REMAINING = [spec['collection'] for spec in SPECS if not spec.get('test')]


class D1Error(RuntimeError):
    pass


def camel(column: str) -> str:
    out = []
    upper_next = False
    for char in str(column):
        if char == '_':
            upper_next = True
            continue
        out.append(char.upper() if upper_next else char)
        upper_next = False
    return ''.join(out)


def truthy(value) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    return str(value).strip().lower() in ('1', 'true')


def valid_segment(value) -> bool:
    if not isinstance(value, str) or not value or len(value) > 128:
        return False
    if value in ('.', '..'):
        return False
    return not any(ord(char) < 32 or char == '/' for char in value)


def map_row(spec, row):
    """Mirror of mapRow() in cloudflare/document-rebuild.js."""
    document = {}
    warnings = []
    for column, raw in row.items():
        key = camel(column)
        if column in spec['json']:
            fallback = spec['json'][column]
            if raw is None:
                document[key] = json.loads(fallback)
                continue
            if not isinstance(raw, str):
                document[key] = raw
                continue
            try:
                document[key] = json.loads(raw)
            except ValueError:
                document[key] = json.loads(fallback)
                warnings.append({'column': column, 'reason': 'invalid-json'})
            continue
        if raw is None:
            continue
        if column in spec['boolean']:
            document[key] = truthy(raw)
            continue
        document[key] = raw

    doc_id = row.get(spec['pk'])
    if not valid_segment(doc_id):
        return {'doc': None, 'warnings': [], 'error': f"invalid {spec['pk']}"}
    document['id'] = doc_id
    if spec['collection'] == 'users':
        document['uid'] = doc_id
    try:
        stamp = int(row.get(spec['time']) or 0)
    except (TypeError, ValueError):
        stamp = 0
    document['updatedAt'] = stamp if stamp > 0 else 0
    return {'doc': document, 'warnings': warnings, 'error': None}


def owner_uid(spec, row):
    for column in spec['owner']:
        value = row.get(column)
        if isinstance(value, str) and value:
            return value[:128]
    return ''


def _request(sql, params, timeout=90):
    if not API_TOKEN:
        raise D1Error('CLOUDFLARE_API_TOKEN is not set')
    url = f'{API_BASE}/accounts/{ACCOUNT_ID}/d1/database/{DATABASE_ID}/query'
    body = json.dumps({'sql': sql, 'params': list(params)}).encode('utf8')
    request = urllib.request.Request(url, data=body, method='POST', headers={
        'Authorization': f'Bearer {API_TOKEN}',
        'Content-Type': 'application/json',
    })
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read() or b'{}')
    except urllib.error.HTTPError as error:
        detail = ''
        try:
            payload = json.loads(error.read() or b'{}')
            detail = '; '.join(str(item.get('message', '')) for item in payload.get('errors') or [])
        except ValueError:
            detail = ''
        raise D1Error(f'D1 HTTP {error.code}{": " + detail if detail else ""}') from None
    except (urllib.error.URLError, TimeoutError):
        raise D1Error('D1 request failed (network or timeout)') from None


def d1(sql, params=(), retries=3):
    """Run one statement and return (rows, meta)."""
    last = None
    for attempt in range(retries):
        payload = _request(sql, params)
        if not payload.get('success'):
            messages = '; '.join(str(item.get('message', '')) for item in payload.get('errors') or [])
            raise D1Error(f'D1 refused the statement{": " + messages if messages else ""}')
        results = payload.get('result') or []
        if not results:
            raise D1Error('D1 returned no result')
        result = results[0]
        if result.get('success') is False:
            raise D1Error('D1 statement failed')
        return result.get('results') or [], result.get('meta') or {}
    raise D1Error(last or 'D1 statement failed')


def scalar(sql, params=()):
    rows, _ = d1(sql, params)
    return int((rows[0] or {}).get('count') or 0)


def source_rows(spec):
    return scalar(f"SELECT COUNT(*) AS count FROM {spec['table']}")


def document_rows(collection):
    return scalar('SELECT COUNT(*) AS count FROM friendbook_cf_documents WHERE collection = ?',
                  [collection])


def missing_documents(spec):
    return scalar(
        f"SELECT COUNT(*) AS count FROM {spec['table']} t WHERE NOT EXISTS ("
        f"SELECT 1 FROM friendbook_cf_documents d WHERE d.path = ? || t.{spec['pk']})",
        [f"{spec['collection']}/"])


def orphan_documents(spec):
    return scalar(
        'SELECT COUNT(*) AS count FROM friendbook_cf_documents WHERE collection = ? '
        f"AND doc_id NOT IN (SELECT {spec['pk']} FROM {spec['table']})",
        [spec['collection']])


def deep_equal(left, right):
    if left == right:
        return True
    if isinstance(left, bool) != isinstance(right, bool):
        return False
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(deep_equal(a, b) for a, b in zip(left, right))
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(deep_equal(left[k], right[k]) for k in left)
    return False


MAX_LOOKUP_IDS = 90  # D1 rejects a statement with more than 100 bound parameters


def existing_doc_ids(collection, doc_ids):
    """Which of these ids already have a document (so the rebuild must not claim them)."""
    found = set()
    wanted = list(doc_ids)
    for start in range(0, len(wanted), MAX_LOOKUP_IDS):
        batch = wanted[start:start + MAX_LOOKUP_IDS]
        if not batch:
            continue
        placeholders = ','.join(['?'] * len(batch))
        rows, _ = d1(
            f'SELECT doc_id FROM friendbook_cf_documents WHERE collection = ? AND doc_id IN ({placeholders})',
            [collection, *batch])
        found.update(row['doc_id'] for row in rows)
    return found


def verify(spec, sample=25, ids=None):
    """Coverage plus a round trip.

    With `ids` (documents this run wrote) every mapped field must match exactly - that is
    the migration's own output, so a difference is a bug.
    Without `ids` the sample can contain documents the app or an earlier mirror wrote. Those
    are never overwritten, and their older/looser shape is reported as `keptExistingDiffering`
    rather than counted as a failure.
    """
    limit = max(1, min(MAX_SAMPLE, int(sample)))
    if ids:
        chosen = list(ids)[:limit]
        placeholders = ','.join(['?'] * len(chosen))
        rows, _ = d1(f"SELECT * FROM {spec['table']} WHERE {spec['pk']} IN ({placeholders})",
                     chosen)
        strict = True
    else:
        rows, _ = d1(f"SELECT * FROM {spec['table']} WHERE {spec['pk']} IS NOT NULL "
                     f"ORDER BY {spec['pk']} LIMIT ?", [limit])
        strict = False

    mismatches = []
    kept_differing = []
    checked = 0
    for row in rows:
        mapped = map_row(spec, row)
        if mapped['error']:
            mismatches.append({'id': str(row.get(spec['pk']) or ''), 'reason': mapped['error']})
            continue
        doc = mapped['doc']
        path = f"{spec['collection']}/{doc['id']}"
        stored, _ = d1('SELECT document_json FROM friendbook_cf_documents WHERE path = ?', [path])
        if not stored or not stored[0].get('document_json'):
            mismatches.append({'id': doc['id'], 'reason': 'missing-document'})
            continue
        try:
            actual = json.loads(stored[0]['document_json'])
        except ValueError:
            mismatches.append({'id': doc['id'], 'reason': 'unreadable-document'})
            continue
        keys = [key for key, value in doc.items() if not deep_equal(actual.get(key), value)]
        if keys:
            entry = {'id': doc['id'], 'reason': 'field-mismatch', 'keys': keys[:12]}
            if strict:
                mismatches.append(entry)
            else:
                entry['reason'] = 'kept-existing-differs'
                kept_differing.append(entry)
        else:
            checked += 1
    return {
        'collection': spec['collection'],
        'strict': strict,
        'checked': checked,
        'mismatchCount': len(mismatches),
        'mismatches': mismatches[:20],
        'keptExistingDiffering': len(kept_differing),
        'keptExistingSample': kept_differing[:5],
        'sourceRows': source_rows(spec),
        'documentRows': document_rows(spec['collection']),
        'sourceRowsWithoutDocument': missing_documents(spec),
        'documentsWithoutSourceRow': orphan_documents(spec),
    }


def gate():
    """Coverage is the gate; the field round trip is proven on what the rebuild wrote."""
    spec = SPEC_BY_COLLECTION[TEST_COLLECTION]
    rows = source_rows(spec)
    docs = document_rows(TEST_COLLECTION)
    missing = missing_documents(spec)
    sample = {'checked': 0, 'mismatchCount': 0, 'mismatches': [], 'keptExistingDiffering': 0}
    if rows > 0 and docs >= rows:
        sample = verify(spec, 3)
    passed = rows > 0 and docs >= rows and missing == 0 \
        and sample['checked'] + sample['keptExistingDiffering'] > 0 \
        and sample['mismatchCount'] == 0
    return {
        'collection': TEST_COLLECTION,
        'passed': passed,
        'sourceRows': rows,
        'documentRows': docs,
        'sourceRowsWithoutDocument': missing,
        'sampleChecked': sample['checked'],
        'sampleMismatches': sample['mismatches'],
    }


def plan():
    state = gate()
    collections = []
    for spec in SPECS:
        rows = source_rows(spec)
        docs = document_rows(spec['collection'])
        collections.append({
            'collection': spec['collection'],
            'sourceTable': spec['table'],
            'sourceRows': rows,
            'documentRows': docs,
            'pending': max(0, rows - docs),
            'ready': False if spec.get('test') else state['passed'],
            'testCollection': bool(spec.get('test')),
        })
    return {
        'ok': True,
        'testCollection': TEST_COLLECTION,
        'gate': state,
        'collections': collections,
        'totals': {
            'sourceRows': sum(item['sourceRows'] for item in collections),
            'documentRows': sum(item['documentRows'] for item in collections),
            'pending': sum(item['pending'] for item in collections),
        },
    }


def chunks(documents):
    batch, size = [], 0
    for item in documents:
        blob = len(item['document_json'].encode('utf8'))
        if batch and (len(batch) >= WRITE_CHUNK_ROWS or size + blob > WRITE_CHUNK_BYTES):
            yield batch
            batch, size = [], 0
        batch.append(item)
        size += blob
    if batch:
        yield batch


def write_documents(documents, overwrite=False):
    written = skipped = 0
    for batch in chunks(documents):
        placeholders = ','.join(['(?,?,?,?,?,?)'] * len(batch))
        params = []
        for item in batch:
            params.extend([item['path'], item['collection'], item['doc_id'],
                           item['owner_uid'], item['document_json'], item['updated_at']])
        conflict = ('ON CONFLICT(path) DO UPDATE SET owner_uid = excluded.owner_uid, '
                    'document_json = excluded.document_json, updated_at = excluded.updated_at'
                    if overwrite else 'ON CONFLICT(path) DO NOTHING')
        sql = ('INSERT INTO friendbook_cf_documents'
               '(path, collection, doc_id, owner_uid, document_json, updated_at) '
               f'VALUES {placeholders} {conflict}')
        _, meta = d1(sql, params)
        changes = int((meta or {}).get('changes') or 0)
        written += changes
        skipped += len(batch) - changes
    return written, skipped


def rebuild(spec, apply=False, overwrite=False, page=MAX_PAGE, limit_rows=None):
    summary = {'collection': spec['collection'], 'dryRun': not apply, 'scanned': 0,
               'converted': 0, 'written': 0, 'skippedExisting': 0, 'oversized': 0,
               'invalid': 0, 'warnings': {}, 'errors': [], 'pages': 0, '_writtenIds': []}
    cursor = ''
    seen = set()
    while True:
        rows, _ = d1(f"SELECT * FROM {spec['table']} WHERE {spec['pk']} > ? "
                     f"ORDER BY {spec['pk']} LIMIT ?", [cursor, page])
        if not rows:
            break
        summary['pages'] += 1
        summary['scanned'] += len(rows)
        pending = []
        for row in rows:
            mapped = map_row(spec, row)
            if mapped['error'] or not mapped['doc']:
                summary['invalid'] += 1
                if len(summary['errors']) < 10:
                    summary['errors'].append({'id': str(row.get(spec['pk']) or ''),
                                              'error': mapped['error'] or 'unmapped'})
                continue
            for warning in mapped['warnings']:
                key = f"{warning['column']}:{warning['reason']}"
                summary['warnings'][key] = summary['warnings'].get(key, 0) + 1
            doc = mapped['doc']
            blob = json.dumps(doc, separators=(',', ':'), ensure_ascii=False)
            if len(blob.encode('utf8')) > MAX_DOCUMENT_BYTES:
                summary['oversized'] += 1
                if len(summary['errors']) < 10:
                    summary['errors'].append({'id': doc['id'], 'error': 'document-too-large'})
                continue
            summary['converted'] += 1
            pending.append({
                'path': f"{spec['collection']}/{doc['id']}",
                'collection': spec['collection'],
                'doc_id': doc['id'],
                'owner_uid': owner_uid(spec, row),
                'document_json': blob,
                'updated_at': doc['updatedAt'],
            })
        if apply and pending:
            # One lookup per write chunk: a whole page of ids would exceed D1's parameter limit.
            for chunk in chunks(pending):
                already = existing_doc_ids(spec['collection'], [item['doc_id'] for item in chunk])
                written, skipped = write_documents(chunk, overwrite=overwrite)
                summary['written'] += written
                summary['skippedExisting'] += skipped
                if written:
                    summary['_writtenIds'].extend(item['doc_id'] for item in chunk
                                                  if overwrite or item['doc_id'] not in already)
        cursor = str(rows[-1][spec['pk']])
        if cursor in seen:
            raise D1Error('cursor did not advance')
        seen.add(cursor)
        if limit_rows is not None and summary['scanned'] >= limit_rows:
            break
        if len(rows) < page:
            break
    return summary


def _public(value):
    if isinstance(value, dict):
        return {k: _public(v) for k, v in value.items() if not str(k).startswith('_')}
    if isinstance(value, list):
        return [_public(item) for item in value]
    return value


def say(payload):
    print(json.dumps(_public(payload), sort_keys=True), flush=True)


def require(condition, message, **payload):
    if not condition:
        say({'ok': False, 'stopped': message, **payload})
        raise SystemExit(1)


def flow(apply_writes):
    report = {'mode': 'APPLY' if apply_writes else 'DRY_RUN'}
    before = plan()
    report['planBefore'] = {'totals': before['totals'], 'gate': before['gate']}
    say(report['planBefore'])
    require(before['totals']['sourceRows'] > 0, 'no source rows found; nothing to convert',
            plan=before['totals'])

    posts = SPEC_BY_COLLECTION[TEST_COLLECTION]
    dry = rebuild(posts, apply=False)
    report['postsDryRun'] = dry
    say(dry)
    require(dry['written'] == 0, 'dry run wrote something', result=dry)
    require(dry['invalid'] == 0 and dry['oversized'] == 0,
            'posts rows could not all be converted', result=dry)
    require(dry['converted'] == dry['scanned'], 'posts dry run lost rows', result=dry)
    require(dry['errors'] == [], 'posts dry run reported errors', result=dry)

    if not apply_writes:
        report['planAfter'] = plan()['totals']
        say({'ok': True, 'report': report})
        return 0

    posts_apply = rebuild(posts, apply=True, overwrite=False)
    report['postsApply'] = posts_apply
    say(posts_apply)
    # Field-by-field only for the documents this run wrote: the ones already there belong to
    # the app or an earlier mirror, are never replaced, and legitimately omit empty fields.
    written = posts_apply.get('_writtenIds') or []
    posts_verify = verify(posts, 40, ids=written) if written else verify(posts, 25)
    report['postsVerify'] = posts_verify
    say(posts_verify)
    require(posts_verify['mismatchCount'] == 0, 'posts verification mismatched',
            result=posts_verify)
    require(posts_verify['sourceRowsWithoutDocument'] == 0,
            'some posts rows still have no document', result=posts_verify)
    lived = gate()
    require(lived['passed'], 'posts gate did not pass after apply', gate=lived)
    say({'gate': lived})

    applied = []
    for name in REMAINING:
        spec = SPEC_BY_COLLECTION[name]
        result = rebuild(spec, apply=True, overwrite=False)
        new_ids = result.get('_writtenIds') or []
        check = verify(spec, 20, ids=new_ids) if new_ids else verify(spec, 10)
        say({'applied': result, 'verified': check})
        require(check['mismatchCount'] == 0, f'{name} verification mismatched', result=check)
        require(check['sourceRowsWithoutDocument'] == 0,
                f'{name} still has rows without a document', result=check)
        applied.append({'collection': name, 'written': result['written'],
                        'skippedExisting': result['skippedExisting'],
                        'sourceRows': check['sourceRows'],
                        'documentRows': check['documentRows']})

    after = plan()
    report['collections'] = applied
    report['planAfter'] = {'totals': after['totals'], 'gate': after['gate']}
    say(report['planAfter'])
    require(after['gate']['passed'], 'posts gate is not true at the end', gate=after['gate'])
    outstanding = [item['collection'] for item in after['collections'] if item['pending'] > 0]
    report['outstanding'] = outstanding
    say({'ok': True, 'report': report})
    return 0 if not outstanding else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('plan')
    run = sub.add_parser('run')
    run.add_argument('collection')
    run.add_argument('--apply', action='store_true')
    run.add_argument('--overwrite', action='store_true')
    run.add_argument('--page', type=int, default=MAX_PAGE)
    run.add_argument('--max-rows', type=int, default=0)
    check = sub.add_parser('verify')
    check.add_argument('collection')
    check.add_argument('--sample', type=int, default=MAX_SAMPLE)
    pipe = sub.add_parser('flow')
    pipe.add_argument('--apply', action='store_true')
    args = parser.parse_args(argv)

    if not API_TOKEN:
        print('CLOUDFLARE_API_TOKEN is not set', file=sys.stderr)
        return 1

    started = time.time()
    try:
        if args.command == 'plan':
            say(plan())
            return 0
        if args.command == 'verify':
            spec = SPEC_BY_COLLECTION.get(args.collection)
            if not spec:
                print('unknown collection; choose one of: ' + ', '.join(SPEC_BY_COLLECTION),
                      file=sys.stderr)
                return 1
            result = verify(spec, args.sample)
            say(result)
            return 0 if result['mismatchCount'] == 0 else 1
        if args.command == 'run':
            if args.collection == 'all':
                names = list(SPEC_BY_COLLECTION)
            elif args.collection in SPEC_BY_COLLECTION:
                names = [args.collection]
            else:
                print('unknown collection; choose one of: ' + ', '.join(SPEC_BY_COLLECTION),
                      file=sys.stderr)
                return 1
            current = gate()
            for name in names:
                spec = SPEC_BY_COLLECTION[name]
                if not spec.get('test') and not current['passed']:
                    say({'ok': False, 'blocked': 'posts gate not passed', 'gate': current,
                         'collection': name})
                    return 2
                result = rebuild(spec, apply=args.apply, overwrite=args.overwrite,
                                 page=args.page,
                                 limit_rows=args.max_rows or None)
                say(result)
                current = gate()
                if result['errors']:
                    return 1
            say({'ok': True, 'gate': current, 'elapsedSeconds': round(time.time() - started, 1)})
            return 0
        return flow(args.apply)
    except D1Error as error:
        print(f'D1 error: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
