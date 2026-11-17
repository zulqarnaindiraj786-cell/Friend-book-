"""Source contract guards, not device/network behavior tests."""
from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class PagingGuards(unittest.TestCase):
    def test_watch_can_reach_next_page(self):
        source=(JAVA/'post/WatchActivity.java').read_text()
        for token in ['adapter.showFooter = true','onScrolled','query.limit(10).get()','query.startAfter(cursor)']:
            self.assertIn(token,source)
        self.assertNotIn('.addSnapshotListener',source)
    def test_open_chat_window_and_lifecycle(self):
        source=(JAVA/'chat/ChatActivity.java').read_text()
        # Chat pages older history out of D1 on a keyset cursor, so the guard pins the cursor
        # arithmetic rather than the Firestore query object that used to carry it.
        for token in ['.limit(20).build()','whereLessThan("createdAtMs", beforeMs)',
                      'chatGeneration!=historyGeneration','generation!=historyGeneration']:
            self.assertIn(token,source)
    def test_reel_fallback_cannot_prepare_offscreen(self):
        source=(JAVA/'reels/ReelsFragment.java').read_text()
        fallback=source.split('private void prepareWithRawUrlFallback',1)[1].split('releasePlayerOnly()',1)[0]
        for token in ['!wantPlay','getBindingAdapterPosition()!=playingPos']:
            self.assertIn(token,fallback)
        self.assertNotIn('ReelVideoCache.prefetchWindow(',source)
        self.assertNotIn('ReelVideoCache.prefetchSingle(', (JAVA/'post/FeedAdapter.java').read_text())
    def test_comments_do_not_expand_frozen_map(self):
        source=(JAVA/'post/CommentsEngine.java').read_text()
        self.assertIn('handle.query.limit(50).get()',source)
        self.assertNotIn('addSnapshotListener',source)
        self.assertIn('query.startAfter(cursor).limit(50).get()',source)
        # The frozen commentsMap must never be expanded. The send path writes the comment document
        # and the post's preview separately now, so the guard checks that split rather than the
        # Firestore patch map that used to carry both.
        self.assertIn('Fb.map("lastComment", commentMap, "commentsStorageVersion", 2)',source)
        self.assertIn('CloudflareStore.increment("comments", 1)',source)
        self.assertNotIn('dot.put("commentsMap." + c.id + ".',source)
