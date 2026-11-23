from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class InboxOnDemandCache(unittest.TestCase):
    def test_push_and_pull_bypass_cache_but_open_and_older_pages_reuse_it(self):
        s=(JAVA/'chat/ConversationsActivity.java').read_text()
        self.assertIn('loadInboxPage(boolean reset) { loadInboxPage(reset,false); }',s)
        self.assertIn('pushPending=false;loadInboxPage(true,true)',s)
        self.assertIn('inboxSwipe.setOnRefreshListener(()->loadInboxPage(true,true))',s)
        self.assertIn('page.getCachedOrFetch(forceRefresh)',s)
        self.assertIn('query.limit(INBOX_PAGE_SIZE+1)',s)
        self.assertNotIn('refreshCachedHead',s)
        self.assertNotIn('addSnapshotListener',s)
    def test_cached_page_restores_cursor_without_clobbering_handoff(self):
        s=(JAVA/'chat/ConversationsActivity.java').read_text()
        self.assertIn('if(!reset || inboxCursor==null)',s)
        self.assertIn('previous.room.lastMessageTime>r.room.lastMessageTime',s)
        apply=s.split('java.util.function.Consumer<com.friendbook.social.d1.documents.QuerySnapshot> apply=',1)[1].split('java.util.function.Consumer<Exception>',1)[0]
        self.assertIn('applyInboxHandoff(me)',apply)
        self.assertIn('schedulePendingPush()',apply)
        self.assertIn('if(!snap.getMetadata().isFromCache())\n                    CallActivity.maybeHandleIncomingFromRoomDoc',apply)
    def test_every_collection_invalidates_without_reading_hidden_lists(self):
        s=(JAVA/'d1/documents/D1DocumentStore.java').read_text()
        notify=s.split('void notifyCollectionListeners(String parentPath)',1)[1].split('for (QueryListenerEntry',1)[0]
        self.assertIn('QueryCachePolicy.preferencesFor(parentPath)',notify)
        self.assertIn('authenticatedUid()+"|"+parentPath',notify)
        self.assertNotIn('.get(Source.SERVER)',notify)
        self.assertNotIn('"groups".equals(parentPath) || "friends".equals(parentPath)',notify)
