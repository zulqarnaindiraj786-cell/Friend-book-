#!/usr/bin/env python3
"""Five-user daily D1 budget derived from this codebase's query shapes.

No network access. Every number is a stated assumption multiplied by a cost that
exists in the SQL: rows read per list page equal the page limit, a document read
is one row, and each document write lands in both the canonical table and the
legacy mirror (two row writes) unless the caller uses a single-table path.

Run: python3 -m scripts.loadtest.five_user_budget
"""
import json

USERS = 5

# Per-user, per-day activity assumptions.
ACTIVITY = {
    'app_opens': 20,             # cold or warm opens that paint Home
    'feed_pages': 40,            # initial page + pagination, mostly cache hits
    'chat_sent': 30,
    'chat_received_while_open': 45,   # push-delivered, read only when the screen is open
    'chat_list_opens': 10,
    'notification_opens': 5,
    'profile_opens': 20,
    'likes_reactions': 50,
    'posts_comments': 5,
    'settings_saves': 5,
    'live_events': 10,           # live start/end/viewer change heartbeats
}

# Rows read per action, from the SQL limits and the on-demand policies.
READ_COST = {
    'app_open': 30 + 32 + 100 + 32 + 1 + 1,   # feed page 30, badges 32/100/32, me doc, config doc
    'feed_page': 40,
    'chat_send': 12,             # room auth + membership + room document
    'chat_received': 34,         # last 30 messages + room document
    'chat_list': 100,            # capped chat badge window
    'notification_open': 32,
    'profile_open': 3,
}

# Row writes per user action. Every document write touches two tables today.
WRITE_COST = {
    'message': 2,                # message row in canonical + mirror
    'chat_room_update': 2,       # unread/lastMessage preview
    'notification': 2,
    'reaction': 2,
    'receipt': 2,
    'post_or_comment': 2,
    'settings': 2,
    'live_heartbeat': 2,
}

CRON_RUNS = 96                   # every 15 minutes
CRON_READS_PER_RUN = 5           # bounded maintenance lookups
CRON_WRITES_PER_RUN = 0          # writes only when something is actually stale


def per_user_reads():
    a, c = ACTIVITY, READ_COST
    return {
        'app_opens': a['app_opens'] * c['app_open'],
        'feed_pages': a['feed_pages'] * c['feed_page'],
        'chat_sends': a['chat_sent'] * c['chat_send'],
        'chat_received': a['chat_received_while_open'] * c['chat_received'],
        'chat_list': a['chat_list_opens'] * c['chat_list'],
        'notifications': a['notification_opens'] * c['notification_open'],
        'profiles': a['profile_opens'] * c['profile_open'],
    }


def per_user_writes():
    a, w = ACTIVITY, WRITE_COST
    return {
        'messages': a['chat_sent'] * w['message'],
        'chat_previews': a['chat_sent'] * w['chat_room_update'],
        'notifications': a['chat_sent'] * w['notification'],
        'reactions': a['likes_reactions'] * w['reaction'],
        'receipts': a['chat_received_while_open'] * 0.5 * w['receipt'],
        'posts_comments': (a['posts_comments'] * 2) * w['post_or_comment'],
        'settings': a['settings_saves'] * w['settings'],
        'live': a['live_events'] * w['live_heartbeat'],
    }


def main():
    reads = {key: round(value) for key, value in per_user_reads().items()}
    writes = {key: round(value) for key, value in per_user_writes().items()}
    reads_total = sum(reads.values()) * USERS + CRON_RUNS * CRON_READS_PER_RUN
    writes_total = sum(writes.values()) * USERS + CRON_RUNS * CRON_WRITES_PER_RUN
    report = {
        'users': USERS,
        'reads_per_user_per_day': reads,
        'reads_per_user_total': sum(reads.values()),
        'writes_per_user_per_day': writes,
        'writes_per_user_total': sum(writes.values()),
        'cron_runs_per_day': CRON_RUNS,
        'daily_reads': round(reads_total),
        'daily_writes': round(writes_total),
        'read_limit': 100_000,
        'write_limit': 5_000,
        'within_read_target': reads_total < 100_000,
        'within_write_target': writes_total < 5_000,
    }
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report['within_read_target'] and report['within_write_target'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
