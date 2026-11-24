"""STEP 6 + STEP 7 + QUOTA wiring guards: media is cached once, nothing refreshes itself.

Asserted contract:
  * STEP 6 - a photo/video URL is fetched once, then served from local disk.
  * STEP 7 - story, notification and chat list data is read only when the user opens the screen or
    pulls to refresh; no timers, no polling, no live list listeners.
  * QUOTA - presence/typing/live heartbeats do not write on a short timer, badges are one-shot and
    pushes bump them locally.
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
        self.assertIn('MediaDiskCache.uriForPlayback(this, stream)',
                      (JAVA / 'story/StoryViewerActivity.java').read_text())
        self.assertIn('MediaDiskCache.uriForPlayback(this, stream)',
                      (JAVA / 'post/FullscreenMediaActivity.java').read_text())

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

    def test_nothing_is_downloaded_at_launch(self):
        cache = (JAVA / 'reels/ReelVideoCache.java').read_text()
        self.assertNotIn('warmStartupReels', cache)
        self.assertNotIn('collection("reels")', cache)


class NoBackgroundRefresh(unittest.TestCase):
    def test_no_eight_second_polling_loops(self):
        for file in ['main/HomeFragment.java', 'reels/ReelsFragment.java']:
            source = (JAVA / file).read_text()
            self.assertNotIn('postDelayed(this,8000)', source, file)
            self.assertNotIn('postDelayed(this, 8000L)', source, file)

    def test_list_screens_hold_no_query_listener(self):
        for file in ['main/NotificationsFragment.java', 'chat/ConversationsActivity.java',
                     'main/MainActivity.java']:
            source = (JAVA / file).read_text()
            self.assertNotIn('addSnapshotListener', source, file)

    def test_chat_list_reads_once_and_supports_pull_to_refresh(self):
        source = (JAVA / 'chat/ConversationsActivity.java').read_text()
        self.assertIn('inboxSwipe', source)
        self.assertIn('loadInboxPage(true,true)', source)
        self.assertIn('page.getCachedOrFetch(forceRefresh)', source)

    def test_home_lists_are_one_shot(self):
        home = (JAVA / 'main/HomeFragment.java').read_text()
        self.assertNotIn('friendsReg', home)
        self.assertNotIn('livesReg', home)
        self.assertIn('FRIEND_FACT_TTL_MS=300_000L', home)
        self.assertNotIn('OnDemandLoads.FRIEND_GRAPH_LIMIT',home)

    def test_badges_are_bounded_and_pushed(self):
        source = (JAVA / 'main/MainActivity.java').read_text()
        badges = source.split('private void startBadgeListeners()', 1)[1].split('private void ', 1)[0]
        self.assertEqual(0, badges.count('addSnapshotListener'), 'badges must not listen')
        self.assertIn('onPushArrived', source)
        self.assertIn('MainActivity.onPushArrived(', (JAVA / 'push/FriendMessagingService.java').read_text())

    def test_presence_and_typing_do_not_poll(self):
        # Presence was retired from the client: no watcher, no heartbeat timer to leak writes.
        self.assertFalse((JAVA / 'core/Presence.java').exists())
        app = (JAVA).rglob('*.java')
        for path in app:
            text = path.read_text(errors='ignore')
            self.assertNotIn('Presence.watch(', text, str(path))
            self.assertNotIn('Presence.setTyping(', text, str(path))

    def test_live_host_does_not_write_on_a_short_timer(self):
        live = (JAVA / 'live/LiveActivity.java').read_text()
        self.assertNotIn('postDelayed(hostHeartbeatTask', live)
        self.assertIn('publishHostHeartbeat()', live)


if __name__ == '__main__':
    unittest.main()
