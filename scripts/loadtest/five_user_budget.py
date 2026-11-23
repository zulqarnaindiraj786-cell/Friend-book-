#!/usr/bin/env python3
"""Five-user daily D1 read/write budget, derived from the limits in this codebase.

No network access. Every read cost is a `limit(...)`, a badge window constant or a documented
single-document read in the Android/Worker code; nothing is invented. Run:

    python3 -m scripts.loadtest.five_user_budget

Exit code 0 only while daily reads < 100,000 and daily writes < 5,000. The report also prints the
baseline this work replaced, so the delta is auditable instead of claimed.
"""
import json

USERS = 5

# ---------------------------------------------------------------- activity per user per day
ACTIVITY = {
    'app_opens': 20,             # cold/warm opens that paint Home + the bottom-tab badges
    'feed_pages': 20,            # further pages after the first (HomeFragment PAGE = 20)
    'story_viewer_opens': 10,
    'notification_opens': 5,
    'chat_list_opens': 10,
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
# HomeFragment: posts limit(20), stories limit(10), lives limit(25), notes/users limit(30), me doc 1
# MainActivity badges: notifications BADGE_WINDOW(32), chats CHAT_BADGE_LIMIT(20), friend_requests
#   BADGE_WINDOW(32), reels limit(16) — all one-shot now, so a change re-reads nothing.
HOME_READS = 20 + 10 + 25 + 30 + 1
BADGE_READS = 32 + 20 + 32 + 16
FRIEND_GRAPH_TTL_REFRESHES = 4          # cached 15 minutes

READ_COST = {
    'app_open_home_and_badges': HOME_READS + BADGE_READS,   # 118
    'feed_listener_redeliveries': 2 * 20,                   # the feed listener still re-reads its page
    'friend_graph_refresh': 60,                             # cached 15 min; ~4 reads of a 60-friend graph
    'feed_page': 20,
    'story_viewer_open': 10,
    'notification_open': 20,
    'chat_list_open': 10 + 20,                              # inbox limit(10) + users strip (cached 10 min)
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
        'app_opens': a['app_opens'] * c['app_open_home_and_badges'],
        'feed_listener': a['app_opens'] * c['feed_listener_redeliveries'],
        'friend_graph': a['app_opens'] * 0 + FRIEND_GRAPH_TTL_REFRESHES * c['friend_graph_refresh'],
        'feed_pages': a['feed_pages'] * c['feed_page'],
        'story_viewer': a['story_viewer_opens'] * c['story_viewer_open'],
        'notifications': a['notification_opens'] * c['notification_open'],
        'chat_list': a['chat_list_opens'] * c['chat_list_open'],
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
