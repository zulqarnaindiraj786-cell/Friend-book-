#!/usr/bin/env python3
"""Aggregate-only, capped inspection of the existing feed-exception index."""
import json
import os
import urllib.error
import urllib.request

SQL = """
WITH sample AS MATERIALIZED (
 SELECT doc_id,path,document_json FROM friendbook_cf_firestore_documents
 INDEXED BY idx_cf_feed_exceptions
 WHERE parent_path='posts' AND (doc_id GLOB '*[^ -~]*' OR path<>'posts/'||doc_id OR COALESCE(((json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
    OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)),0)=0)
 LIMIT 129
)
SELECT count(*) AS sampled,
 coalesce(sum(CASE WHEN coalesce(json_type(document_json,'$.createdAt'),'missing') IN ('missing','null','true','false') THEN 1 ELSE 0 END),0) AS low_rank,
 coalesce(sum(CASE WHEN json_type(document_json,'$.createdAt')='text' THEN 1 ELSE 0 END),0) AS text_values,
 coalesce(sum(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 1 ELSE 0 END),0) AS numeric_values,
 coalesce(sum(CASE WHEN json_type(document_json,'$.createdAt') IN ('object','array') THEN 1 ELSE 0 END),0) AS structured_values,
 coalesce(sum(CASE WHEN doc_id GLOB '*[^ -~]*' OR path<>'posts/'||doc_id THEN 1 ELSE 0 END),0) AS unusual_ids
FROM sample
"""


def summarize(payload):
    if not payload.get('success') or len(payload.get('result', [])) != 1:
        raise ValueError('incomplete D1 response')
    result=payload['result'][0]
    if not result.get('success') or len(result.get('results', [])) != 1:
        raise ValueError('incomplete D1 result')
    row=result['results'][0]
    keys=('sampled','low_rank','text_values','numeric_values','structured_values','unusual_ids')
    if any(type(row.get(k)) is not int or row[k]<0 for k in keys) or row['sampled']>129:
        raise ValueError('invalid aggregate')
    if sum(row[k] for k in keys[1:5])!=row['sampled'] or row['unusual_ids']>row['sampled']:
        raise ValueError('inconsistent aggregate')
    return {**{k:row[k] for k in keys},'truncated':row['sampled']==129,
            'd1_reported_rows_read':(result.get('meta') or {}).get('rows_read')}


def main():
    account='92f3d04598822de2e49e14561218d090'
    database='e1c605ca-a50b-46c6-9deb-56dd1f70d163'
    request=urllib.request.Request(f'https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{database}/query',
        data=json.dumps({'sql':SQL}).encode(),headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(request,timeout=45) as response: summary=summarize(json.load(response))
    except (urllib.error.URLError,ValueError,TimeoutError,KeyError):
        raise SystemExit('Aggregate preflight failed; no production changes made.')
    print('::notice:: Aggregate-only feed exception preflight '+json.dumps(summary,sort_keys=True))

if __name__=='__main__':main()
