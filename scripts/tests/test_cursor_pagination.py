from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class CursorContracts(unittest.TestCase):
    def test_pager_closes_and_does_not_advance_failed_or_offline_cursor(self):
        code=(ROOT/'core/CursorPager.java').read_text()
        for token in ['page.startAfter(cursor)','bounded.getCachedOrFetch(false) : bounded.get()','Objects.equals(actor,Fb.uid())','if(!offline && !result.isEmpty())cursor=', 'public void close(){closed=true;requestGeneration++;','timeoutHandler.postDelayed(timeout,20000L)','request!=requestGeneration','LruCache<>(24)']:
            self.assertIn(token,code)
        self.assertNotIn('cursor=',code.split('public void next()',1)[1].split('.addOnFailureListener',1)[1])
    def test_requested_list_pages_and_controls(self):
        for path,token in [('friends/FollowListActivity.java',',10,"follow:'),('main/NotificationsFragment.java',',PAGE_SIZE,"notifications:'),('profile/ProfileActivity.java',',10,"profile-posts:'),('profile/ProfileActivity.java',',10,"profile-reels:'),('post/Reactions.java',',30,"post-reactions:')]:
            self.assertIn(token,(ROOT/path).read_text())
    def test_profile_lazy_reels_and_privacy_before_photo_hydration(self):
        code=(ROOT/'profile/ProfileActivity.java').read_text()
        self.assertIn('if(idx==1 && videoPager==null)',code)
        self.assertIn('if(!canSee(post))continue;',code)
        self.assertIn('postPager.next()',code)
    def test_comments_new_fifty_contract(self):
        code=(ROOT/'post/CommentsEngine.java').read_text()
        for token in ['legacyLoaded=10, visible=10','handle.query.limit(10)','query.startAfter(cursor).limit(10)','page.size()==10']:
            self.assertIn(token,code)
