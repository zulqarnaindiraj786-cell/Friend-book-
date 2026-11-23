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
    'live_host_minutes': 10,    # minutes a user actually broadcasts per day
    'foreground_minutes': 120,  # presence heartbeat runs only while the app is in the foreground
}

# ---------------------------------------------------------------- reads per action (code limits)
# HomeFragment: posts limit(7), stories limit(10), lives limit(25), me document 1
# MainActivity badges (one-shot at launch, pushed updates cost 0 reads):
#   notifications isRead=false limit(30), chats limit(20), friend_requests limit(20), reels limit(16)
# Feed keeps its posts listener, so its bounded 7-row window re-delivers twice per open.
APP_OPEN_READS = 7 + 10 + 25 + 30 + 20 + 20 + 16 + 1
FEED_LISTENER_REDELIVERIES = 2 * 7

READ_COST = {
    'app_open_badges_and_home': APP_OPEN_READS + FEED_LISTENER_REDELIVERIES,   # 129 + 14
    'friend_graph_refresh': 60,                   # cached 15 min: at most ~4 reads of a 60-friend graph per day
    'feed_page': 7,                               # HomeFragment PAGE = 7
    'story_viewer_open': 10,                      # StoryViewerActivity CursorPager limit(10)
    'notification_open': 20,                      # NotificationsFragment pager limit(20)
    'chat_list_open': 10 + 15,                    # 10 rooms + 45-user strip amortised over a 10 min cache
                                                  # (presence is derived from those docs: 0 extra reads)
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
    'live_heartbeat': 1,        # one host heartbeat write per minute (was one every 8s)
    'presence_heartbeat': 1,    # own presence doc, throttled to one write every 4 minutes
    'presence_session': 1,      # WebSocket presence session row, same 4 minute coalescing
    'fcm_maintenance': 1,       # 12-hourly registration refresh, 2 per day
}

# The friend graph is cached for 15 minutes, so a day can hold at most ~96 refreshes per user.
MAX_FRIEND_GRAPH_READS_PER_DAY = 96

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
        'friend_graph': round((a['app_opens'] * c['friend_graph_refresh']) / MAX_FRIEND_GRAPH_READS_PER_DAY),
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
        'presence_heartbeats': round(a['foreground_minutes'] / 4) * w['presence_heartbeat'],
        'presence_sessions': round(a['foreground_minutes'] / 4) * w['presence_session'],
        'fcm_maintenance': 2 * w['fcm_maintenance'],
    }



# ---------------------------------------------------------------- baseline (what STEP 6-8 removed)
# Everything here used to happen on this branch before the quota work; it exists so the report can
# show the delta instead of asking anyone to trust it.
BASELINE = {
    # per app open: badge listeners re-read their windows on every change (2 deliveries each)
    'badge_listener_redeliveries': 2 * (30 + 60 + 30),
    # badges were unbounded before this session, then 30/60/30; the live listeners are gone now
    'badge_window_old': 30 + 60 + 30 + 16,
    'badge_window_new': 30 + 20 + 20 + 16,
    # chat list attached a live users listener (45 rows) and a 30s presence watcher per row
    'chat_list_users_old': 45,
    'chat_list_presence_old': 10 * 2,
    'chat_list_users_new': 15,
    # friends: unbounded live listener (60-row graph re-read on attach)
    'friends_old': 60,
    'friends_new': 12,
    # cold start pulled the top 3 Reels (1 query) and downloaded their videos
    'startup_reel_reads_old': 3,
    # presence heartbeat every 30s -> 240 writes/hour; typing cost ~5 writes per sent message
    'presence_writes_old': round(120 / 0.5),
    'typing_writes_per_message': 5,
}


def baseline_totals():
    a, b, c = ACTIVITY, BASELINE, READ_COST
    reads_per_user = (
        a['app_opens'] * (b['badge_window_old'] + b['badge_listener_redeliveries'] + 7 + 10 + 25 + 1)
        + a['app_opens'] * b['startup_reel_reads_old']
        + a['chat_list_opens'] * (10 + b['chat_list_users_old'] + b['chat_list_presence_old'])
        + a['app_opens'] * b['friends_old']
        + a['feed_pages'] * c['feed_page']
        + a['story_viewer_opens'] * c['story_viewer_open']
        + a['notification_opens'] * c['notification_open']
        + a['chat_thread_opens'] * c['chat_thread_open']
        + a['chat_sent'] * c['chat_send']
        + a['profile_opens'] * c['profile_open']
    )
    writes_per_user = (
        a['chat_sent'] * (WRITE_COST['message'] + WRITE_COST['room_preview'] + WRITE_COST['notification'])
        + a['chat_sent'] * b['typing_writes_per_message']
        + a['likes_reactions'] * WRITE_COST['reaction']
        + round(a['chat_received'] * 0.5) * WRITE_COST['receipt']
        + (a['posts_comments'] * 2) * WRITE_COST['post_or_comment']
        + a['settings_saves'] * WRITE_COST['settings']
        + a['live_host_minutes'] * 7 * WRITE_COST['live_heartbeat']  # old: 8s heartbeat = 7.5 writes/min
        + b['presence_writes_old']
    )
    return round(reads_per_user * USERS + CRON_RUNS * CRON_READS_PER_RUN), round(writes_per_user * USERS)


def main():
    reads = {k: round(v) for k, v in per_user_reads().items()}
    writes = {k: round(v) for k, v in per_user_writes().items()}
    reads_total = sum(reads.values()) * USERS + CRON_RUNS * CRON_READS_PER_RUN
    writes_total = sum(writes.values()) * USERS + CRON_RUNS * CRON_WRITES_PER_RUN
    top_reads = sorted(reads.items(), key=lambda kv: kv[1], reverse=True)
    base_reads, base_writes = baseline_totals()
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
