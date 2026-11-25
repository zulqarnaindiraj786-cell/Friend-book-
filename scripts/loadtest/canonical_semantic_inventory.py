#!/usr/bin/env python3
"""Read-only semantic parity check between legacy mirror and canonical documents.

Reads bounded keyset pages; never prints, stores, or uploads document paths or
content. Only per-collection match/mismatch counts and D1 read metadata escape.
"""
import json
from datetime import datetime, timezone
from scripts.loadtest.retired_table_preflight import remote_query

PAGE = 250
KNOWN_FIELDS = frozenset({'id','uid','fromUid','toUid','status','participants','friendsCount','followersCount',
    'followingCount','isRead','createdAt','updatedAt','createTime','updateTime','name','username','displayName',
    'photoUrl','avatarUrl','profileImage','email','phone','text','caption','visibility','ownerUid','postId',
    'chatId','groupId','type','title','body','lastMessage','lastMessageAt','blocked','blockedUsers'})


def _timestamp(value):
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp() * 1000)
    except (ValueError, OverflowError):
        return None


def decode_value(value):
    if isinstance(value, list):
        return [decode_value(item) for item in value]
    if not isinstance(value, dict):
        return value
    if value.get('__type') == 'timestamp' and isinstance(value.get('millis'), (int, float)):
        return int(value['millis'])
    if value.get('__type') == 'reference' and isinstance(value.get('path'), str):
        return value['path']
    if 'stringValue' in value:
        return str(value['stringValue'])
    if 'booleanValue' in value:
        return bool(value['booleanValue'])
    if 'integerValue' in value:
        try: return int(value['integerValue'])
        except (ValueError, TypeError): return None
    if 'doubleValue' in value:
        try: return float(value['doubleValue'])
        except (ValueError, TypeError): return None
    if 'timestampValue' in value:
        return _timestamp(value['timestampValue'])
    if 'nullValue' in value:
        return None
    if 'arrayValue' in value:
        vals = value.get('arrayValue', {}).get('values', [])
        return [decode_value(item) for item in vals] if isinstance(vals, list) else []
    if 'mapValue' in value:
        fields = value.get('mapValue', {}).get('fields', {})
        return decode_fields(fields)
    return {key: decode_value(item) for key, item in value.items()}


def decode_fields(fields):
    if not isinstance(fields, dict):
        return {}
    return {key: decode_value(value) for key, value in fields.items()}


def plain_document(raw, doc_id):
    parsed = json.loads(raw) if isinstance(raw, str) else raw
    if not isinstance(parsed, dict):
        raise ValueError('not an object')
    if isinstance(parsed.get('fields'), dict):
        plain = decode_fields(parsed['fields'])
    else:
        plain = {key: decode_value(value) for key, value in parsed.items() if key not in ('createTime', 'updateTime')}
    if not plain.get('id') and doc_id:
        plain['id'] = doc_id
    if not plain.get('createdAt') and isinstance(parsed.get('createTime'), str):
        created = _timestamp(parsed['createTime'])
        if created is not None:
            plain['createdAt'] = created
    return plain


def inspect(query, page_size=PAGE):
    if type(page_size) is not int or not 1 <= page_size <= 1000:
        raise ValueError('Invalid page size')
    cursor = ''
    totals = {}
    rows_read = 0
    while True:
        rows, meta = query('''SELECT m.path AS path,m.collection AS collection,m.doc_id AS mirror_id,
            m.owner_uid AS mirror_owner,m.document_json AS mirror_json,c.doc_id AS canonical_id,
            c.owner_uid AS canonical_owner,c.document_json AS canonical_json,c.create_time AS create_time
          FROM friendbook_cf_documents m LEFT JOIN friendbook_cf_firestore_documents c ON c.path=m.path
          WHERE m.path>? ORDER BY m.path LIMIT ?''', (cursor, page_size))
        read = meta.get('rows_read')
        if type(read) is not int or read < 0:
            raise ValueError('D1 read accounting unavailable')
        rows_read += read
        if not rows:
            break
        for row in rows:
            path = row.get('path')
            if not isinstance(path, str) or path <= cursor:
                raise ValueError('Invalid inventory cursor')
            cursor = path
            collection = row.get('collection') if isinstance(row.get('collection'), str) else 'other'
            item = totals.setdefault(collection, {'sampled': 0, 'canonical_missing': 0,
                'semantic_match': 0, 'semantic_mismatch': 0, 'owner_match': 0, 'owner_mismatch': 0,
                'owner_mismatch_canonical_empty': 0, 'owner_mismatch_mirror_empty': 0,
                'owner_mismatch_canonical_is_doc_id': 0, 'owner_mismatch_mirror_is_doc_id': 0,
                'mismatch_fields': {}, 'malformed': 0})
            item['sampled'] += 1
            if row.get('canonical_json') is None:
                item['canonical_missing'] += 1
                continue
            mirror_owner, canonical_owner = row.get('mirror_owner'), row.get('canonical_owner')
            doc_id = row.get('canonical_id') or row.get('mirror_id')
            if mirror_owner == canonical_owner:
                item['owner_match'] += 1
            else:
                item['owner_mismatch'] += 1
                if not canonical_owner: item['owner_mismatch_canonical_empty'] += 1
                if not mirror_owner: item['owner_mismatch_mirror_empty'] += 1
                if canonical_owner == doc_id: item['owner_mismatch_canonical_is_doc_id'] += 1
                if mirror_owner == doc_id: item['owner_mismatch_mirror_is_doc_id'] += 1
            try:
                mid = row.get('canonical_id') or row.get('mirror_id')
                mirror = plain_document(row.get('mirror_json'), row.get('mirror_id') or mid)
                canonical = plain_document(row.get('canonical_json'), mid)
            except (ValueError, TypeError, json.JSONDecodeError):
                item['malformed'] += 1
                continue
            if mirror == canonical:
                item['semantic_match'] += 1
            else:
                item['semantic_mismatch'] += 1
                keys = set(mirror) | set(canonical)
                for key in keys:
                    if mirror.get(key) != canonical.get(key) or (key in mirror) != (key in canonical):
                        label = key if key in KNOWN_FIELDS else '_other'
                        item['mismatch_fields'][label] = item['mismatch_fields'].get(label, 0) + 1
        if len(rows) < page_size:
            break
    return {'collections': totals, 'sampled': sum(x['sampled'] for x in totals.values()),
            'rows_read': rows_read, 'truncated': False, 'page_size': page_size}


def main():
    result = inspect(remote_query)
    collections = {name: row for name, row in result['collections'].items()
        if row['canonical_missing'] or row['semantic_mismatch'] or row['owner_mismatch'] or row['malformed']}
    summary = {key: result[key] for key in ('sampled', 'rows_read', 'truncated', 'page_size')}
    summary['totals'] = {key: sum(row[key] for row in result['collections'].values())
        for key in ('canonical_missing', 'semantic_match', 'semantic_mismatch', 'owner_match', 'owner_mismatch', 'malformed')}
    summary['mismatch_collections'] = collections
    print('::notice:: Privacy-preserving canonical semantic inventory ' + json.dumps(summary, sort_keys=True))


if __name__ == '__main__':
    main()
