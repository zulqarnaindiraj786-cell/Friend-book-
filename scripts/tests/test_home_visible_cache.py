"""Regression guards for the user's ten-post / no unseen download contract."""
from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class HomeVisibleCache(unittest.TestCase):
    def test_discovery_requires_viewport_and_no_reels_query(self):
        home=(JAVA/'main/HomeFragment.java').read_text()
        adapter=(JAVA/'post/FeedAdapter.java').read_text()
        self.assertIn('getGlobalVisibleRect(rect)',adapter)
        self.assertIn('host.onDiscoveryVisible()',adapter)
        self.assertNotIn('collection("reels")',home)
        self.assertIn('setItemPrefetchEnabled(false)',home)
        self.assertIn('FeedHeadReads.load(first,forceReload',home)
        self.assertIn('first.hasFreshLocalPage(FeedReadPolicy.REUSE_MS)',(JAVA/'core/FeedHeadReads.java').read_text())
    def test_friend_graph_is_saved_and_video_stories_use_posters(self):
        home=(JAVA/'main/HomeFragment.java').read_text()
        self.assertIn('putStringSet("friendGraph:" + me',home)
        story=(JAVA/'story/StoriesAdapter.java').read_text()
        self.assertIn('else if ("video".equals(latest.mediaType))',story)
        video=story.split('else if ("video".equals(latest.mediaType))')[1].split('} else {')[0]
        self.assertNotIn('Ui.img(c, h.img, latest.mediaUrl)',video)
        self.assertIn('deriveThumbUrl',video)
