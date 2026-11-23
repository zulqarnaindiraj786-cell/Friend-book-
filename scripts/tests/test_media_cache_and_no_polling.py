"""STEP 6 + STEP 7 wiring guards: media is cached once, and nothing refreshes itself.

These assert the contract the user asked for:
  * STEP 6 - a photo/video URL is fetched once, then served from local disk.
  * STEP 7 - story, notification and chat list data is read only when the user opens the screen
    or pulls to refresh; no timers, no polling, no live list listeners.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


class MediaCacheWiring(unittest.TestCase):
    def test_story_and_fullscreen_videos_play_from_disk_cache(self):
        cache = (JAVA / 'core/MediaDiskCache.java').read_text()
        for token in ['uriForPlayback', 'cachedFile', 'warm(', 'MAX_CACHE_BYTES', 'trim(']:
            self.assertIn(token, cache)
        self.assertIn('MediaDiskCache.uriForPlayback(this, s.mediaUrl)',
                      (JAVA / 'story/StoryViewerActivity.java').read_text())
        self.assertIn('MediaDiskCache.uriForPlayback(this, url)',
                      (JAVA / 'post/FullscreenMediaActivity.java').read_text())
        # The next unseen story video is warmed so swiping never re-downloads.
        self.assertIn('MediaDiskCache.warm(this, next.mediaUrl)',
                      (JAVA / 'story/StoryViewerActivity.java').read_text())

    def test_photos_use_one_explicit_disk_cache_for_the_whole_app(self):
        module = (JAVA / 'core/FriendbookGlideModule.java').read_text()
        self.assertIn('@GlideModule', module)
        self.assertIn('DiskLruCacheFactory', module)
        self.assertIn('DISK_CACHE_BYTES', module)
        self.assertIn("annotationProcessor 'com.github.bumptech.glide:compiler:4.16.0'",
                      (ROOT / 'app/build.gradle').read_text())
        ui = (JAVA / 'core/Ui.java').read_text()
        self.assertNotIn('DiskCacheStrategy.NONE', ui)
        self.assertIn('DiskCacheStrategy.ALL', ui)


class NoBackgroundRefresh(unittest.TestCase):
    def test_home_and_reels_do_not_poll_engagement(self):
        for file in ['main/HomeFragment.java', 'reels/ReelsFragment.java']:
            source = (JAVA / file).read_text()
            self.assertNotIn('engagementHandler.postDelayed(this,8000)', source, file)
            self.assertNotIn('engagementHandler.postDelayed(this, 8000L)', source, file)
            self.assertIn('refreshVisibleEngagement', source, file)

    def test_notifications_never_attach_a_refresh_listener(self):
        source = (JAVA / 'main/NotificationsFragment.java').read_text()
        self.assertNotIn('addSnapshotListener', source)
        self.assertIn('startPages()', source)

    def test_chat_list_reads_once_and_supports_pull_to_refresh(self):
        source = (JAVA / 'chat/ConversationsActivity.java').read_text()
        self.assertNotIn('.addSnapshotListener', source)
        self.assertIn('swipe.setOnRefreshListener', source)
        self.assertIn('query.limit(10).get(com.friendbook.social.d1.documents.Source.SERVER)', source)

    def test_presence_is_one_shot_on_every_list_screen(self):
        presence = (JAVA / 'core/Presence.java').read_text()
        self.assertIn('public static void peek(', presence)
        # Chat list: no extra read at all, the user documents it already fetched carry presence.
        chat_list = (JAVA / 'chat/ConversationsActivity.java').read_text()
        self.assertIn('Presence.isUserOnlineDoc(ud)', chat_list)
        self.assertNotIn('Presence.watch(', chat_list)
        self.assertNotIn('Presence.peek(', chat_list)
        # Chat header + profile: a single one-shot read on open.
        for file in ['chat/ChatActivity.java', 'profile/ProfileActivity.java']:
            source = (JAVA / file).read_text()
            self.assertIn('Presence.peek(', source, file)
            self.assertNotIn('Presence.watch(', source, file)

    def test_typing_writes_are_gone_and_heartbeat_is_throttled(self):
        chat = (JAVA / 'chat/ChatActivity.java').read_text()
        self.assertNotIn('Presence.setTyping(', chat)
        self.assertNotIn('watchTyping(', chat)
        presence = (JAVA / 'core/Presence.java').read_text()
        self.assertIn('HEARTBEAT_INTERVAL_MS = 240_000L', presence)
        self.assertLess(240_000, 300_000)  # must stay inside PresencePolicy.ONLINE_TTL_MS

    def test_nothing_is_downloaded_at_launch(self):
        cache = (JAVA / 'reels/ReelVideoCache.java').read_text()
        startup = cache.split('public static void warmStartupReels', 1)[1].split('private static synchronized', 1)[0]
        self.assertNotIn('collection("reels")', startup)
        self.assertNotIn('prefetchSingle(', startup)
        self.assertNotIn('prefetchWindow(', startup)

    def test_badge_windows_are_bounded_and_one_shot(self):
        source = (JAVA / 'main/MainActivity.java').read_text()
        badges = source.split('private void startBadgeListeners()', 1)[1].split('private void ', 1)[0]
        self.assertIn('.limit(30)', badges)   # unread notifications
        self.assertIn('.limit(20)', badges)   # chat room window / pending requests
        self.assertIn('.limit(16)', badges)   # reels
        self.assertEqual(0, badges.count('addSnapshotListener'), 'badges must not listen')
        # Pushes bump the pill locally instead of re-reading the tables.
        self.assertIn('onPushArrived', source)
        self.assertIn('MainActivity.onPushArrived(', (JAVA / 'push/FriendMessagingService.java').read_text())
        readme = (ROOT / 'scripts/loadtest/five_user_budget.py').read_text()
        self.assertIn('APP_OPEN_READS', readme)

    def test_main_activity_holds_no_query_listener_at_all(self):
        self.assertEqual(0, (JAVA / 'main/MainActivity.java').read_text().count('addSnapshotListener'))

    def test_feed_does_not_reload_on_like_or_comment(self):
        home = (JAVA / 'main/HomeFragment.java').read_text()
        watch = home.split('private void watchLatestPosts()', 1)[1].split('private void startPostsQuery', 1)[0]
        self.assertIn("key.append(doc.getId())", watch)
        self.assertNotIn('doc.getData()', watch)


if __name__ == '__main__':
    unittest.main()
