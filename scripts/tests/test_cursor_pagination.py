from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class CursorContracts(unittest.TestCase):
    def test_pager_closes_and_does_not_advance_failed_or_offline_cursor(self):
        code=(ROOT/'core/CursorPager.java').read_text()
        for token in ['page.startAfter(cursor)','page.limit(size).get()','Objects.equals(actor,Fb.uid())','if(!offline && !result.isEmpty())cursor=', 'public void close(){closed=true;}','LruCache<>(24)']:
            self.assertIn(token,code)
        self.assertNotIn('cursor=',code.split('.addOnFailureListener',1)[1])
    def test_requested_list_pages_and_controls(self):
        # These lists page on D1 now, so the page size is an argument to DocPager rather than part
        # of a Firestore CursorPager key. The sizes themselves are unchanged.
        for path,token in [('friends/FollowListActivity.java','.build(),20,'),
                           ('profile/ProfileActivity.java','.build(),7,'),
                           ('profile/ProfileActivity.java','.build(),10,'),
                           ('post/Reactions.java',',30,"post-reactions:')]:
            self.assertIn(token,(ROOT/path).read_text())
        # Notifications moved to D1, so it pages with DocPager rather than CursorPager.
        notifications=(ROOT/'main/NotificationsFragment.java').read_text()
        self.assertIn('new com.friendbook.social.core.DocPager(nb.build(),20',notifications)
        self.assertIn('DocQuery.of("notifications")',notifications)
        self.assertIn('.orderBy("createdAt",com.friendbook.social.core.DocQuery.DESC)',notifications)
    def test_profile_lazy_reels_and_privacy_before_photo_hydration(self):
        code=(ROOT/'profile/ProfileActivity.java').read_text()
        self.assertIn('if(idx==1 && videoPager==null)',code)
        self.assertIn('if(!canSee(post))continue;',code)
        self.assertIn('postPager.next()',code)
    def test_comments_new_fifty_contract(self):
        code=(ROOT/'post/CommentsEngine.java').read_text()
        for token in ['legacyLoaded=50, visible=50','handle.query.limit(50)','query.startAfter(cursor).limit(50)','page.size()==50']:
            self.assertIn(token,code)
