"""Source regression guards; actual footer/control views are exercised by Robolectric in CI."""
import unittest
from pathlib import Path
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class ScrollLoadingUx(unittest.TestCase):
    def test_friends_default_keeps_suggestions_inline_and_both_pagers_close(self):
        code=(JAVA/'friends/FriendsFragment.java').read_text()
        requests=code.split('if (section == SEC_REQUESTS) {',1)[1].split('} else if (section == SEC_SUGGESTIONS)',1)[0]
        self.assertIn('People you may know',requests)
        self.assertIn('addSuggestions(rows)',requests)
        # Both pagers close on hide/stop; they are D1 pagers now.
        self.assertIn('suggestionDocPager.close()',code)
        self.assertIn('activeDocPager.close()',code)
        self.assertIn('if(loading){requestRender();return;}',code)
        self.assertNotIn('root.addView(refresh)',code)
        self.assertNotIn('moreButton',code)
        self.assertNotIn('HorizontalScrollView',code)
        self.assertIn('LayoutParams(0,-2,1)',code)
    def test_home_has_no_sticky_refresh_or_fake_spinner_timer(self):
        code=(JAVA/'main/HomeFragment.java').read_text()
        self.assertNotIn('freshPill.setVisibility(View.VISIBLE)',code)
        self.assertNotIn('swipe.postDelayed',code)
        self.assertIn('if(swipe!=null && !loadingPosts)swipe.setRefreshing(false)',code)
        self.assertIn('rv.post(()->',code)
        self.assertIn('loadingPosts=false;restartPostsListener();',code)
    def test_loader_is_feed_footer_not_manual_paging(self):
        code=(JAVA/'post/FeedAdapter.java').read_text()
        self.assertIn('fh.spinner.setVisibility(loading?',code)
        self.assertIn('more && !host.automaticPaging()',code)
        self.assertIn('notifyItemChanged(getItemCount()-1,"paging")',code)
    def test_first_page_error_retries_without_invalid_cursor(self):
        code=(JAVA/'main/HomeFragment.java').read_text()
        self.assertIn('if (lastDoc == null) { if (pageError) restartPostsListener(); return; }',code)
        # An empty page is only an error when the worker says there is more.
        self.assertIn('pageError = moreAvailable && out.isEmpty()',code)
        self.assertIn('pageError=moreAvailable && finalFeed.isEmpty()',code)
