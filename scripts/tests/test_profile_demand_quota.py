"""Profile source wiring contracts; runtime demand is covered by ProfileDemandTest."""
from pathlib import Path
import unittest
S=(Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social/profile/ProfileActivity.java').read_text()
class ProfileDemandQuota(unittest.TestCase):
 def test_exact_pages_and_ttls(self):
  self.assertIn('10,"profile-friends:"',S)
  self.assertIn('10,"profile-posts:"',S)
  self.assertIn('10,"profile-reels:"',S)
  self.assertIn('friendPager.startCachedFor(300_000L)',S)
  self.assertIn('postPager.startCachedForForeground(120_000L)',S)
  self.assertIn('videoPager.startCachedFor(120_000L)',S)
 def test_batching_and_no_bind_time_highlight_reads(self):
  render=S.split('private void renderPersonalDetailsAndHighlights',1)[1].split('private void loadHighlightPage',1)[0]
  self.assertNotIn('.get()',render)
  self.assertIn('v->loadHighlightPage()',render)
  self.assertNotIn('Fb.getUser(',S)
  self.assertIn('Ui.chunks(ids,10)',S)
 def test_scroll_only_on_recent_user_gesture(self):
  self.assertIn('elapsedRealtime()-profileScrollIntentAt>1_000L',S)
  self.assertIn('y<=oldY',S)
  self.assertEqual(4,S.count('setItemPrefetchEnabled(false)'))
 def test_photo_callback_actor_and_request_guards(self):
  photo=S.split('protected void onActivityResult',1)[1].split('private void showProfileSearchDialog',1)[0]
  self.assertIn('(req!=REQ_PICK_AVATAR && req!=REQ_PICK_COVER)',photo)
  self.assertIn('photoUploadBusy=true',photo)
  self.assertLess(photo.index('if(!java.util.Objects.equals(uploadActor,Fb.uid()))'),photo.index('Fb.patchMe(up)'))
  self.assertGreaterEqual(photo.count('java.util.Objects.equals(uploadActor,Fb.uid())'),4)
 def test_cancel_and_accept_are_bounded_no_full_request_scan(self):
  cancel=S.split('private void cancelOutgoingRequest()',1)[1].split('private void reconfirmFriendship',1)[0]
  self.assertIn('.document(requestId).delete()',cancel)
  self.assertNotIn('.get()',cancel)
  accept=S.split('private void respondToIncoming',1)[1].split('private void sendProfileRequest',1)[0]
  self.assertIn('batch.commit()',accept)
  self.assertIn('beginRelationshipAction(',accept)
