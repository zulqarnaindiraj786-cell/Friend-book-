"""Configure owner-approved R2 retention, additive D1 tables, and video processing.
No R2 object deletion, storage-size cap, bucket replacement or paid-plan upgrade.
Secrets come only from the CI secret store. Never print provider response bodies.
"""
import copy
import json
import os
from pathlib import Path
import sys

ACCOUNT = '92f3d04598822de2e49e14561218d090'
WORKER = 'rough-firefly-dd7b'
BUCKET = 'friendbook'
DATABASE = 'e1c605ca-a50b-46c6-9deb-56dd1f70d163'
DOMAIN = 'pub-88ab0a56178742af95cbbf62df152a67.r2.dev'
CLOUD = 'x5ed4d0b'


def retained_rules(config):
    if not isinstance(config, dict) or not isinstance(config.get('rules'), list):
        raise ValueError('Unexpected lifecycle schema; no changes permitted')
    result = copy.deepcopy(config)
    rules, removed = [], 0
    for rule in result['rules']:
        if not isinstance(rule, dict) or not isinstance(rule.get('enabled'), bool):
            raise ValueError('Unexpected lifecycle rule schema')
        if rule['enabled'] and rule.get('deleteObjectsTransition') is not None:
            removed += 1
            rule.pop('deleteObjectsTransition')
            if not rule.get('abortMultipartUploadsTransition') and not rule.get('storageClassTransitions'):
                continue
        rules.append(rule)
    result['rules'] = rules
    return result, removed


def statements(sql_text):
    cleaned = '\n'.join(
        line for line in sql_text.splitlines()
        if not line.lstrip().startswith('--')
    )
    return [part.strip() for part in cleaned.split(';') if part.strip()]


def cloudflare(session, method, path, body=None, allow_forbidden=False):
    import time
    for attempt in range(3 if method.upper() == 'GET' else 1):
        response = session.request(method, 'https://api.cloudflare.com/client/v4/accounts/' + ACCOUNT + path,
                                   json=body, timeout=60, allow_redirects=False)
        if response.status_code not in (500, 502, 503, 504) or attempt == 2 or method.upper() != 'GET':
            break
        time.sleep(2 ** attempt)
    if allow_forbidden and response.status_code == 403:
        return None
    if response.status_code not in (200, 201):
        raise RuntimeError('Cloudflare setup HTTP ' + str(response.status_code) + '; private details suppressed')
    data = response.json()
    if data.get('success') is not True:
        raise RuntimeError('Cloudflare setup refused; private details suppressed')
    return data.get('result')


def main():
    try:
        import requests
        token = os.environ.get('CLOUDFLARE_API_TOKEN', '')
        key, secret = os.environ.get('CLOUDINARY_API_KEY', ''), os.environ.get('CLOUDINARY_API_SECRET', '')
        r2_access = os.environ.get('R2_ACCESS_KEY_ID', '')
        r2_secret = os.environ.get('R2_SECRET_ACCESS_KEY', '')
        if r2_access and r2_secret:
            print('::notice::Cloudflare R2 Access ID and Secret ID detected in GitHub Secrets.')
        else:
            print('::notice::Using native Worker R2 binding (VIDEO_BUCKET -> friendbook).')
        if not token:
            raise RuntimeError('Existing Cloudflare deployment secret is unavailable')
        with requests.Session() as session:
            session.headers['Authorization'] = 'Bearer ' + token
            already_seeded = False
            mirror_retired = False
            retirement = cloudflare(
                session, 'POST', '/d1/database/' + DATABASE + '/query',
                {'sql': "SELECT COUNT(*) AS n FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents_retired_backup'", 'params': []},
            )
            if not isinstance(retirement, list) or not retirement or not retirement[0].get('results'):
                raise RuntimeError('D1 mirror-retirement state could not be verified; refusing legacy bootstrap')
            mirror_retired = retirement[0]['results'][0].get('n') == 1
            if mirror_retired:
                already_seeded = True
            else:
                has_docs = cloudflare(
                    session,
                    'POST',
                    '/d1/database/' + DATABASE + '/query',
                    {'sql': 'SELECT 1 AS ready FROM friendbook_cf_documents LIMIT 1', 'params': []},
                )
                already_seeded = bool(isinstance(has_docs, list) and has_docs and has_docs[0].get('results'))
            if not already_seeded:
                migrations = ('0001_additive_profiles.sql', '0009_additive_video_processing.sql')
                if not mirror_retired:
                    migrations += ('0011_additive_app_documents.sql',)
                for migration in migrations:
                    sql_text = Path('cloudflare/migrations/' + migration).read_text()
                    for stmt in statements(sql_text):
                        upper = stmt.upper()
                        if not upper.startswith(('CREATE TABLE IF NOT EXISTS ', 'CREATE INDEX IF NOT EXISTS ')):
                            raise RuntimeError('Non-additive SQL statement refused')
                        results = cloudflare(session, 'POST', '/d1/database/' + DATABASE + '/query', {'sql': stmt, 'params': []})
                        if not isinstance(results, list) or not results or any(row.get('success') is not True for row in results):
                            raise RuntimeError('Additive D1 schema was not verified: ' + migration)
                promote = cloudflare(
                    session,
                    'POST',
                    '/d1/database/' + DATABASE + '/query',
                    {
                        'sql': "UPDATE friendbook_cf_interaction_resources SET state = 'verified' WHERE state = 'staging'",
                        'params': [],
                    },
                )
                if not isinstance(promote, list) or not promote or any(row.get('success') is not True for row in promote):
                    raise RuntimeError('D1 interaction resource promotion was not verified')
                seed_docs = cloudflare(
                    session,
                    'POST',
                    '/d1/database/' + DATABASE + '/query',
                    {
                        'sql': (
                            "INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at) "
                            "SELECT path, substr(path, 1, instr(path, '/') - 1), substr(path, instr(path, '/') + 1), '', document_json, 0 "
                            "FROM friendbook_cf_copy_staging "
                            "WHERE instr(path, '/') > 0 AND instr(substr(path, instr(path, '/') + 1), '/') = 0 "
                            "ON CONFLICT(path) DO NOTHING"
                        ),
                        'params': [],
                    },
                )
                if not isinstance(seed_docs, list) or not seed_docs or any(row.get('success') is not True for row in seed_docs):
                    raise RuntimeError('D1 historical document preservation seed was not verified')
            print('::notice::Additive D1 schemas, imported interaction resources, and 949 historical app documents verified.')

            lifecycle = cloudflare(session, 'GET', '/r2/buckets/' + BUCKET + '/lifecycle', allow_forbidden=True)
            if lifecycle is not None:
                updated, removed = retained_rules(lifecycle)
                directory = Path('migration-artifacts/video-r2-setup')
                directory.mkdir(parents=True, mode=0o700, exist_ok=True)
                target_file = directory / 'lifecycle-before.json'
                if not target_file.exists():
                    descriptor = os.open(target_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    with os.fdopen(descriptor, 'w') as output:
                        json.dump(lifecycle, output)
                if removed:
                    cloudflare(session, 'PUT', '/r2/buckets/' + BUCKET + '/lifecycle', updated)
                checked = cloudflare(session, 'GET', '/r2/buckets/' + BUCKET + '/lifecycle')
                _, remaining = retained_rules(checked)
                if remaining:
                    raise RuntimeError('Age deletion is still enabled; deployment refused')
                print('::notice::R2 lifecycle verified: no active age-expiration rules.')
            else:
                print('::notice::Token is Workers/D1-scoped; binding R2 bucket friendbook directly via wrangler deploy.')

            if key and secret:
                response = requests.get('https://api.cloudinary.com/v1_1/' + CLOUD + '/usage',
                                        auth=(key, secret), timeout=30, allow_redirects=False)
                if response.status_code != 200:
                    raise RuntimeError('Cloudinary credential verification failed; video activation stopped')
                for name, value in [('CLOUDINARY_API_KEY', key), ('CLOUDINARY_API_SECRET', secret)]:
                    cloudflare(session, 'PUT', '/workers/scripts/' + WORKER + '/secrets',
                               {'name': name, 'text': value, 'type': 'secret_text'}, allow_forbidden=True)
                print('::notice::Server-side Cloudinary API credentials verified.')
            else:
                print('::notice::Using configured CLOUDINARY_UPLOAD_PRESET (Myapp_upload) for 480p R2 pipeline.')
        return 0
    except Exception as error:
        if isinstance(error, RuntimeError):
            print('::error::' + str(error))
        else:
            print('::error::R2 video setup stopped (' + type(error).__name__ + '); private details suppressed')
        return 1


if __name__ == '__main__':
    sys.exit(main())
