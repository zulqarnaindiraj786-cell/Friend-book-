"""Source contracts only; Android gesture/network behavior needs device validation."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class SeamlessPaging(unittest.TestCase):
    def test_guarded_scroll_wiring(self):
        helper=(ROOT/'core/NearEndPaging.java').read_text()
        self.assertIn('SCROLL_STATE_IDLE',helper)
        self.assertIn('gate.shouldLoad',helper)
        for path in ['friends/FollowListActivity.java']:
            self.assertIn('NearEndPaging.attach(rv,', (ROOT/path).read_text())
        notifications=(ROOT/'main/NotificationsFragment.java').read_text()
        self.assertIn('pageGate.shouldLoad(dy',notifications)
        self.assertIn('getItemCount()-1',notifications)
        inbox=(ROOT/'chat/ConversationsActivity.java').read_text()
        self.assertNotIn('NearEndPaging.attach',inbox)
        self.assertIn('inboxMoreButton.setOnClickListener',inbox)
        for path in ['post/PostDetailActivity.java','reels/ReelCommentsActivity.java']:
            self.assertIn('NearEndPaging.attachComments(', (ROOT/path).read_text())
    def test_no_unchanged_list_rebuild_during_background_page_request(self):
        for path,token in [('main/NotificationsFragment.java','if(loading){moreButton.setText'),('friends/FollowListActivity.java','if(loading){moreButton.setText'),('profile/ProfileActivity.java','if(isFinishing() || loading)return;')]:
            self.assertIn(token,(ROOT/path).read_text())
    def test_comment_anchor_and_no_media_prefetch_added(self):
        code=(ROOT/'post/CommentsAdapter.java').read_text()
        for token in ['anchorId.equals(rows.get(i).c.id)','rows.size()>oldCount','scrollToPositionWithOffset']:
            self.assertIn(token,code)
        for path in ['core/NearEndPaging.java','core/ScrollPageGate.java']:
            code=(ROOT/path).read_text()
            self.assertNotIn('MediaPlayer',code);self.assertNotIn('prefetchSingle',code)
