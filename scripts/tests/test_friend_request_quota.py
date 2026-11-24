from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class FriendRequestQuota(unittest.TestCase):
 def test_actions_do_not_reload_section_on_success_or_failure(self):
  s=(JAVA/'friends/FriendsFragment.java').read_text().split('// ---------- actions ----------',1)[1]
  self.assertNotIn('startSection()',s)
  for token in ['claimAction(me,from)','pendingActions.remove(action)','incoming.add(restore)','claimAction(me,other)']:
   self.assertIn(token,s)
 def test_lifecycle_closes_both_pagers_and_view_ownership(self):
  s=(JAVA/'friends/FriendsFragment.java').read_text()
  self.assertIn('public void onDestroyView()',s)
  self.assertIn('token==generation && visibleActor(actor)',s)
  self.assertIn('if(hidden){ closePage(false); }',s)
  self.assertIn('activePager.isLoading()',s)
  self.assertNotIn('startDelta()',s)
 def test_friends_and_profiles_are_ten_row_submitted_queries(self):
  s=(JAVA/'friends/FriendsFragment.java').read_text()
  self.assertNotIn('.limit(20)',s)
  self.assertNotIn('Fb.getUserFresh(',s)
  self.assertIn('Ui.chunks(missing,10)',s)
  self.assertIn('activePager.startCachedFor(300_000L)',s)
  self.assertIn('setItemPrefetchEnabled(false)',s)
 def test_request_badge_is_bounded_and_cached(self):
  s=(JAVA/'main/MainActivity.java').read_text().split('private void startBadgeListeners()',1)[1].split('Fb.db().collection("friend_requests")',1)[1]
  self.assertIn('.limit(10)',s)
  self.assertIn('.getCachedOrFetchAutomatic(badgeReadScope,300_000L)',s)
