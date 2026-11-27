#!/usr/bin/env python3
"""Explicit rollback: restore the dropped mirror from its in-D1 snapshot."""
import json, os, urllib.request
ACCOUNT='92f3d04598822de2e49e14561218d090'; DATABASE='e1c605ca-a50b-46c6-9deb-56dd1f70d163'
TABLE='friendbook_cf_documents'; BACKUP=TABLE+'_retired_backup'; SCHEMA=TABLE+'_retired_backup_schema'
def remote_query(sql,params=()):
 token=os.environ.get('CLOUDFLARE_API_TOKEN')
 if not token: raise RuntimeError('Cloudflare API token unavailable')
 url=f'https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/d1/database/{DATABASE}/query'
 req=urllib.request.Request(url,data=json.dumps({'sql':sql,'params':list(params)}).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=45) as r:p=json.load(r)
 item=p.get('result',[{}])[0]
 if not p.get('success') or not item.get('success') or not isinstance(item.get('results'),list):raise RuntimeError('D1 restore query failed')
 return item['results']
def restore(query=remote_query):
 names={r['name'] for r in query("SELECT name FROM sqlite_master WHERE type='table' AND name IN (?,?)",(TABLE,BACKUP))}
 if TABLE in names:raise ValueError('Mirror exists; refusing overwrite')
 if BACKUP not in names:raise ValueError('Rollback snapshot missing')
 schema=query(f'SELECT type,name,sql FROM "{SCHEMA}" ORDER BY CASE type WHEN \'table\' THEN 0 WHEN \'index\' THEN 1 ELSE 2 END,name')
 ddl=[r['sql'] for r in schema if r.get('type')=='table' and r.get('name')==TABLE]
 if len(ddl)!=1:raise ValueError('Original table schema missing')
 query(ddl[0]); cols='path,collection,doc_id,owner_uid,document_json,updated_at'
 query(f'INSERT INTO "{TABLE}"({cols}) SELECT {cols} FROM "{BACKUP}"')
 for r in schema:
  if r.get('type') in ('index','trigger') and isinstance(r.get('sql'),str):query(r['sql'])
 check=query(f'''SELECT (SELECT COUNT(*) FROM "{TABLE}") restored,(SELECT COUNT(*) FROM "{BACKUP}") backup,
 NOT EXISTS(SELECT {cols} FROM "{TABLE}" EXCEPT SELECT {cols} FROM "{BACKUP}") f,
 NOT EXISTS(SELECT {cols} FROM "{BACKUP}" EXCEPT SELECT {cols} FROM "{TABLE}") r''')
 if len(check)!=1 or check[0]['restored']!=check[0]['backup'] or check[0]['f']!=1 or check[0]['r']!=1:raise ValueError('Restore verification failed')
 return {'restored':True,'rows':check[0]['restored'],'backup_retained':True}
if __name__=='__main__':
 try:print('::notice:: Mirror restore '+json.dumps(restore(),sort_keys=True))
 except Exception as e:raise SystemExit('Mirror restore failed: '+type(e).__name__)
