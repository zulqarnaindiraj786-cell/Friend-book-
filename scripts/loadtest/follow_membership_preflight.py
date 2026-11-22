#!/usr/bin/env python3
"""Capped aggregate-only D1 follow-list index investigation; no user values."""
import json
import os
import urllib.error
import urllib.request

# Only counts/lengths are returned. Cap both source sets; a truncated result
# cannot be used to approve a build. These are not billed-write estimates.
SQL = """
WITH canonical AS MATERIALIZED (
 SELECT path,document_json FROM friendbook_cf_firestore_documents
 WHERE parent_path='users' LIMIT 1001
), legacy AS MATERIALIZED (
 SELECT path,document_json FROM friendbook_cf_documents
 INDEXED BY idx_friendbook_cf_documents_collection_updated
 WHERE collection='users' LIMIT 1001
), shaped AS MATERIALIZED (
 SELECT 1 AS source, document_json AS doc, 0 AS wrapped FROM canonical
 UNION ALL
 SELECT 0, document_json, CASE WHEN json_type(document_json,'$.fields')='object' THEN 1 ELSE 0 END FROM legacy
), arrays AS MATERIALIZED (
 SELECT source,wrapped,
  CASE WHEN wrapped=1 AND json_type(doc,'$.fields.followers.arrayValue.values')='array' THEN json_array_length(doc,'$.fields.followers.arrayValue.values')
       WHEN wrapped=0 AND json_type(doc,'$.followers')='array' THEN json_array_length(doc,'$.followers') ELSE 0 END AS followers,
  CASE WHEN wrapped=1 AND json_type(doc,'$.fields.following.arrayValue.values')='array' THEN json_array_length(doc,'$.fields.following.arrayValue.values')
       WHEN wrapped=0 AND json_type(doc,'$.following')='array' THEN json_array_length(doc,'$.following') ELSE 0 END AS following
 FROM shaped
)
SELECT (SELECT count(*) FROM canonical) AS canonical_users,
 (SELECT count(*) FROM legacy) AS legacy_users,
 (SELECT count(*) FROM legacy a WHERE NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)) AS legacy_only,
 (SELECT count(*) FROM arrays WHERE source=0 AND wrapped=1) AS wrapped_legacy,
 (SELECT coalesce(sum(followers+following),0) FROM arrays) AS sampled_array_entries,
 (SELECT coalesce(max(max(followers,following)),0) FROM arrays) AS largest_array
"""


def main():
    account='92f3d04598822de2e49e14561218d090'
    database='e1c605ca-a50b-46c6-9deb-56dd1f70d163'
    request=urllib.request.Request(f'https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{database}/query',
        data=json.dumps({'sql':SQL}).encode(),headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(request,timeout=45) as response: payload=json.load(response)
    except (urllib.error.URLError,ValueError,TimeoutError):
        raise SystemExit('Aggregate preflight failed; no production changes made.')
    if not payload.get('success') or len(payload.get('result',[]))!=1:
        raise SystemExit('D1 did not accept preflight; no production changes made.')
    result=payload['result'][0]
    if not result.get('success') or len(result.get('results',[]))!=1:
        raise SystemExit('D1 returned incomplete preflight; no production changes made.')
    row=result['results'][0]
    keys=('canonical_users','legacy_users','legacy_only','wrapped_legacy','sampled_array_entries','largest_array')
    if any(type(row.get(k)) is not int or row[k]<0 for k in keys) or max(row['canonical_users'],row['legacy_users'])>1001:
        raise SystemExit('D1 returned invalid aggregates; no production changes made.')
    summary={key:row[key] for key in keys}
    summary['truncated']=max(row['canonical_users'],row['legacy_users'])==1001
    summary['d1_reported_rows_read']=(result.get('meta') or {}).get('rows_read')
    print('::notice:: Aggregate-only follow membership preflight '+json.dumps(summary,sort_keys=True))


if __name__=='__main__': main()
