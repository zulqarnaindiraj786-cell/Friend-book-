"""Configure owner-approved R2 retention and temporary Cloudinary processing.
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
            # Disable just the expiration action. Preserve multipart abort and
            # storage-class actions; omit a rule if no actions remain.
            rule.pop('deleteObjectsTransition')
            if not rule.get('abortMultipartUploadsTransition') and not rule.get('storageClassTransitions'):
                continue
        rules.append(rule)
    result['rules'] = rules
    return result, removed


def cloudflare(session, method, path, body=None):
    response = session.request(method, 'https://api.cloudflare.com/client/v4/accounts/' + ACCOUNT + path,
                               json=body, timeout=60, allow_redirects=False)
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
        if not token:
            raise RuntimeError('Existing Cloudflare deployment secret is unavailable')
        with requests.Session() as session:
            session.headers['Authorization'] = 'Bearer ' + token
            cloudflare(session, 'GET', '/r2/buckets/' + BUCKET)
            lifecycle = cloudflare(session, 'GET', '/r2/buckets/' + BUCKET + '/lifecycle')
            updated, removed = retained_rules(lifecycle)
            # Private recovery copy only; never uploaded as a public artifact.
            directory = Path('migration-artifacts/video-r2-setup')
            directory.mkdir(parents=True, mode=0o700, exist_ok=True)
            descriptor = os.open(directory / 'lifecycle-before.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w') as output:
                json.dump(lifecycle, output)
            if removed:
                cloudflare(session, 'PUT', '/r2/buckets/' + BUCKET + '/lifecycle', updated)
            checked = cloudflare(session, 'GET', '/r2/buckets/' + BUCKET + '/lifecycle')
            _, remaining = retained_rules(checked)
            if remaining:
                raise RuntimeError('Age deletion is still enabled; deployment refused')
            print('R2 age-expiration disabled/absent and verified. No saved R2 objects were deleted.')
            domain = cloudflare(session, 'GET', '/r2/buckets/' + BUCKET + '/domains/managed')
            if not isinstance(domain, dict) or domain.get('domain') != DOMAIN or domain.get('enabled') is not True:
                raise RuntimeError('Provided R2 public URL is not verified active; no public-access change made')
            if not key or not secret:
                raise RuntimeError('R2 retention verified; video activation needs CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET repository secrets. Never paste credentials in chat')
            response = requests.get('https://api.cloudinary.com/v1_1/' + CLOUD + '/usage',
                                    auth=(key, secret), timeout=30, allow_redirects=False)
            if response.status_code != 200:
                raise RuntimeError('Cloudinary credential verification failed; video activation stopped')
            sql = Path('cloudflare/migrations/0009_additive_video_processing.sql').read_text()
            results = cloudflare(session, 'POST', '/d1/database/' + DATABASE + '/query', {'sql': sql, 'params': []})
            if not isinstance(results, list) or not results or any(row.get('success') is not True for row in results):
                raise RuntimeError('Additive video processing schema was not verified')
            for name, value in [('CLOUDINARY_API_KEY', key), ('CLOUDINARY_API_SECRET', secret)]:
                cloudflare(session, 'PUT', '/workers/scripts/' + WORKER + '/secrets',
                           {'name': name, 'text': value, 'type': 'secret_text'})
        print('R2 existing bucket/domain verified; active age-expiration removed; additive processing schema and server-only credentials configured. No R2 objects deleted. Storage billing may grow.')
        return 0
    except Exception as error:
        if isinstance(error, RuntimeError):
            print('::error::' + str(error))
        else:
            print('::error::R2 video setup stopped (' + type(error).__name__ + '); private details suppressed')
        return 1


if __name__ == '__main__':
    sys.exit(main())
