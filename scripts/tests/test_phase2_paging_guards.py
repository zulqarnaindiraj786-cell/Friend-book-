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
        for token in ['.limitToLast(15)','Query.Direction.DESCENDING).startAfter(historyCursor).limit(15).get()',
                      'chatGeneration!=historyGeneration','generation!=historyGeneration']:
            self.assertIn(token,source)
    def test_reel_fallback_cannot_prepare_offscreen(self):
        source=(JAVA/'reels/ReelsFragment.java').read_text()
        fallback=source.split('private void prepareWithRawUrlFallback',1)[1].split('releasePlayerOnly()',1)[0]
        for token in ['!userRequestedVideo','!wantPlay','getBindingAdapterPosition()!=playingPos']:
            self.assertIn(token,fallback)
        self.assertNotIn('ReelVideoCache.prefetchWindow(',source)
        self.assertNotIn('ReelVideoCache.prefetchSingle(', (JAVA/'post/FeedAdapter.java').read_text())
    def test_comments_do_not_expand_frozen_map(self):
        source=(JAVA/'post/CommentsEngine.java').read_text()
        self.assertIn('handle.query.limit(50).addSnapshotListener',source)
        self.assertIn('query.startAfter(cursor).limit(50).get()',source)
        self.assertIn('patch.put("lastComment",commentMap)',source)
        self.assertNotIn('dot.put("commentsMap." + c.id + ".',source)
