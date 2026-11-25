#!/usr/bin/env python3
"""Capped aggregate counts only; never returns document data or mutates D1."""
import json,os,urllib.error,urllib.request
SQL='''WITH p AS MATERIALIZED (SELECT path,doc_id FROM friendbook_cf_firestore_documents INDEXED BY idx_friendbook_cf_firestore_parent WHERE parent_path='pages' LIMIT 257),
e AS MATERIALIZED (SELECT path,doc_id FROM friendbook_cf_firestore_documents INDEXED BY idx_friendbook_cf_firestore_parent WHERE parent_path='events' LIMIT 257)
SELECT 'pages' AS kind,count(*) AS sampled,COALESCE(sum(path<>'pages/'||doc_id),0) AS unusual FROM p
UNION ALL SELECT 'events' AS kind,count(*) AS sampled,COALESCE(sum(path<>'events/'||doc_id),0) AS unusual FROM e'''

def summarize(payload):
 if not payload.get('success') or len(payload.get('result',[]))!=1:raise ValueError('invalid response')
 result=payload['result'][0];rows=result.get('results',[])
 if not result.get('success') or len(rows)!=2:raise ValueError('invalid result')
 output={}
 for row in rows:
  kind=row.get('kind');n=row.get('sampled');unusual=row.get('unusual')
  if kind not in ('pages','events') or kind in output or type(n) is not int or not 0<=n<=257 or type(unusual) is not int or not 0<=unusual<=n:raise ValueError('invalid counts')
  output[kind]={'sampled':n,'unusual':unusual,'truncated':n==257}
 return {'collections':output,'d1_reported_rows_read':(result.get('meta') or {}).get('rows_read')}

def main():
 account='92f3d04598822de2e49e14561218d090';db='e1c605ca-a50b-46c6-9deb-56dd1f70d163'
 req=urllib.request.Request(f'https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{db}/query',data=json.dumps({'sql':SQL}).encode(),headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=45) as response: result=summarize(json.load(response))
 except (urllib.error.URLError,ValueError,KeyError,TimeoutError):raise SystemExit('Aggregate preflight failed; no schema change attempted.')
 print('::notice:: Capped simple-root aggregate preflight '+json.dumps(result,sort_keys=True))
if __name__=='__main__':main()
