import pathlib
import unittest

PROFILE = pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social/profile/ProfileActivity.java'


class ProfileRequestFastPathTest(unittest.TestCase):
    def test_request_write_updates_button_and_cancel_uses_known_id(self):
        source = PROFILE.read_text()
        self.assertIn('beginRelationshipAction("Sending…")', source)
        self.assertIn('hasOut=true;outgoingRequestId=x.getId()', source)
        cancel = source.split('private void cancelOutgoingRequest()', 1)[1].split('private void reconfirmFriendship()', 1)[0]
        self.assertIn('.document(requestId).delete()', cancel)
        self.assertNotIn('whereEqualTo(', cancel)
        self.assertIn('hasOut=false;outgoingRequestId=null', cancel)
