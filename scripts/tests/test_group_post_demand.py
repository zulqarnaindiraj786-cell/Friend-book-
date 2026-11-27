from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class GroupPostDemand(unittest.TestCase):
 def test_feed_no_longer_loads_120_or_attaches_post_listener(self):
  s=(JAVA/'groups/GroupDetailActivity.java').read_text()
  self.assertNotIn('.limit(120)',s)
  self.assertNotIn('pReg',s)
  self.assertIn('10,"group-posts:"+id',s)
  self.assertIn('!groupPostPager.isLoading() && groupPostPager.hasMore()',s)
  self.assertIn('groupPostPager.close()',s)
 def test_pending_and_management_are_independent_pages_not_feed_truncation(self):
  s=(JAVA/'groups/GroupDetailActivity.java').read_text()
  for method,next_method in [('showPendingPostsDialog','showJoinRequestsDialog'),('showModerateGroupPostsDialog','showBannedMembersDialog')]:
   section=s.split('private void '+method+'()',1)[1].split('private void '+next_method+'()',1)[0]
   self.assertIn('CursorPager(query,',section)
   self.assertIn('pager.close()',section)
   self.assertIn('pager.next()',section)
   self.assertIn('resolvedPosts.contains(p.id)',section)
  self.assertIn('.whereEqualTo("groupApprovalStatus","pending")',s)
 def test_pin_is_resolved_by_id_and_rejects_other_group_pending_or_stale_callback(self):
  s=(JAVA/'groups/GroupDetailActivity.java').read_text().split('private void requestPinnedPost()',1)[1].split('@Override',1)[0]
  for text in ['document(pin).getCachedOrFetch()','generation!=screenGeneration','!id.equals(post.groupId)','"pending".equals(post.groupApprovalStatus)','pin.equals(requestedPinnedId)']:self.assertIn(text,s)
