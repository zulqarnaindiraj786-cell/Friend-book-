#!/usr/bin/env python3
"""Read-only schema and backup verification after canonical mirror retirement."""
import json,sys
from scripts.loadtest.retired_table_preflight import remote_query

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
 print('::notice:: Canonical mirror snapshot diff '+json.dumps(result,sort_keys=True))
 return result

def inspect(query):
 rows,meta=query("SELECT name FROM sqlite_master WHERE type='table' AND name IN (?,?,?) ORDER BY name",('friendbook_cf_documents','friendbook_cf_documents_retired_backup','friendbook_cf_documents_retired_backup_schema'))
 names={r.get('name') for r in rows}
 if 'friendbook_cf_documents' in names:raise ValueError('Legacy application mirror still exists')
 required={'friendbook_cf_documents_retired_backup','friendbook_cf_documents_retired_backup_schema'}
 if not required<=names:raise ValueError('Rollback snapshot or schema metadata missing')
 counts,_=query('SELECT COUNT(*) AS n FROM friendbook_cf_documents_retired_backup',())
 if len(counts)!=1 or type(counts[0].get('n')) is not int:raise ValueError('Invalid rollback snapshot count')
 schema,_=query("SELECT COUNT(*) AS n FROM friendbook_cf_documents_retired_backup_schema WHERE type='table' AND name='friendbook_cf_documents' AND sql IS NOT NULL",())
 if len(schema)!=1 or schema[0].get('n')!=1:raise ValueError('Original schema not retained')
 return {'mirror_table_absent':True,'rollback_snapshot_present':True,'schema_retained':True,'snapshot_rows':counts[0]['n'],'rows_read':meta.get('rows_read')}
if __name__=='__main__':
 try:
  if '--diff' in sys.argv:diff(remote_query)
  else:print('::notice:: Canonical mirror retirement '+json.dumps(inspect(remote_query),sort_keys=True))
 except Exception as e:
  allowed={'Legacy application mirror still exists','Rollback snapshot or schema metadata missing','Invalid rollback snapshot count','Original schema not retained'}
  message=str(e) if str(e) in allowed else type(e).__name__
  raise SystemExit('Canonical mirror retirement verification failed: '+message)
