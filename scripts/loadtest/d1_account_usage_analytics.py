#!/usr/bin/env python3
"""Read-only account-wide daily D1 analytics. No app queries or database changes.

These dashboard-source analytics can be delayed or adaptive and are not a
transactional quota balance. Never use them alone to guarantee migration safety.
"""
import datetime as dt
import json
import os
import sys
import urllib.error
import urllib.request


def make_query(day):
    # No databaseId filter: totals include every D1 database in the account.
    return '''query($account: String!, $day: Date!) {
      viewer { accounts(filter: {accountTag: $account}) {
        d1AnalyticsAdaptiveGroups(limit: 1, filter: {date_geq: $day, date_leq: $day}) {
          sum { rowsRead rowsWritten }
        }
      } }
    }''', {'account': '92f3d04598822de2e49e14561218d090', 'day': day}


def parse(payload):
    if payload.get('errors'):
        raise ValueError('Analytics request rejected')
    accounts = payload['data']['viewer']['accounts']
    if len(accounts) != 1:
        raise ValueError('Account analytics unavailable')
    groups = accounts[0]['d1AnalyticsAdaptiveGroups']
    if len(groups) != 1:
        raise ValueError('Daily analytics unavailable')
    values = groups[0]['sum']
    if any(type(values.get(k)) is not int or values[k] < 0 for k in ('rowsRead', 'rowsWritten')):
        raise ValueError('Invalid daily analytics')
    return {'rowsRead': values['rowsRead'], 'rowsWritten': values['rowsWritten']}


def within_preflight_budget(result):
    # Deliberately large margins for delayed analytics, sampling, normal activity
    # and index work. This is still not a guaranteed live quota balance.
    return result['rowsRead'] <= 3_000_000 and result['rowsWritten'] <= 50_000


def within_fcm_metadata_budget(result):
    # Applies only to a metadata-only table/trigger rollout with no index build,
    # backfill or row scan. This is a reserve, not a live-balance guarantee.
    return result['rowsRead'] <= 3_000_000 and result['rowsWritten'] <= 75_000


def within_user_backfill_budget(result):
    # Only for separately tested, atomic at-most-two user, friend-request or
    # friendship promotions. Users have strict follower/following fanout caps. Delayed
    # analytics are not a live quota balance and do not predict billing.
    return result['rowsRead'] <= 3_000_000 and result['rowsWritten'] <= 60_000


def within_retired_drop_budget(result):
    # Only for the seven retired tables, after the independent row/index cap
    # and schema guard pass. This reserve does not guarantee DROP billing.
    return result['rowsRead'] <= 3_000_000 and result['rowsWritten'] <= 60_000


def within_root_backfill_budget(result):
    # Bulk promotion of legacy-only root documents is capped per run: at most
    # 6,000 promoted rows and a bounded number of batches. This reserve keeps
    # headroom for the triggers on chats/comments and is not a billing guarantee.
    return result['rowsRead'] <= 3_000_000 and result['rowsWritten'] <= 150_000


def within_notification_inspection_budget(result):
    # Read-only, capped notification index inspection, never a migration or an
    # index build. Keep the independent 50k write limit for those operations.
    return result['rowsRead'] <= 3_000_000 and result['rowsWritten'] <= 75_000


def main():
    token = os.environ.get('CLOUDFLARE_API_TOKEN')
    if not token:
        raise SystemExit('Cloudflare token unavailable; no changes made.')
    now = dt.datetime.now(dt.timezone.utc)
    query, variables = make_query(now.date().isoformat())
    req = urllib.request.Request('https://api.cloudflare.com/client/v4/graphql',
        data=json.dumps({'query': query, 'variables': variables}).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            result = parse(json.load(response))
    except (urllib.error.URLError, ValueError, TimeoutError, KeyError, TypeError):
        raise SystemExit('Account analytics unavailable; no changes made.')
    print('::notice:: Account-wide D1 daily analytics ' + json.dumps({
        **result, 'utc_date': now.date().isoformat(), 'queried_at': now.isoformat()
    }, sort_keys=True))
    print('::notice:: Analytics may be delayed/adaptive; this is not a guaranteed live quota balance.')
    if '--preflight-budget' in sys.argv and not within_preflight_budget(result):
        raise SystemExit('Conservative preflight budget exceeded; no D1 SQL or migration attempted.')
    if '--fcm-metadata-budget' in sys.argv and not within_fcm_metadata_budget(result):
        raise SystemExit('FCM metadata-only rollout reserve exceeded; no migration attempted.')
    if '--notification-inspection-budget' in sys.argv and not within_notification_inspection_budget(result):
        raise SystemExit('Read-only notification inspection reserve exceeded; no D1 inspection attempted.')
    if '--user-backfill-budget' in sys.argv and not within_user_backfill_budget(result):
        raise SystemExit('Two-user backfill reserve exceeded; no further backfill work attempted.')
    if '--root-backfill-budget' in sys.argv and not within_root_backfill_budget(result):
        raise SystemExit('Root backfill reserve exceeded; no further backfill work attempted.')
    if '--retired-drop-budget' in sys.argv and not within_retired_drop_budget(result):
        raise SystemExit('Retired-table drop reserve exceeded; no migration attempted.')


if __name__ == '__main__':
    main()
