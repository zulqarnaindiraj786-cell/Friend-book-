#!/usr/bin/env python3
"""Bounded read-only count; no identifiers, tokens or notification data leave D1."""
import json
import os
import sys
import urllib.error
import urllib.request

CAP=2001
SQL='SELECT count(*) AS sampled FROM (SELECT event_key FROM friendbook_cf_fcm_device_claims LIMIT 2001)'

def main():
 account='92f3d04598822de2e49e14561218d090'; database='e1c605ca-a50b-46c6-9deb-56dd1f70d163'
 request=urllib.request.Request(f'https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{database}/query',data=json.dumps({'sql':SQL}).encode(),headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(request,timeout=45) as response:data=json.load(response)
 except (urllib.error.URLError,ValueError,TimeoutError):raise SystemExit('Claim preflight failed; no production changes made.')
 if not data.get('success') or len(data.get('result',[]))!=1 or not data['result'][0].get('success') or len(data['result'][0].get('results',[]))!=1:raise SystemExit('Invalid claim preflight result.')
 item=data['result'][0];sampled=item['results'][0].get('sampled')
 if type(sampled) is not int or sampled<0 or sampled>CAP:raise SystemExit('Invalid claim aggregate.')
 print('::notice:: FCM claim preflight '+json.dumps({'sampled':sampled,'truncated':sampled==CAP,'rows_read':(item.get('meta') or {}).get('rows_read')}))
 if '--require-build-safe' in sys.argv and sampled==CAP:raise SystemExit('Claim conversion exceeds safety bound; migration blocked.')
if __name__=='__main__':main()
