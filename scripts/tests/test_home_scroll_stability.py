import unittest
from pathlib import Path

ROOT = Path('app/src/main/java/com/friendbook/social')

class HomeScrollStability(unittest.TestCase):
    def test_warm_return_keeps_five_posts_and_does_not_force_top(self):
        home = (ROOT/'main/HomeFragment.java').read_text()
        self.assertIn('rv.getScrollState()!=RecyclerView.SCROLL_STATE_IDLE', home)
        self.assertIn('setPostsKeepingScroll(finalFeed)', home)
        main = (ROOT/'main/MainActivity.java').read_text()
        self.assertNotIn('.scrollToTop()', main)

    def test_feed_structural_changes_use_stable_diff_and_do_not_grow_ads_above_anchor(self):
        adapter = (ROOT/'post/FeedAdapter.java').read_text()
        structural = adapter.split('public void setPosts(', 1)[1].split('private static boolean sameReactionsSignature', 1)[0]
        self.assertNotIn('notifyDataSetChanged();', structural)
        self.assertIn('dispatchRowDiff(before,false);', structural)
        self.assertIn('if (getBindingAdapterPosition()<=first) return;', adapter)
