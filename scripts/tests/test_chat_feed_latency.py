from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'

class ChatFeedLatencyTest(unittest.TestCase):
    def test_message_lanes_and_direct_push(self):
        store = (ROOT / 'd1/documents/D1DocumentStore.java').read_text()
        self.assertIn('runAsync(chatWriteExecutor, task)', store)
        self.assertIn('ListenerPollingPolicy.isChatMessages(path) ? chatReadExecutor', store)
        push = (ROOT / 'push/FCMPush.java').read_text()
        self.assertIn('invoke(directPeer,', push)
        self.assertIn('PushFanoutWorker.enqueue("chat", chatId, messageId)', push)

    def test_notification_fallback_dedupes_and_ignores_cache(self):
        push = (ROOT / 'push/PushManager.java').read_text()
        self.assertIn('doc.getMetadata().isFromCache()', push)
        self.assertIn('"notification_" + doc.getId()', push)
        self.assertIn('seen.contains(id)', push)

    def test_home_tab_switch_does_not_refresh_sponsored_slots(self):
        home = (ROOT / 'main/HomeFragment.java').read_text()
        hidden = home.split('void onHiddenChanged', 1)[1].split('private void refreshPostsIfNeeded', 1)[0]
        self.assertNotIn('refreshSponsoredAds', hidden)
        self.assertIn('view.getScrollState()!=RecyclerView.SCROLL_STATE_IDLE', home)
        feed = (ROOT / 'post/FeedAdapter.java').read_text()
        self.assertIn('isCurrentFeedSlotAd(slotOrdinal, currentNativeAd)', feed)
        self.assertIn('return id & Long.MAX_VALUE', feed)

    def test_delayed_push_fallback_is_foreground_and_chat_scoped(self):
        store = (ROOT / 'd1/documents/D1DocumentStore.java').read_text()
        fallback = store.split('private void pollVisibleChatFallback()', 1)[1].split('private void pollActiveListeners()', 1)[0]
        self.assertIn('App.isForeground()', fallback)
        self.assertIn('periodicAllowed(now)', fallback)
        self.assertIn('entry.active && (chat || inbox)', fallback)
        self.assertIn('chatFallbackDue(now - entry.lastAttemptMs, chat)', fallback)
        self.assertIn('scheduleQueryRead(entry)', fallback)
