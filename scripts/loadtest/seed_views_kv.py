#!/usr/bin/env python3
"""One-time, fail-closed transfer of legacy view totals from D1 to the live KV snapshot.

Called only before Worker deployment. Never overwrites a KV snapshot already receiving live views.
The Worker request path itself does not read D1 for view totals.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ACCOUNT = '92f3d04598822de2e49e14561218d090'
DATABASE = 'e1c605ca-a50b-46c6-9deb-56dd1f70d163'
KEY = 'views:legacy-seed:v1'
MAX_RESOURCES = 1000
CONFIG = Path(__file__).resolve().parents[2] / 'wrangler.toml'


def _request(url, token, method='GET', data=None):
    req = urllib.request.Request(url, data=data,
        headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
        method=method)
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def load_namespace(config_text):
    match = re.search(r'(?ms)^\[\[kv_namespaces\]\]\s*\n\s*binding\s*=\s*"VIEWS"\s*\n\s*id\s*=\s*"([a-zA-Z0-9_-]+)"', config_text)
    if not match:
        raise RuntimeError('VIEWS KV binding was not provisioned; refusing deployment')
    return match.group(1)


def validate_rows(rows):
    if not isinstance(rows, list) or len(rows) > MAX_RESOURCES:
        raise RuntimeError('Legacy view inventory exceeds the safe KV snapshot capacity')
    result = {}
    for row in rows:
        if not isinstance(row, dict):
            raise RuntimeError('Malformed D1 view result')
        resource, views = row.get('resource'), row.get('views')
        if (not isinstance(resource, str) or len(resource) > 128
                or not re.fullmatch(r'(?:posts|reels)/[^/,\x00-\x20]+', resource)
                or type(views) not in (int, float) or views < 0):
            raise RuntimeError('Invalid D1 view row; refusing deployment')
        if resource in result:
            raise RuntimeError('Duplicate D1 view resource; refusing deployment')
        result[resource] = int(views)
    return result


def seed(token, namespace, request=_request):
    base = f'https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/storage/kv/namespaces/{namespace}/values/'
    value_url = base + urllib.parse.quote(KEY, safe='')
    status, body = request(value_url, token)
    if status == 200:
        # Never replace the current snapshot: it may already contain post-cutover views.
        try:
            current = json.loads(body)
            if not isinstance(current, dict):
                raise ValueError
            validate_rows([{'resource': key, 'views': value} for key, value in current.items()])
        except (ValueError, TypeError, RuntimeError):
            raise RuntimeError('Existing immutable view seed is invalid; refusing deployment') from None
        print('Immutable legacy view seed already exists; keeping it unchanged.')
        return 'preserved'
    if status != 404:
        raise RuntimeError(f'Unable to inspect live views KV snapshot (HTTP {status})')

    query_url = f'https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/d1/database/{DATABASE}/query'
    payload = json.dumps({'sql': f'SELECT resource, views FROM friendbook_cf_view_counters ORDER BY views DESC LIMIT {MAX_RESOURCES + 1}', 'params': []}).encode()
    status, body = request(query_url, token, method='POST', data=payload)
    try:
        response = json.loads(body)
        result = response['result'][0]
        rows = result['results']
    except (ValueError, KeyError, IndexError, TypeError):
        raise RuntimeError('D1 view inventory response was incomplete') from None
    meta = result.get('meta')
    if (status != 200 or not response.get('success') or not result.get('success')
            or not isinstance(meta, dict) or type(meta.get('rows_read')) is not int
            or meta['rows_read'] < 0):
        raise RuntimeError(f'D1 view inventory query failed or lacks read accounting (HTTP {status})')
    totals = validate_rows(rows)
    encoded = json.dumps(totals, separators=(',', ':')).encode()
    status, body = request(value_url, token, method='PUT', data=encoded)
    try:
        response = json.loads(body)
    except (ValueError, TypeError):
        response = {}
    if status not in (200, 201) or not response.get('success'):
        raise RuntimeError(f'Unable to write legacy view snapshot to KV (HTTP {status})')
    verify_status, verify_body = request(value_url, token)
    try:
        verified = json.loads(verify_body)
    except (ValueError, TypeError):
        verified = None
    if verify_status != 200 or verified != totals:
        raise RuntimeError('Legacy view snapshot did not verify after KV write; refusing deployment')
    print(f'Seeded and verified Workers KV with {len(totals)} aggregate view totals; D1 used only before deploy.')
    return 'seeded'


def main():
    token = os.environ.get('CLOUDFLARE_API_TOKEN')
    if not token:
        raise RuntimeError('Cloudflare API token unavailable; refusing deployment')
    namespace = load_namespace(CONFIG.read_text())
    seed(token, namespace)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'::error::Views snapshot preflight failed: {error}', file=sys.stderr)
        raise SystemExit(1)
