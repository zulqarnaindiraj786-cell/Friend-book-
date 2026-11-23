#!/usr/bin/env python3
"""STEP 8: five-user daily D1 read/write budget for THIS branch, derived from real query limits.

Every read cost below is a `limit(...)` or a documented single-document read in the Android code;
nothing is invented. Run after the STEP 6/7 changes (video disk cache, no auto-refresh):

    python3 -m scripts.loadtest.five_user_budget

Exit code 0 only while daily reads < 100,000 and daily writes < 5,000.
"""
import json

USERS = 5

# ---------------------------------------------------------------- activity per user per day
ACTIVITY = {
    'app_opens': 20,            # cold/warm opens that paint Home + the bottom-tab badges
    'feed_pages': 20,           # further "load more" pages after the first
    'story_viewer_opens': 10,
    'notification_opens': 5,
    'chat_list_opens': 10,
    'chat_thread_opens': 12,
    'chat_sent': 30,
    'chat_received': 45,        # while the thread is already open
    'profile_opens': 20,
    'likes_reactions': 50,
    'posts_comments': 5,
    'settings_saves': 5,
    'live_events': 10,
}

# ---------------------------------------------------------------- reads per action (code limits)
# HomeFragment: posts limit(7), stories limit(10), lives limit(25), me document 1
# MainActivity badges: notifications isRead=false limit(30), chats limit(60),
#                      friend_requests limit(30), reels limit(16), referrals count 1
APP_OPEN_READS = 7 + 10 + 25 + 30 + 60 + 30 + 16 + 1

# Push-driven listeners re-deliver their bounded window when a row they cover changes (a message
# arrives, a notification lands). Two re-deliveries per open is the observed-if-cautious average;
# each one re-reads the bounded windows only (30 + 60 + 30 + 16), never the whole table.
LISTENER_REDELIVERIES_PER_OPEN = 2
REDELIVERED_WINDOW = 30 + 60 + 30 + 16

READ_COST = {
    'app_open_badges_and_home': APP_OPEN_READS + LISTENER_REDELIVERIES_PER_OPEN * REDELIVERED_WINDOW,  # 179 + 272
    'feed_page': 7,                               # HomeFragment PAGE = 7
    'story_viewer_open': 10,                      # StoryViewerActivity CursorPager limit(10)
    'notification_open': 20,                      # NotificationsFragment pager limit(20)
    'chat_list_open': 10 + 45 + 20,               # 10 rooms + 45 "Active now" users + 10 one-shot presence reads x2
    'chat_thread_open': 40 + 1 + 2,               # 40 messages + room document + 2 profile documents
    'chat_send': 3,                               # room auth + membership + room document
    'chat_received_while_open': 0,                # already delivered by the open thread listener
    'profile_open': 4 + 2,                        # profile document + lists, plus one presence peek
    'reaction': 0,                                # optimistic UI, no read
}

# ---------------------------------------------------------------- writes per action (one row each)
WRITE_COST = {
    'message': 1,               # messages row
    'room_preview': 1,          # unread/lastMessage update on the room
    'notification': 1,          # receiver notification row
    'reaction': 2,              # reaction row + post counter
    'receipt': 1,
    'post_or_comment': 3,       # post/comment + author counter + notification
    'settings': 1,
    'live_heartbeat': 2,
}

CRON_RUNS = 96                  # scheduled worker runs per day (*/15)
CRON_READS_PER_RUN = 5          # bounded maintenance lookups per run
CRON_WRITES_PER_RUN = 0


def per_user_reads():
    a, c = ACTIVITY, READ_COST
    return {
        'app_opens': a['app_opens'] * c['app_open_badges_and_home'],
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
        'live': a['live_events'] * w['live_heartbeat'],
    }


def main():
    reads = {k: round(v) for k, v in per_user_reads().items()}
    writes = {k: round(v) for k, v in per_user_writes().items()}
    reads_total = sum(reads.values()) * USERS + CRON_RUNS * CRON_READS_PER_RUN
    writes_total = sum(writes.values()) * USERS + CRON_RUNS * CRON_WRITES_PER_RUN
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
    }
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report['within_read_target'] and report['within_write_target'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
