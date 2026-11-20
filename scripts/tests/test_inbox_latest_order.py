import pathlib
import unittest

CHAT = pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social/chat'


class InboxLatestOrderTest(unittest.TestCase):
    def test_open_chat_hands_off_server_message_without_changing_delivery(self):
        source = (CHAT / 'ChatActivity.java').read_text()
        self.assertIn('chatVisible && !snap.getMetadata().isFromCache() && !snap.getMetadata().hasPendingWrites()', source)
        self.assertIn('InboxMessageHandoff.seen(actor', source)
        inbox = (CHAT / 'ConversationsActivity.java').read_text()
        resume = inbox.split('void onResume()', 1)[1].split('private void loadInboxPage', 1)[0]
        self.assertIn('applyInboxHandoff(Fb.uid());', resume)
        self.assertIn('applyFilter();', resume)

    def test_cached_or_old_pages_cannot_replace_the_latest_row(self):
        inbox = (CHAT / 'ConversationsActivity.java').read_text()
        self.assertIn('snap.getMetadata().isFromCache() && !allRooms.isEmpty()', inbox)
        self.assertIn('previous.room.lastMessageTime>r.room.lastMessageTime', inbox)
        self.assertIn('allRooms.set(allRooms.indexOf(previous),r)', inbox)
        self.assertIn('.orderBy("inboxActivityAt"', inbox)
