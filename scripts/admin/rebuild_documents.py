#!/usr/bin/env python3
"""Drive the Worker-side D1 -> document-store rebuild (posts first, then the rest).

Runs from an authorized machine or GitHub Actions. It never touches Firebase, never
prints the admin token and defaults to a dry run:

    export CF_WORKERS_API_BASE="https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev"
    export MIGRATION_ADMIN_TOKEN="<worker secret>"

    python3 scripts/admin/rebuild_documents.py plan
    python3 scripts/admin/rebuild_documents.py run posts            # dry run
    python3 scripts/admin/rebuild_documents.py run posts --apply    # writes
    python3 scripts/admin/rebuild_documents.py verify posts --sample 40
    python3 scripts/admin/rebuild_documents.py run all --apply      # gate enforced

Routes live under the existing migration API (/v1/admin/migrate/documents/*), so the same
MIGRATION_ADMIN_TOKEN (at least 32 characters) that the other migration routes use applies.

Exit codes: 0 success, 1 failure, 2 posts gate blocked.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

COLLECTIONS = [
    'posts', 'users', 'comments', 'reels', 'stories', 'chats', 'messages', 'groups',
    'friend_requests', 'notifications',
]
TEST_COLLECTION = 'posts'


def normalise_base(value):
    """Accept a Worker root URL or the migration-API URL used by the other scripts.

    CF_API_BASE in this repo sometimes already ends with /v1/admin/migrate (see
    plan_missing_documents.py). Both forms must work, without duplicating the prefix.
    """
    base = (value or '').strip().rstrip('/')
    suffix = '/v1/admin/migrate'
    if base.endswith(suffix):
        base = base[:-len(suffix)]
    return base


class RebuildError(Exception):
    pass


class GateBlocked(RebuildError):
    pass


def http_call(base, token, path, method='GET', payload=None, timeout=90):
    data = json.dumps(payload).encode('utf8') if payload is not None else None
    headers = {'Authorization': f'Bearer {token}', 'Accept': 'application/json'}
    if data is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read() or b'{}')
    except urllib.error.HTTPError as error:
        try:
            body = json.loads(error.read() or b'{}')
        except ValueError:
            body = {}
        return error.code, body


def checked(call, path, method='GET', payload=None):
    status, body = call(path, method, payload)
    if status == 409:
        raise GateBlocked(json.dumps(body.get('gate') or {}, sort_keys=True))
    if status != 200 or not isinstance(body, dict):
        raise RebuildError(f'{method} {path} failed with status {status}')
    return body


def rebuild_collection(call, collection, apply=False, overwrite=False, limit=None,
                       max_pages=500, log=print):
    """Page through one collection with the keyset cursor until it is exhausted."""
    if collection not in COLLECTIONS:
        raise RebuildError(f'unknown collection {collection}')
    totals = {'collection': collection, 'pages': 0, 'scanned': 0, 'converted': 0,
              'written': 0, 'skippedExisting': 0, 'oversized': 0, 'invalid': 0}
    cursor = ''
    seen = set()
    while True:
        payload = {'collection': collection, 'apply': bool(apply),
                   'overwrite': bool(overwrite), 'after': cursor}
        if limit:
            payload['limit'] = int(limit)
        body = checked(call, '/v1/admin/migrate/documents/rebuild', 'POST', payload)
        totals['pages'] += 1
        for key in ('scanned', 'converted', 'written', 'skippedExisting', 'oversized', 'invalid'):
            totals[key] += int(body.get(key) or 0)
        log(json.dumps({k: body.get(k) for k in
                        ('collection', 'dryRun', 'scanned', 'converted', 'written',
                         'skippedExisting', 'oversized', 'invalid', 'nextCursor')}, sort_keys=True))
        next_cursor = body.get('nextCursor')
        if not next_cursor:
            break
        if next_cursor in seen or next_cursor == cursor:
            raise RebuildError('cursor did not advance; refusing to loop')
        seen.add(next_cursor)
        cursor = next_cursor
        if totals['pages'] >= max_pages:
            raise RebuildError('page limit reached; run the command again to continue')
    totals['dryRun'] = not apply
    return totals


def run(args, call, log=print):
    if args.command == 'plan':
        body = checked(call, '/v1/admin/migrate/documents/plan')
        log(json.dumps(body, indent=2, sort_keys=True))
        return 0

    if args.command == 'verify':
        body = checked(call, f'/v1/admin/migrate/documents/verify?collection={args.collection}'
                             f'&sample={args.sample}')
        log(json.dumps(body, indent=2, sort_keys=True))
        return 0 if body.get('mismatchCount') == 0 else 1

    targets = COLLECTIONS if args.collection == 'all' else [args.collection]
    results = []
    for collection in targets:
        try:
            results.append(rebuild_collection(call, collection, apply=args.apply,
                                              overwrite=args.overwrite, limit=args.limit,
                                              max_pages=args.max_pages, log=log))
        except GateBlocked as error:
            # A blocked gate is an error, not progress: print it on stderr too.
            print(json.dumps({'collection': collection, 'blocked': 'posts gate not passed',
                              'gate': json.loads(str(error))}, sort_keys=True), file=sys.stderr)
            return 2
    log(json.dumps({'mode': 'APPLY' if args.apply else 'DRY_RUN', 'results': results},
                   indent=2, sort_keys=True))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('plan')
    verify = sub.add_parser('verify')
    verify.add_argument('collection')
    verify.add_argument('--sample', type=int, default=40)
    run_parser = sub.add_parser('run')
    run_parser.add_argument('collection', choices=COLLECTIONS + ['all'])
    run_parser.add_argument('--apply', action='store_true', help='write documents')
    run_parser.add_argument('--overwrite', action='store_true',
                            help='replace existing documents (default: keep them)')
    run_parser.add_argument('--limit', type=int, default=0, help='rows per request (default: server)')
    run_parser.add_argument('--max-pages', type=int, default=500)
    args = parser.parse_args(argv)

    base = normalise_base(os.environ.get('CF_WORKERS_API_BASE') or os.environ.get('CF_API_BASE'))
    token = os.environ.get('MIGRATION_ADMIN_TOKEN') or ''
    local = base.startswith('http://127.0.0.1') or base.startswith('http://localhost')
    if not base.startswith('https://') and not local:
        print('CF_WORKERS_API_BASE must be an https Worker URL', file=sys.stderr)
        return 1
    if len(token) < 32:
        print('MIGRATION_ADMIN_TOKEN is missing or shorter than 32 characters; '
              'the worker refuses every migration route without it', file=sys.stderr)
        return 1

    def call(path, method='GET', payload=None):
        return http_call(base, token, path, method, payload)

    try:
        return run(args, call)
    except GateBlocked as error:
        print(json.dumps({'blocked': 'posts gate not passed', 'gate': json.loads(str(error))},
                         sort_keys=True), file=sys.stderr)
        return 2
    except RebuildError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (urllib.error.URLError, TimeoutError, ValueError):
        print('Worker request failed; check the URL, token and network', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
