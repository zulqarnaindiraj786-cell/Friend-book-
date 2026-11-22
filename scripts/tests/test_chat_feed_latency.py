from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'

class ChatFeedLatencyTest(unittest.TestCase):
    def test_message_lanes_and_direct_push(self):
        store = (ROOT / 'd1/documents/D1DocumentStore.java').read_text()
        self.assertIn('runAsync(chatWriteExecutors[Math.floorMod(roomPath.hashCode(), chatWriteExecutors.length)], task)', store)
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
        self.assertIn('entry.active && chat', fallback)
        self.assertIn('chatFallbackDue(now - entry.lastAttemptMs, true)', fallback)
        self.assertIn('scheduleQueryRead(entry)', fallback)

    def test_feed_first_page_does_not_queue_behind_other_reads(self):
        query = (ROOT / 'd1/documents/Query.java').read_text()
        self.assertIn('"posts".equals(parentPath) && feedEngagement', query)
        self.assertIn('startAfterValues == null', query)
        self.assertIn('firstFeedPage ? firestore.runFeedFirstPage(fetch)', query)
        store = (ROOT / 'd1/documents/D1DocumentStore.java').read_text()
        self.assertIn('runAsync(feedFirstPageExecutor, task)', store)
        batch = (ROOT / 'd1/documents/WriteBatch.java').read_text()
        self.assertIn('firestore.runChatSend(path, task)', batch)

    def test_notification_push_coalescing_is_account_scoped_and_fixed_time(self):
        store = (ROOT / 'd1/documents/D1DocumentStore.java').read_text()
        code = store.split('public void refreshInboxFromPush(', 1)[1].split('private final java.util.concurrent.atomic.AtomicLong', 1)[0]
        self.assertIn('pendingNotificationPushes.begin(actor)', code)
        self.assertIn('pendingNotificationPushes.finish(actor)', code)
        self.assertIn('actor.equals(authenticatedUid())', code)
        self.assertIn('NOTIFICATION_PUSH_COALESCE_MS', code)
        self.assertIn('notifyCollectionListeners("friend_requests")', code)
        self.assertIn('notifyCollectionListeners("friends")', code)

    def test_committed_batches_refresh_each_collection_once(self):
        store = (ROOT / 'd1/documents/D1DocumentStore.java').read_text()
        committed = store.split('void notifyCommittedPaths(', 1)[1].split('public Task<org.json.JSONObject> signalCall', 1)[0]
        self.assertIn('for (String path : committed.documents()) invalidateDoc(path)', committed)
        self.assertIn('for (String path : committed.documents()) notifyOnlyDocListeners(path)', committed)
        self.assertIn('for (String parent : committed.collections()) notifyCollectionListeners(parent)', committed)
        for name, transport in [('WriteBatch.java', 'D1DocumentTransport.batch(ops,chatSend)'),
                                ('Transaction.java', 'D1DocumentTransport.transaction(ops)')]:
            code = (ROOT / 'd1/documents' / name).read_text()
            self.assertLess(code.index(transport), code.index('firestore.notifyCommittedPaths(ops)'))

    def test_slow_feed_prioritizes_visible_cards_and_keeps_tail_pagination(self):
        home = (ROOT / 'main/HomeFragment.java').read_text()
        section = home.split('FastCache.NetTier tier = FastCache.netTier(a);', 1)[1].split('rv.setBackgroundColor', 1)[0]
        for marker in ('NET_2G', 'NET_3G', 'Prefs.dataSaver(a)', 'Prefs.turbo2g(a)',
                       'setItemPrefetchEnabled(!constrainedFeed)',
                       'dy>0 && isResumed() && !isHidden()', 'getItemCount()-2', 'loadMore()'):
            self.assertIn(marker, section)
