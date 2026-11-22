#!/usr/bin/env python3
"""Bounded, aggregate-only D1 preflight. No user values or application traffic."""
import json
import os
import urllib.error
import urllib.request

# LIMIT bounds matching rows sampled, not D1 billing. The collection-leading
# index avoids an unbounded scan over other collections. No row values leave SQL.
SQL = """
WITH sample AS MATERIALIZED (
 SELECT path,doc_id,document_json FROM friendbook_cf_documents
 INDEXED BY idx_friendbook_cf_documents_collection_updated
 WHERE collection='comments' LIMIT 5001
), shaped AS MATERIALIZED (
 SELECT CASE WHEN json_type(document_json,'$.fields') IS NOT 'object'
   AND json_type(document_json,'$.createdAt') IN ('integer','real')
   AND json_extract(document_json,'$.createdAt') BETWEEN 1 AND 9007199254740991
   AND doc_id<>'' AND doc_id NOT GLOB '*[^a-zA-Z0-9_-]*' AND path='comments/'||doc_id
   THEN 1 ELSE 0 END AS safe,
   CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.postId.stringValue') ELSE json_extract(document_json,'$.postId') END AS p,
   CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.post_id.stringValue') ELSE json_extract(document_json,'$.post_id') END AS pl,
   CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.resource.stringValue') ELSE json_extract(document_json,'$.resource') END AS r
 FROM sample
), exceptions AS (
 SELECT p AS route FROM shaped WHERE safe=0 AND typeof(p)='text' AND p<>''
 UNION ALL SELECT pl FROM shaped WHERE safe=0 AND typeof(pl)='text' AND pl<>''
 UNION ALL SELECT r FROM shaped WHERE safe=0 AND typeof(r)='text' AND r<>''
), sizes AS (SELECT count(*) AS n FROM exceptions GROUP BY route)
SELECT (SELECT count(*) FROM shaped) AS sampled,
 (SELECT coalesce(sum(safe),0) FROM shaped) AS numeric_safe,
 (SELECT coalesce(max(n),0) FROM sizes) AS largest_exception_route_upper_bound
"""


def main():
    account = '92f3d04598822de2e49e14561218d090'
    database = 'e1c605ca-a50b-46c6-9deb-56dd1f70d163'
    token = os.environ['CLOUDFLARE_API_TOKEN']
    request = urllib.request.Request(
        f'https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{database}/query',
        data=json.dumps({'sql': SQL}).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'},
    )
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            payload = json.load(response)
    except (urllib.error.URLError, ValueError, TimeoutError):
        raise SystemExit('Read-only preflight request failed; no production changes made.')
    if not payload.get('success') or len(payload.get('result', [])) != 1:
        raise SystemExit('D1 did not accept the read-only preflight; no production changes made.')
    result = payload['result'][0]
    if not result.get('success') or len(result.get('results', [])) != 1:
        raise SystemExit('D1 returned an incomplete preflight; no production changes made.')
    row = result['results'][0]
    keys = ('sampled', 'numeric_safe', 'largest_exception_route_upper_bound')
    if any(type(row.get(k)) is not int or row[k] < 0 for k in keys) or row['sampled'] > 5001 or row['numeric_safe'] > row['sampled']:
        raise SystemExit('D1 returned invalid aggregate results; no production changes made.')
    meta = result.get('meta') or {}
    summary = {k: row[k] for k in keys}
    summary['truncated'] = row['sampled'] == 5001
    summary['d1_reported_rows_read'] = meta.get('rows_read')
    print('::notice:: Aggregate-only legacy comment preflight ' + json.dumps(summary, sort_keys=True))


if __name__ == '__main__':
    main()
