"""Activate an explicitly approved subset of the existing D1 archive.

No Firestore/Firebase reads or writes. Existing live records are never overwritten.
Restores missing user/chat/friend/request roots and chat messages where a live room
exists. Does not create trusted friendship/group/admin ledgers or send pushes.
"""
import hashlib
import json
import math
import os
import re
from collections import Counter
from datetime import datetime
from migrate_firestore_to_d1 import D1, PREFIX, CopyError

PHASES = ('users', 'chats', 'friends', 'friend_requests', 'messages')
SAFE_NUMBER = 9007199254740991
MAX_BYTES = 240_000


def millis(value):
    try:
        dt = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if dt.tzinfo is None: raise ValueError('timezone')
        value = int(dt.timestamp() * 1000)
        if abs(value) > SAFE_NUMBER: raise ValueError('range')
        return value
    except (TypeError, ValueError, OverflowError, AttributeError) as exc:
        raise ValueError('invalid timestamp') from exc


def decode(value):
    if not isinstance(value, dict) or len(value) != 1: raise ValueError('invalid value')
    key, val = next(iter(value.items()))
    if key == 'nullValue': return None
    if key == 'stringValue' and isinstance(val, str): return val
    if key == 'booleanValue' and isinstance(val, bool): return val
    if key in ('integerValue', 'doubleValue'):
        if isinstance(val, bool): raise ValueError('invalid number')
        try: n = int(val) if key == 'integerValue' else float(val)
        except (TypeError, ValueError, OverflowError): raise ValueError('invalid number')
        if not math.isfinite(n) or abs(n) > SAFE_NUMBER: raise ValueError('number range')
        return n
    if key == 'timestampValue': return {'__type': 'timestamp', 'millis': millis(val)}
    if key == 'referenceValue' and isinstance(val, str) and '/documents/' in val:
        return {'__type':'reference','path':val.split('/documents/',1)[1]}
    if key == 'arrayValue' and isinstance(val, dict): return [decode(x) for x in val.get('values', [])]
    if key == 'mapValue' and isinstance(val, dict): return fields(val.get('fields', {}))
    raise ValueError('unsupported Firestore type')


def fields(value):
    if not isinstance(value, dict): raise ValueError('invalid fields')
    return {key:decode(item) for key,item in value.items()}


def record(path, text, digest, phase):
    if hashlib.sha256(text.encode('utf-8')).hexdigest() != digest: raise ValueError('archive hash mismatch')
    parts=path.split('/')
    if any(not p or p in ('.','..') or len(p)>512 or re.search(r'[\\/\x00-\x1f]',p) for p in parts): return None
    if phase == 'messages':
        if len(parts)!=4 or parts[0]!='chats' or parts[2]!='messages': return None
    elif len(parts)!=2 or parts[0]!=phase: return None
    raw=json.loads(text)
    if raw.get('name') != PREFIX+'/'+path: raise ValueError('identity mismatch')
    data=fields(raw.get('fields',{}))
    if phase=='users' and data.get('uid') not in (None,'',parts[1]): raise ValueError('profile identity mismatch')
    if phase=='friend_requests':
        if not all(isinstance(data.get(x),str) and data[x] for x in ('fromUid','toUid')) or data['fromUid']==data['toUid']:
            raise ValueError('invalid request')
    owner=next((data[k] for k in ('authorId','uid','senderId','from','toUserId','toUid','userId','creatorId','hostId') if isinstance(data.get(k),str) and data[k]),'')
    if phase=='users' and not owner: owner=parts[1]
    created=millis(raw['createTime']) if raw.get('createTime') else 0
    updated=millis(raw['updateTime']) if raw.get('updateTime') else created
    return {'path':path,'parent':'/'.join(parts[:-1]),'collection':parts[-2],'id':parts[-1],
            'owner':owner,'data':json.dumps(data,ensure_ascii=False,separators=(',',':'),allow_nan=False),
            'created':created,'updated':updated,'room':'/'.join(parts[:2]) if phase=='messages' else '',
            'from':data.get('fromUid',''),'to':data.get('toUid',''),'status':data.get('status','')}


INSERT = '''INSERT OR IGNORE INTO friendbook_cf_firestore_documents
 (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
 SELECT json_extract(j.value,'$.path'),json_extract(j.value,'$.parent'),json_extract(j.value,'$.collection'),
   json_extract(j.value,'$.id'),json_extract(j.value,'$.owner'),json_extract(j.value,'$.data'),
   json_extract(j.value,'$.created'),json_extract(j.value,'$.updated')
 FROM json_each(?) j
 WHERE NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=json_extract(j.value,'$.path'))
 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_documents a WHERE a.path=json_extract(j.value,'$.path'))
 AND (json_extract(j.value,'$.room')='' OR EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents r WHERE r.path=json_extract(j.value,'$.room'))
      OR EXISTS (SELECT 1 FROM friendbook_cf_documents r WHERE r.path=json_extract(j.value,'$.room')))
 AND (json_extract(j.value,'$.collection')!='friend_requests' OR json_extract(j.value,'$.status')!='pending' OR (
   NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.parent_path='friend_requests'
     AND json_extract(f.document_json,'$.fromUid')=json_extract(j.value,'$.from')
     AND json_extract(f.document_json,'$.toUid')=json_extract(j.value,'$.to') AND json_extract(f.document_json,'$.status')='pending')
   AND NOT EXISTS (SELECT 1 FROM friendbook_cf_documents a WHERE a.collection='friend_requests'
     AND COALESCE(json_extract(a.document_json,'$.fromUid'),json_extract(a.document_json,'$.fields.fromUid.stringValue'))=json_extract(j.value,'$.from')
     AND COALESCE(json_extract(a.document_json,'$.toUid'),json_extract(a.document_json,'$.fields.toUid.stringValue'))=json_extract(j.value,'$.to')
     AND COALESCE(json_extract(a.document_json,'$.status'),json_extract(a.document_json,'$.fields.status.stringValue'))='pending')))
 RETURNING path'''


def activate(db):
    stats=Counter(); prefixes={'messages':'chats/'}
    for phase in PHASES:
        prefix=prefixes.get(phase,phase+'/'); cursor=''
        while True:
            rows=db.query('SELECT path,document_json,sha256 FROM friendbook_cf_fullcopy_archive WHERE path>? AND substr(path,1,?)=? ORDER BY path LIMIT 100',(cursor,len(prefix),prefix))
            if not rows: break
            cursor=rows[-1]['path']; items=[]; seen=set(); size=2
            def flush():
                nonlocal items,seen,size
                if items:
                    result=db.query(INSERT,(json.dumps(items,ensure_ascii=False,separators=(',',':')),))
                    stats[phase+'_inserted']+=len(result);stats[phase+'_not_inserted']+=len(items)-len(result)
                items=[];seen=set();size=2
            for row in rows:
                try: item=record(row['path'],row['document_json'],row['sha256'],phase)
                except (ValueError,TypeError,KeyError,json.JSONDecodeError): stats[phase+'_invalid_or_unsupported']+=1;continue
                if item is None: continue
                encoded=json.dumps(item,ensure_ascii=False,separators=(',',':')).encode('utf8')
                if len(encoded)>MAX_BYTES:stats[phase+'_oversized']+=1;continue
                key=(item['from'],item['to']) if phase=='friend_requests' and item['status']=='pending' else None
                if key is not None and key in seen: stats[phase+'_duplicate_pending']+=1;continue
                if len(items)>=30 or size+len(encoded)>MAX_BYTES:flush()
                if key is not None:seen.add(key)
                items.append(item);size+=len(encoded)+1
            flush()
    return dict(stats)


def main():
    import requests
    token=os.environ.get('CLOUDFLARE_API_TOKEN','')
    if not token: raise CopyError('Cloudflare token unavailable')
    session=requests.Session();session.headers.update({'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    result=activate(D1(session))
    print('::notice::D1_ARCHIVE_ACTIVATION='+json.dumps(result,sort_keys=True))
    print('No Firebase requests. Existing live documents and trusted role ledgers were not overwritten.')

if __name__=='__main__': main()
