#!/usr/bin/env python3
"""Metadata-only guard for a code-only Worker rollout. Never prints secrets."""
import argparse
import json
import os
from pathlib import Path
import tomllib
import urllib.request

ACCOUNT = '92f3d04598822de2e49e14561218d090'
SCRIPT = 'rough-firefly-dd7b'


def api(path):
    request = urllib.request.Request(
        f'https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/workers/scripts/{SCRIPT}/{path}',
        headers={'Authorization': 'Bearer ' + os.environ['CLOUDFLARE_API_TOKEN'],
                 'User-Agent': 'FriendBook-Code-Rollout/1.0'})
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    if payload.get('success') is not True:
        raise ValueError('Worker metadata unavailable')
    return payload['result']


def binding_identity(binding):
    # Resource IDs and public binding names only, never secret/plaintext values.
    return {key: binding[key] for key in ('name', 'type', 'id', 'namespace_id',
            'bucket_name', 'dataset', 'class_name', 'script_name', 'environment') if key in binding}


def validate(settings, schedules, config):
    bindings = {row['name']: row for row in settings['bindings']}
    expected = {'DB': 'd1', 'ASSETS': 'assets', 'ANALYTICS_ENGINE': 'analytics_engine',
                'VIDEO_BUCKET': 'r2_bucket', 'VIEWS': 'kv_namespace', 'LIVE_RELAY': 'durable_object_namespace',
                'CALL_RELAY': 'durable_object_namespace'}
    # Refuse to replace a deployment with extra resources absent from local config.
    resources = {name: row['type'] for name, row in bindings.items()
                 if row['type'] not in ('plain_text', 'secret_text', 'json')}
    if resources != expected:
        actual = json.dumps(sorted((binding_identity(row) for row in bindings.values()
                                    if row['type'] not in ('plain_text', 'secret_text', 'json')),
                                   key=lambda row: row['name']), sort_keys=True)
        required = json.dumps(dict(sorted(expected.items())), sort_keys=True)
        raise ValueError('Live resource bindings differ from reviewed configuration; expected=' + required + '; actual=' + actual)
    if bindings['DB'].get('id') != config['d1_databases'][0]['database_id']:
        raise ValueError('D1 binding mismatch')
    if bindings['VIDEO_BUCKET'].get('bucket_name') != 'friendbook':
        raise ValueError('R2 binding mismatch')
    if bindings['ANALYTICS_ENGINE'].get('dataset') != 'friendbook_views':
        raise ValueError('Analytics binding mismatch')
    if bindings['VIEWS'].get('namespace_id') != config['kv_namespaces'][0]['id']:
        raise ValueError('Workers KV binding mismatch')
    for name, cls in [('LIVE_RELAY', 'LiveRelay'), ('CALL_RELAY', 'CallRelay')]:
        if bindings[name].get('class_name') != cls or bindings[name].get('script_name'):
            raise ValueError('Durable Object binding mismatch')
    for name, value in config['vars'].items():
        live = bindings.get(name, {}).get('text')
        # The Worker intentionally defaults this public advisory to 1; an unset
        # live var is behavior-equivalent and preserves the existing response.
        if name == 'ANDROID_MIN_VERSION_CODE' and live is None and value == '1':
            continue
        if live != value:
            raise ValueError('Live variable differs from reviewed configuration: ' + name)
    if sorted(row['cron'] for row in schedules['schedules']) != ['*/15 * * * *']:
        raise ValueError('Cron mismatch')
    return sorted((binding_identity(row) for row in bindings.values()), key=lambda row: row['name'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path)
    parser.add_argument('--compare', action='store_true')
    parser.add_argument('--inspect', action='store_true', help='Print sanitized live binding identities only')
    args = parser.parse_args()
    config = tomllib.loads(Path('wrangler.toml').read_text())
    settings = api('settings')
    if args.inspect:
        identities = sorted((binding_identity(row) for row in settings['bindings']
                             if row.get('type') not in ('plain_text', 'secret_text', 'json')),
                            key=lambda row: row['name'])
        public = next((row.get('text') for row in settings['bindings']
                       if row.get('name') == 'ANDROID_MIN_VERSION_CODE' and row.get('type') == 'plain_text'), None)
        print('::notice:: Live Worker binding inspection ' + json.dumps({'live_resources': identities,
              'android_min_version_code': public if public is None or str(public).isdigit() else 'non-numeric'}, sort_keys=True))
        return
    if not args.snapshot:
        parser.error('--snapshot is required unless --inspect is used')
    identities = validate(settings, api('schedules'), config)
    if args.compare:
        previous = json.loads(args.snapshot.read_text())['bindings']
        if identities != previous:
            raise ValueError('Post-deploy bindings differ; expected=' + json.dumps(previous, sort_keys=True)
                             + '; actual=' + json.dumps(identities, sort_keys=True))
    else:
        deployments = api('deployments')
        versions = deployments.get('deployments', [])
        if not versions or not versions[0].get('versions'):
            raise ValueError('Rollback version unavailable')
        args.snapshot.write_text(json.dumps({'bindings': identities,
            'rollback_versions': versions[0]['versions']}, indent=2) + '\n')
    print('Reviewed live resources, public flags and */15 cron verified; no D1 SQL executed.')


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        message = str(error)
        safe_prefixes = (
            'Live resource bindings differ', 'D1 binding mismatch', 'R2 binding mismatch',
            'Analytics binding mismatch', 'Durable Object binding mismatch',
            'Live variable differs from reviewed configuration:', 'Cron mismatch',
            'Post-deploy bindings differ', 'Rollback version unavailable',
            'Worker metadata unavailable',
        )
        safe = message if any(message.startswith(prefix) for prefix in safe_prefixes) else 'Worker rollout preflight failed safely'
        print('::error:: ' + safe)
        raise SystemExit(1)
