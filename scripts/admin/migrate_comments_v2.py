"""Explicit admin migration. Default dry-run. Never deletes comment records.
Requires google-cloud-firestore and secure ADC/GOOGLE_APPLICATION_CREDENTIALS outside Git.
Copies missing canonical records before pruning frozen legacy parent maps with update-time precondition.
"""
import argparse, json, os
from datetime import datetime

def legacy_records(data, parent_id):
    records={}
    raw_map=data.get('commentsMap') or {}
    raw_list=data.get('commentsList') or []
    if not isinstance(raw_map,dict) or not isinstance(raw_list,list): raise ValueError('Unrecognized legacy storage; review backup before proceeding')
    for key,value in raw_map.items():
        if not isinstance(value,dict) or not key or '/' in str(key): raise ValueError('Invalid legacy map record; refusing to prune')
    for value in raw_list:
        if not isinstance(value,dict) or not value.get('id') or '/' in str(value['id']): raise ValueError('Invalid legacy list record; refusing to prune')
    for key,value in (data.get('commentsMap') or {}).items():
        if isinstance(value,dict) and key and '/' not in str(key): records[str(key)]={**value,'id':str(key)}
    for value in data.get('commentsList') or []:
        if isinstance(value,dict) and value.get('id') and '/' not in str(value['id']): records.setdefault(str(value['id']),dict(value))
    for key,value in records.items():
        value.setdefault('postId',parent_id); value.setdefault('createdAt',0)
    return records

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',required=True,choices=['friendbook-17551'])
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--backup',help='Private JSONL backup path; required for --apply. Keep outside Git.')
    parser.add_argument('--max-parents',type=int,default=100)
    parser.add_argument('--after-post-id')
    parser.add_argument('--after-reel-id')
    parser.add_argument('--prune-legacy',action='store_true',help='Only after old writers are retired and backup/canonical copy verified')
    args=parser.parse_args()
    if args.apply and not args.backup: parser.error('--apply requires --backup')
    if args.max_parents<1: parser.error('--max-parents must be positive')
    from google.cloud import firestore
    from google.api_core.exceptions import AlreadyExists
    db=firestore.Client(project=args.project)
    backup=os.fdopen(os.open(args.backup,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w',encoding='utf8') if args.backup else None
    totals={'parents':0,'canonical_created':0,'pruned':0,'dry_run':not args.apply}
    try:
        for collection in ('posts','reels'):
            query=db.collection(collection).order_by('__name__')
            after=args.after_post_id if collection=='posts' else args.after_reel_id
            if after: query=query.start_after(db.collection(collection).document(after).get())
            for doc in query.limit(args.max_parents).stream():
                totals['last_'+collection+'_id']=doc.id
                data=doc.to_dict(); key=doc.id if collection=='posts' else 'reel_'+doc.id
                records=legacy_records(data,key)
                # Root-only legacy records are included, not just map/subcollection mirrors.
                for root in db.collection('comments').where('postId','==',key).stream():
                    records.setdefault(root.id,root.to_dict())
                if not records and not data.get('commentsMap') and not data.get('commentsList'): continue
                totals['parents']+=1
                if backup: backup.write(json.dumps({'path':doc.reference.path,'update_time':str(doc.update_time),'parent':data,'records':records},default=str)+'\n'); backup.flush()
                if not args.apply: continue
                for cid,record in records.items():
                    record.setdefault('id',cid); record.setdefault('postId',key)
                    record.setdefault('authorId',record.get('userId',record.get('uid','')))
                    record.setdefault('content',record.get('text',record.get('message','')))
                    created=record.get('createdAt',0)
                    if isinstance(created,datetime): record['createdAt']=int(created.timestamp()*1000)
                    elif isinstance(created,str): record['createdAt']=int(datetime.fromisoformat(created.replace('Z','+00:00')).timestamp()*1000)
                    else: record['createdAt']=int(created)
                    try: doc.reference.collection('comments').document(cid).create(record); totals['canonical_created']+=1
                    except AlreadyExists: pass # Never overwrite newer edits/reactions.
                if not args.prune_legacy: continue
                # Refuse to prune a parent that changed during copying. Safe to rerun after old clients retire.
                doc.reference.update({'commentsMap':firestore.DELETE_FIELD,'commentsList':firestore.DELETE_FIELD,
                                      'commentsStorageVersion':2},option=db.write_option(last_update_time=doc.update_time))
                totals['pruned']+=1
    finally:
        if backup: backup.close()
    print(json.dumps(totals))
if __name__=='__main__': main()
