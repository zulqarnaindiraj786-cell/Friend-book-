#!/usr/bin/env python3
"""Read-only schema and backup verification after canonical mirror retirement."""
import json
from scripts.loadtest.retired_table_preflight import remote_query

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
 try:print('::notice:: Canonical mirror retirement '+json.dumps(inspect(remote_query),sort_keys=True))
 except Exception as e:raise SystemExit('Canonical mirror retirement verification failed: '+type(e).__name__)
