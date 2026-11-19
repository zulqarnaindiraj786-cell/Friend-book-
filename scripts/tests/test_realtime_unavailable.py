import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
REALTIME = ROOT / 'app/src/main/java/com/friendbook/social/d1/realtime/DatabaseReference.java'
LIVE = ROOT / 'app/src/main/java/com/friendbook/social/live/LiveActivity.java'

class UnavailableRealtimeTest(unittest.TestCase):
    def test_unimplemented_transport_never_reports_success(self):
        source = REALTIME.read_text()
        self.assertNotIn('Tasks.forResult(', source)
        self.assertIn('Tasks.forException(', source)
        self.assertIn('listener.onCancelled(new DatabaseError())', source)

    def test_failed_room_creation_does_not_trigger_end_write(self):
        source = LIVE.read_text()
        failure = source.split('docRef.set(doc).addOnSuccessListener', 1)[1].split('private void notifyFollowersGoingLive', 1)[0]
        self.assertIn('isLiveStarted = false;', failure)
        self.assertIn('isHost && isLiveStarted && !isEnded && liveId != null', source)

if __name__ == '__main__':
    unittest.main()
