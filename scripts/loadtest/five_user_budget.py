#!/usr/bin/env python3
"""Five-user daily D1 read/write budget, derived from the limits in this codebase.

No network access. This is a document-delivery scenario, NOT measured D1 rows_read. SQL indexes,
engagement subqueries and server scans can consume more; production telemetry is required. Run:

    python3 -m scripts.loadtest.five_user_budget

Exit code 0 only while daily reads < 100,000 and daily writes < 5,000. The report also prints the
baseline this work replaced, so the delta is auditable instead of claimed.
"""
import json

USERS = 5

# ---------------------------------------------------------------- activity per user per day
ACTIVITY = {
    'app_opens': 20,             # cold/warm opens that paint Home + the bottom-tab badges
    'feed_pages': 20,            # further pages after the first (HomeFragment PAGE = 10)
    'story_viewer_opens': 10,
    'notification_opens': 5,
    'notification_pages': 5,
    'notification_mark_all': 1,
    'notification_unread_to_mark': 40,
    'chat_list_opens': 10,
    'chat_list_visible_pushes': 20,
    'chat_list_pulls': 2,
    'chat_list_older_pages': 5,
    'chat_thread_opens': 12,
    'chat_sent': 30,
    'chat_received': 45,         # while the thread is already open
    'profile_opens': 20,
    'likes_reactions': 50,
    'posts_comments': 5,
    'settings_saves': 5,
    'live_host_minutes': 10,
    'foreground_minutes': 120,
}

# ---------------------------------------------------------------- reads per action (code limits)
# HomeFragment: posts 10, visible story metadata 3, live cards 3, auth allowance 1.
# Discovery candidates 3 are charged separately only once scrolled into view.
# MainActivity badges: notifications BADGE_WINDOW(32), chats CHAT_BADGE_LIMIT(20), friend_requests
#   BADGE_WINDOW(32), reels limit(16) — all one-shot now, so a change re-reads nothing.
HOME_READS = 10 + 3 + 3 + 1
BADGE_READS = 32 + 20 + 32 + 16

READ_COST = {
    'app_open_home_and_badges': HOME_READS + BADGE_READS,   # 117
    'feed_listener_redeliveries': 0,                   # no Home feed listener exists
    'friend_graph_refresh': 60,                             # one assumed 60-friend graph, reused from disk
    'feed_page': 10,
    'story_viewer_open': 10,
    'notification_open': 20,  # first page, not the explicit mark-all operation
    'notification_page': 20,
    'notification_sender_lookups': 20,  # conservative allowance: one unknown sender per row
    'notification_mark_batch': 20,
    'chat_list_open': 11,  # 10 displayed rooms + 1 lookahead; no separate users-strip query
    'chat_thread_open': 40 + 1 + 2,
    'chat_send': 3,
    'chat_received_while_open': 0,
    'profile_open': 4,
    'reaction': 0,
}

# ---------------------------------------------------------------- writes per action (one row each)
WRITE_COST = {
    'message': 1,
    'room_preview': 1,
    'notification': 1,
    'reaction': 2,
    'receipt': 1,
    'post_or_comment': 3,
    'settings': 1,
    'live_heartbeat': 1,        # host heartbeat on state change, no timer
    'presence_heartbeat': 0,    # presence is retired from the client: no timer writes at all
    'fcm_maintenance': 1,       # 12-hourly registration refresh
}

CRON_RUNS = 96                  # scheduled worker runs per day (*/15)
CRON_READS_PER_RUN = 5          # one bounded cleanup SELECT (<= 20 rows)
CRON_WRITES_PER_RUN = 0

# ---------------------------------------------------------------- published baseline
# The previous version of this file (see git history before this commit) reported these totals for
# the same five users. They are the reference for the delta, so the saving is auditable.
PUBLISHED_BASELINE_READS = 43_630
PUBLISHED_BASELINE_WRITES = 1_875


def per_user_reads():
    a, c = ACTIVITY, READ_COST
    return {
        'app_opens': c['app_open_home_and_badges'] + (a['app_opens'] - 1),
        'visible_discovery': 3,
        'explicit_home_refreshes': 2 * (HOME_READS - 1 + 3 + 60),
        'feed_listener': a['app_opens'] * c['feed_listener_redeliveries'],
        'friend_graph': c['friend_graph_refresh'],
        'feed_pages': a['feed_pages'] * c['feed_page'],
        'story_viewer': a['story_viewer_opens'] * c['story_viewer_open'],
        # Charge every list open/page as a cache miss (hits cost zero query documents).
        'notifications': a['notification_opens'] * c['notification_open'],
        'notification_pages': a['notification_pages'] * c['notification_page'],
        'notification_senders': (a['notification_opens'] + a['notification_pages']) * c['notification_sender_lookups'],
        # Mark-all reads every unread row, plus an empty-tail probe and refreshed head.
        # Smaller batches reduce peak work, NOT total rows needed to mark everything.
        'notification_mark_all': a['notification_mark_all'] * (a['notification_unread_to_mark'] + 1 + c['notification_open']),
        # Conservatively charge all opens as misses, plus explicit pulls, older pages and visible pushes.
        'chat_list': (a['chat_list_opens'] + a['chat_list_visible_pushes'] + a['chat_list_pulls'] + a['chat_list_older_pages']) * c['chat_list_open'],
        'chat_threads': a['chat_thread_opens'] * c['chat_thread_open'],
        'chat_sends': a['chat_sent'] * c['chat_send'],
        'chat_received': a['chat_received'] * c['chat_received_while_open'],
        'profiles': a['profile_opens'] * c['profile_open'],
        'reactions': a['likes_reactions'] * c['reaction'],
    }


def per_user_writes():
    a, w = ACTIVITY, WRITE_COST
    return {
        'messages': a['chat_sent'] * w['message'],
        'room_previews': a['chat_sent'] * w['room_preview'],
        'notifications': a['chat_sent'] * w['notification'],
        'notification_mark_all': a['notification_mark_all'] * a['notification_unread_to_mark'],
        'reactions': a['likes_reactions'] * w['reaction'],
        'receipts': round(a['chat_received'] * 0.5) * w['receipt'],
        'posts_comments': (a['posts_comments'] * 2) * w['post_or_comment'],
        'settings': a['settings_saves'] * w['settings'],
        'live': a['live_host_minutes'] * w['live_heartbeat'],
        'presence': round(a['foreground_minutes'] / 4) * w['presence_heartbeat'],
        'fcm_maintenance': 2 * w['fcm_maintenance'],
    }


def baseline_totals():
    return PUBLISHED_BASELINE_READS, PUBLISHED_BASELINE_WRITES


def main():
    reads = {k: round(v) for k, v in per_user_reads().items()}
    writes = {k: round(v) for k, v in per_user_writes().items()}
    reads_total = sum(reads.values()) * USERS + CRON_RUNS * CRON_READS_PER_RUN
    writes_total = sum(writes.values()) * USERS + CRON_RUNS * CRON_WRITES_PER_RUN
    base_reads, base_writes = baseline_totals()
    top_reads = sorted(reads.items(), key=lambda kv: kv[1], reverse=True)
    report = {
        'users': USERS,
        'measurement': 'document-delivery estimate, not D1 rows_read telemetry',
        'assumptions': '20 opens: 1 cache miss, 19 cache hits; 60 friends; 2 explicit Home refreshes; cache not evicted/invalidated otherwise; notification list: 5 opens + 5 older pages charged as misses, 200 sender lookups, 1 explicit mark-all of 40 unread; inbox: 10 opens as misses + 20 visible push refreshes + 2 pulls + 5 older pages, 11 docs each',
        'cold_open_documents_with_60_friends': HOME_READS + BADGE_READS + 60,
        'cached_open_incremental_home_and_badge_documents': 0,
        'cached_open_auth_allowance': 1,
        'all_20_opens_cache_miss_daily_reads': (sum(reads.values()) - reads['app_opens'] + 20 * (HOME_READS + BADGE_READS + 60)) * USERS + CRON_RUNS * CRON_READS_PER_RUN,
        'reads_per_user_per_day': reads,
        'reads_per_user_total': sum(reads.values()),
        'writes_per_user_per_day': writes,
        'writes_per_user_total': sum(writes.values()),
        'top_read_consumers': [{'action': k, 'reads': v} for k, v in top_reads[:3]],
        'cron_runs_per_day': CRON_RUNS,
        'daily_reads': round(reads_total),
        'daily_writes': round(writes_total),
        'read_limit': 100_000,
        'write_limit': 5_000,
        'within_read_target': reads_total < 100_000,
        'within_write_target': writes_total < 5_000,
        'baseline_daily_reads': base_reads,
        'baseline_daily_writes': base_writes,
        'reads_removed_pct': round(100 * (base_reads - reads_total) / base_reads, 1) if base_reads else 0,
        'writes_removed_pct': round(100 * (base_writes - writes_total) / base_writes, 1) if base_writes else 0,
    }
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report['within_read_target'] and report['within_write_target'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
