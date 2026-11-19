import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social/groups'
class GroupAcknowledgements(unittest.TestCase):
 def test_join_and_roles_acknowledge_server_result(self):
  s=(ROOT/'GroupDetailActivity.java').read_text()
  self.assertIn('private void groupWrite(',s)
  self.assertIn('Group update failed. Please try again.',s)
  self.assertIn('groupWrite(Fb.db().collection("groups").document(id).update(up)',s)
  self.assertIn("Couldn't approve request. Please try again.",s)
  self.assertIn("Couldn't send invitations. Please try again.",s)
  self.assertIn("Couldn't remove post. Please try again.",s)
 def test_create_shows_failure(self):
  s=(ROOT/'GroupsActivity.java').read_text()
  self.assertIn("Couldn't create group. Please try again.",s)
if __name__=='__main__': unittest.main()
