#!/usr/bin/env python3
"""Read-only schema and backup verification after canonical mirror retirement."""
import json,sys,urllib.error
from scripts.loadtest.retired_table_preflight import remote_query

class SanitizedHttpError(Exception):
 def __init__(self, status):
  super().__init__('Cloudflare D1 HTTP '+str(status))

def safe_query(sql, params):
 try:
  return remote_query(sql, params)
 except urllib.error.HTTPError as error:
  raise SanitizedHttpError(error.code) from None

def diff(query):
 names,_=query("SELECT name FROM sqlite_master WHERE type='table' AND name IN (?,?)",('friendbook_cf_documents','friendbook_cf_documents_retired_backup'))
 found={r.get('name') for r in names}
 if not {'friendbook_cf_documents','friendbook_cf_documents_retired_backup'}<=found:raise ValueError('source or snapshot table missing')
 rows,meta=query('''SELECT (SELECT COUNT(*) FROM friendbook_cf_documents) current_rows,
 (SELECT COUNT(*) FROM friendbook_cf_documents_retired_backup) snapshot_rows,
 (SELECT COUNT(*) FROM friendbook_cf_documents c LEFT JOIN friendbook_cf_documents_retired_backup b ON b.path=c.path WHERE b.path IS NULL) current_only,
 (SELECT COUNT(*) FROM friendbook_cf_documents_retired_backup b LEFT JOIN friendbook_cf_documents c ON c.path=b.path WHERE c.path IS NULL) snapshot_only,
 (SELECT COUNT(*) FROM friendbook_cf_documents c JOIN friendbook_cf_documents_retired_backup b ON b.path=c.path WHERE c.collection IS NOT b.collection OR c.doc_id IS NOT b.doc_id OR c.owner_uid IS NOT b.owner_uid OR c.document_json IS NOT b.document_json OR c.updated_at IS NOT b.updated_at) changed_rows''',())
 if len(rows)!=1 or any(type(rows[0].get(k)) is not int for k in ('current_rows','snapshot_rows','current_only','snapshot_only','changed_rows')):raise ValueError('invalid aggregate diff')
 result={**rows[0],'rows_read':meta.get('rows_read')}
 # Counts grouped only by collection identify which application path repopulated
 # the mirror without exposing paths, owners, IDs, or document contents.
 breakdown,_=query('''SELECT collection,
 COUNT(*) current_rows,
 SUM(CASE WHEN b.path IS NULL THEN 1 ELSE 0 END) current_only,
 SUM(CASE WHEN b.path IS NOT NULL AND (c.doc_id IS NOT b.doc_id OR c.owner_uid IS NOT b.owner_uid OR c.document_json IS NOT b.document_json OR c.updated_at IS NOT b.updated_at) THEN 1 ELSE 0 END) changed_rows
 FROM friendbook_cf_documents c LEFT JOIN friendbook_cf_documents_retired_backup b ON b.path=c.path
 GROUP BY collection ORDER BY collection''',())
 result['by_collection']=breakdown
 print('::notice:: Canonical mirror snapshot diff '+json.dumps(result,sort_keys=True))
 return result

def inspect(query):
 rows,meta=query("SELECT name FROM sqlite_master WHERE type='table' AND name IN (?,?,?,?) ORDER BY name",('friendbook_cf_documents','friendbook_cf_documents_retired_backup','friendbook_cf_documents_retired_backup_schema','friendbook_cf_documents_reappeared_0106'))
 names={r.get('name') for r in rows}
 if 'friendbook_cf_documents' in names:raise ValueError('Legacy application mirror still exists')
 required={'friendbook_cf_documents_retired_backup','friendbook_cf_documents_retired_backup_schema','friendbook_cf_documents_reappeared_0106'}
 if not required<=names:raise ValueError('Rollback snapshot or schema metadata missing')
 counts,_=query('SELECT COUNT(*) AS n FROM friendbook_cf_documents_retired_backup',())
 if len(counts)!=1 or type(counts[0].get('n')) is not int:raise ValueError('Invalid rollback snapshot count')
 schema,_=query("SELECT COUNT(*) AS n FROM friendbook_cf_documents_retired_backup_schema WHERE type='table' AND name='friendbook_cf_documents' AND sql IS NOT NULL",())
 if len(schema)!=1 or schema[0].get('n')!=1:raise ValueError('Original schema not retained')
 return {'mirror_table_absent':True,'rollback_snapshot_present':True,'schema_retained':True,'snapshot_rows':counts[0]['n'],'rows_read':meta.get('rows_read')}
if __name__=='__main__':
 try:
  if '--diff' in sys.argv:diff(safe_query)
  else:print('::notice:: Canonical mirror retirement '+json.dumps(inspect(safe_query),sort_keys=True))
 except Exception as e:
  allowed={'Legacy application mirror still exists','Rollback snapshot or schema metadata missing','Invalid rollback snapshot count','Original schema not retained'}
  message=str(e) if str(e) in allowed or isinstance(e,SanitizedHttpError) else type(e).__name__
  print('::error:: Canonical mirror audit: '+message)
  raise SystemExit('Canonical mirror retirement verification failed: '+message)
