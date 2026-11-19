from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class IntermittentDataTests(unittest.TestCase):
 def test_profile_does_not_clear_on_temporary_failure_or_cached_miss(self):
  s=(ROOT/'profile/ProfileActivity.java').read_text()
  self.assertIn('if (user == null) nameT.setText',s)
  self.assertIn('if (d.getMetadata().isFromCache()) return;',s)
  self.assertIn('if (snap==null) return; // keep the last confirmed relationship',s)
  self.assertIn('Math.max(user.followerCount, user.friendsCount)',s)
 def test_token_rejection_retries_once_not_all_failures(self):
  doc=(ROOT/'d1/documents/D1DocumentTransport.java').read_text()
  self.assertIn('Code.UNAUTHENTICATED',doc)
  self.assertIn('requestOnce(method,pathAndQuery,body,actor,true)',doc)
  client=(ROOT/'core/D1Client.java').read_text()
  self.assertIn('failure.status!=401',client)
  self.assertIn('user.getIdToken(true)',client)
 def test_counts_retry_and_chat_transient_errors_do_not_close_room(self):
  s=(ROOT/'core/CountReads.java').read_text()
  self.assertIn('handler.postDelayed(this,delay)',s)
  self.assertIn('handler.removeCallbacks(retry)',s)
  chat=(ROOT/'chat/ChatActivity.java').read_text()
  self.assertIn('if (d == null) return; // a transient read failure',chat)
if __name__=='__main__':unittest.main()
