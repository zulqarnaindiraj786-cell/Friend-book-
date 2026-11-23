#!/usr/bin/env python3
"""Read-only aggregate guard profiling, only after bounded preflight succeeds."""
import json
import os
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]

def main():
    # Fail closed on excessive table size/exception count or account usage.
    subprocess.run(['python3', 'scripts/loadtest/d1_account_usage_analytics.py', '--preflight-budget'], cwd=ROOT, check=True)
    subprocess.run(['python3', 'scripts/loadtest/friend_guard_index_preflight.py', '--require-index-build-safe'], cwd=ROOT, check=True)
    probes=json.loads(subprocess.check_output(['node','scripts/loadtest/friend_guard_queries.mjs'],cwd=ROOT,text=True))
    summaries=[]
    for probe in probes:
        if not probe['sql'].startswith('SELECT ') or ';' in probe['sql']:
            raise ValueError('Only single aggregate SELECT allowed')
        for explain in (True,False):
            req=urllib.request.Request('https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query',
                data=json.dumps({'sql':('EXPLAIN QUERY PLAN ' if explain else '')+probe['sql'],'params':probe['params']}).encode(),
                headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
            with urllib.request.urlopen(req,timeout=45) as response: payload=json.load(response)
            if not payload.get('success') or len(payload.get('result',[]))!=1 or not payload['result'][0].get('success'):
                raise ValueError('Read-only profiling response incomplete')
            result=payload['result'][0]
            if result.get('meta',{}).get('rows_written') != 0:
                raise ValueError('Read-only profiling invariant violated')
            if explain:
                details=[r['detail'] for r in result['results']]
                print('Guard plan '+probe['name']+' '+json.dumps(details))
            else:
                summaries.append({'name':probe['name'],'aggregate':result['results'],'rows_read':result['meta']['rows_read'],'rows_written':result['meta']['rows_written']})
    print('::notice::Guard probe summary '+json.dumps(summaries,sort_keys=True))

if __name__=='__main__':
    main()
