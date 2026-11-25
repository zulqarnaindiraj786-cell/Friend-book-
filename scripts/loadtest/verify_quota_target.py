#!/usr/bin/env python3
"""Fail-closed acceptance verification using daily account GraphQL analytics.

The target is NOT a Cloudflare plan limit. No D1 SQL or app traffic is executed.
An observed completed day is not a future quota guarantee or device attribution.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import urllib.request

from d1_account_usage_analytics import make_query, parse

READ_TARGET=100_000
WRITE_TARGET=5_000


def evaluate_day(day,usage,now,fixes_since):
    start=dt.datetime.combine(day,dt.time.min,tzinfo=dt.timezone.utc)
    if day>now.date(): raise ValueError('Future day cannot be verified')
    result={'utc_date':day.isoformat(),'usage':usage,'complete_day':day<now.date(),
            'entire_day_after_fixes':start>=fixes_since,'status':'UNVERIFIED'}
    if usage is None:
        result['reason']='analytics_unavailable_not_zero'
        return result
    if any(type(usage.get(k)) is not int or usage[k]<0 for k in ('rowsRead','rowsWritten')):
        raise ValueError('Invalid daily usage')
    result['over_target']={k:max(0,usage[k]-limit) for k,limit in (('rowsRead',READ_TARGET),('rowsWritten',WRITE_TARGET))}
    if any(result['over_target'].values()):
        result.update(status='FAIL',reason='observed_account_daily_target_exceeded')
    elif not result['complete_day']:
        result['reason']='current_day_incomplete'
    elif not result['entire_day_after_fixes']:
        result['reason']='day_predates_or_straddles_fixes'
    else:
        result.update(status='OBSERVED_WITHIN_TARGET',reason='completed_post_fix_day_in_delayed_account_analytics')
    return result


def verdict(days):
    # Historical pre-fix days do not establish acceptance of the current fixes.
    relevant=[d for d in days if d['entire_day_after_fixes'] or not d['complete_day']]
    if any(d['status']=='FAIL' for d in relevant): return 'FAIL'
    if any(d['status']=='OBSERVED_WITHIN_TARGET' for d in relevant): return 'OBSERVED_WITHIN_TARGET'
    return 'UNVERIFIED'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixes-active-since',required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--enforce',action='store_true')
    args=parser.parse_args()
    fixes=dt.datetime.fromisoformat(args.fixes_active_since.replace('Z','+00:00'))
    if fixes.tzinfo is None: raise ValueError('Fix timestamp requires timezone')
    fixes=fixes.astimezone(dt.timezone.utc)
    now=dt.datetime.now(dt.timezone.utc)
    days=[]
    for offset in (2,1,0):
        day=now.date()-dt.timedelta(days=offset)
        query,variables=make_query(day.isoformat())
        req=urllib.request.Request('https://api.cloudflare.com/client/v4/graphql',
            data=json.dumps({'query':query,'variables':variables}).encode(),
            headers={'Authorization':'Bearer '+os.environ['CLOUDFLARE_API_TOKEN'],'Content-Type':'application/json'})
        try:
            with urllib.request.urlopen(req,timeout=30) as response: usage=parse(json.load(response))
        except (OSError,ValueError,KeyError,TypeError,TimeoutError):
            usage=None
        days.append(evaluate_day(day,usage,now,fixes))
    report={'status':verdict(days),'queried_at':now.isoformat(),'fixes_active_since':fixes.isoformat(),
            'acceptance_target_not_provider_limit':{'rowsRead':READ_TARGET,'rowsWritten':WRITE_TARGET},
            'source':'Delayed/adaptive Cloudflare account daily analytics; all databases and admin work included',
            'future_quota_guarantee':False,'matched_app_session_proven':False,'days':days}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
    print('::notice::Strict daily quota verification '+json.dumps(report,sort_keys=True))
    if args.enforce and report['status']!='OBSERVED_WITHIN_TARGET':
        raise SystemExit(2 if report['status']=='FAIL' else 3)


if __name__=='__main__':
    main()
