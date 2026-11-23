import pathlib
import unittest

JAVA = pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'


class ChatPushFastPathTest(unittest.TestCase):
    def test_direct_foreground_push_is_bounded_and_keeps_durable_fallback(self):
        source = (JAVA / 'push/FCMPush.java').read_text()
        self.assertIn('directMessage && com.friendbook.social.App.isForeground()', source)
        self.assertIn('ArrayBlockingQueue<>(8)', source)
        self.assertIn('PushGateway.call(body, bearer)', source)
        self.assertIn('if (actor.equals(Fb.uid())) PushDeliveryWorker.enqueue(body)', source)
        activity = (JAVA / 'chat/ChatActivity.java').read_text()
        self.assertIn('batch.commitChatSend().addOnSuccessListener', activity)

    def test_foreground_reads_are_scheduled_before_notification_render(self):
        source = (JAVA / 'push/FriendMessagingService.java').read_text()
        self.assertEqual(source.count('.refreshChatFromPush('), 1)
        self.assertLess(source.index('.refreshChatFromPush('), source.index('PushManager.show('))
        self.assertIn('actor.equals(data.get("toUserId"))', source)
