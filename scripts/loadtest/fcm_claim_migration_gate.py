#!/usr/bin/env python3
"""Validate a tightly scoped FCM migration rollout, without querying user rows."""
import json
import re
import sys

NAME = '0085_fcm_claims_incremental.sql'
OLD = 'friendbook_cf_fcm_device_claims'
NEW = 'friendbook_cf_fcm_device_claims_v2'
BRIDGE = 'cf_fcm_claims_legacy_bridge'


def pending_safe(text):
    names = set(re.findall(r'\b\d{4}_[a-zA-Z0-9_]+\.sql\b', text))
    if names:
        return names == {NAME}
    return bool(re.search(r'\bno migrations? to apply\b', text, re.I))


def compact(sql):
    return re.sub(r'\s+', '', sql.lower()).replace('ifnotexists', '').rstrip(';')


def schema_safe(payload):
    if not isinstance(payload, list) or len(payload) != 1 or payload[0].get('success') is not True:
        return False
    rows = payload[0].get('results')
    if not isinstance(rows, list) or len(rows) != 3:
        return False
    by_name = {row.get('name'): row for row in rows if isinstance(row, dict)}
    if set(by_name) != {OLD, NEW, BRIDGE}:
        return False
    if by_name[OLD].get('type') != 'table' or by_name[NEW].get('type') != 'table' or by_name[BRIDGE].get('type') != 'trigger':
        return False
    if not all(isinstance(by_name[n].get('sql'), str) for n in (OLD, NEW, BRIDGE)):
        return False
    old = compact(by_name[OLD]['sql'])
    if 'event_keytextprimarykeynotnull' not in old or 'created_atintegernotnull' not in old:
        return False
    new = compact(by_name[NEW]['sql'])
    trigger = compact(by_name[BRIDGE]['sql'])
    expected_new = compact('CREATE TABLE friendbook_cf_fcm_device_claims_v2 (event_key TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL) WITHOUT ROWID')
    expected_trigger = compact('CREATE TRIGGER cf_fcm_claims_legacy_bridge BEFORE INSERT ON friendbook_cf_fcm_device_claims WHEN EXISTS (SELECT 1 FROM friendbook_cf_fcm_device_claims_v2 WHERE event_key=NEW.event_key) BEGIN SELECT RAISE(IGNORE); END')
    return new == expected_new and trigger == expected_trigger


def main():
    try:
        mode, filename = sys.argv[1:]
        with open(filename, encoding='utf-8') as stream:
            value = stream.read()
        safe = pending_safe(value) if mode == 'pending' else schema_safe(json.loads(value)) if mode == 'schema' else False
    except (ValueError, OSError, TypeError, KeyError):
        safe = False
    if not safe:
        raise SystemExit('FCM bridge rollout check failed; no Worker deployment.')
    print('FCM bridge rollout check passed (schema metadata only).')


if __name__ == '__main__':
    main()
