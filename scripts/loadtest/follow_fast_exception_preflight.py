#!/usr/bin/env python3
"""Read only aggregate to evaluate a lower-cost follow-index trigger predicate."""
import json
import os
import urllib.error
import urllib.request

# Conservative: escapes or repeated key text anywhere are exceptional, so no
# JSON virtual-table scan runs per profile update. Only counts leave D1.
SQL="""
WITH s AS MATERIALIZED (
 SELECT 1 AS source,document_json AS doc FROM (SELECT document_json FROM friendbook_cf_firestore_documents WHERE parent_path='users' LIMIT 1001)
 UNION ALL SELECT 0,document_json FROM (SELECT document_json FROM friendbook_cf_documents INDEXED BY idx_friendbook_cf_documents_collection_updated WHERE collection='users' LIMIT 1001)
), p AS MATERIALIZED (
 SELECT source, CASE WHEN instr(doc,char(92))>0
  OR length(doc)-length(replace(doc,'"followers"',''))>length('"followers"')
  OR length(doc)-length(replace(doc,'"following"',''))>length('"following"')
  OR (source=0 AND (json_type(doc,'$.fields') IS 'object'
   OR length(doc)-length(replace(doc,'"fields"',''))>length('"fields"'))) THEN 1 ELSE 0 END AS exceptional FROM s
)
SELECT count(*) AS sampled,
 coalesce(sum(CASE WHEN source=1 THEN 1 ELSE 0 END),0) AS canonical,
 coalesce(sum(CASE WHEN source=0 THEN 1 ELSE 0 END),0) AS legacy,
 coalesce(sum(CASE WHEN source=1 AND exceptional=1 THEN 1 ELSE 0 END),0) AS canonical_exception,
 coalesce(sum(CASE WHEN source=0 AND exceptional=1 THEN 1 ELSE 0 END),0) AS legacy_exception FROM p
"""

def main():
 account='92f3d04598822de2e49e14561218d090'; database='e1c605ca-a50b-46c6-9deb-56dd1f70d163'
 req=urllib.request.Request(f'https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{database}/query',data=json.dumps({'sql':SQL}).encode(),headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=45) as response: data=json.load(response)
 except (urllib.error.URLError,ValueError,TimeoutError): raise SystemExit('Preflight failed; no production changes made.')
 if not data.get('success') or len(data.get('result',[]))!=1 or not data['result'][0].get('success') or len(data['result'][0].get('results',[]))!=1:raise SystemExit('Invalid preflight result; no changes made.')
 item=data['result'][0];row=item['results'][0]
 keys=('sampled','canonical','legacy','canonical_exception','legacy_exception')
 if any(type(row.get(k)) is not int or row[k]<0 for k in keys) or row['canonical']>1001 or row['legacy']>1001:raise SystemExit('Invalid aggregate; no changes made.')
 result={k:row[k] for k in keys};result['truncated']=row['canonical']==1001 or row['legacy']==1001;result['rows_read']=(item.get('meta') or {}).get('rows_read')
 print('::notice:: Follow trigger conservative exception preflight '+json.dumps(result,sort_keys=True))
if __name__=='__main__':main()
