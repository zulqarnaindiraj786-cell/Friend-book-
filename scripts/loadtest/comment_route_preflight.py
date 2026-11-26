#!/usr/bin/env python3
"""Capped aggregate-only candidate route size preflight; no comment values leave D1."""
import json,os,urllib.error,urllib.request

SQL="""
WITH sample AS MATERIALIZED (
 SELECT document_json FROM friendbook_cf_firestore_documents INDEXED BY idx_friendbook_cf_firestore_parent
 WHERE parent_path='comments' LIMIT 5001
), routes AS (
 SELECT
   CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.postId.stringValue') ELSE json_extract(document_json,'$.postId') END AS p,
   CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.post_id.stringValue') ELSE json_extract(document_json,'$.post_id') END AS pl,
   CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.resource.stringValue') ELSE json_extract(document_json,'$.resource') END AS r
 FROM sample
)
SELECT count(*) AS sampled,
 COALESCE(sum((typeof(p)='text')+(typeof(pl)='text')+(typeof(r)='text')),0) AS text_fields,
 COALESCE(sum(2*(typeof(p)='text')+2*(typeof(pl)='text')+(typeof(r)='text')),0) AS route_rows_upper_bound
FROM routes
"""

def summarize(payload):
 if not payload.get('success') or len(payload.get('result',[]))!=1: raise ValueError('invalid response')
 result=payload['result'][0]
 if not result.get('success') or len(result.get('results',[]))!=1:raise ValueError('invalid result')
 row=result['results'][0];keys=('sampled','text_fields','route_rows_upper_bound')
 if any(type(row.get(k)) is not int or row[k]<0 for k in keys) or row['sampled']>5001 or row['text_fields']>3*row['sampled'] or row['route_rows_upper_bound']>5*row['sampled'] or row['route_rows_upper_bound']<row['text_fields']:raise ValueError('invalid counts')
 return {**{k:row[k] for k in keys},'truncated':row['sampled']==5001,'d1_reported_rows_read':(result.get('meta') or {}).get('rows_read')}

def main():
 account='92f3d04598822de2e49e14561218d090';db='e1c605ca-a50b-46c6-9deb-56dd1f70d163'
 req=urllib.request.Request(f'https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{db}/query',data=json.dumps({'sql':SQL}).encode(),headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=45) as response: result=summarize(json.load(response))
 except (urllib.error.URLError,ValueError,KeyError,TimeoutError):raise SystemExit('Aggregate preflight failed; no migration attempted.')
 print('::notice:: Aggregate-only historical comment route preflight '+json.dumps(result,sort_keys=True))

if __name__=='__main__':main()
