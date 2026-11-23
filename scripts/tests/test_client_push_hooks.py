"""Client-side wiring guards; physical two-device delivery is tested separately."""
import unittest
from pathlib import Path
JAVA = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'

class ClientPushHooksTest(unittest.TestCase):
    def test_social_notification_after_successful_write(self):
        source = (JAVA/'core/Notify.java').read_text()
        self.assertIn('collection("notifications").add(n).addOnSuccessListener', source)
        self.assertIn('if (me.equals(Fb.uid())) com.friendbook.social.push.FCMPush.notification', source)
        self.assertIn('batch.commit().addOnSuccessListener', source)
        for file in ['profile/ProfileActivity.java', 'post/FeedAdapter.java', 'reels/ReelsFragment.java', 'live/LiveActivity.java']:
            self.assertIn('Notify.follow(', (JAVA/file).read_text(), file)

    def test_reactions_dispatch_after_write_success(self):
        source = (JAVA/'post/Reactions.java').read_text()
        start = source.index('reactRef.set(data).addOnSuccessListener')
        end = source.index('Map<String, Object> dot', start)
        self.assertIn('Notify.send(post.authorId, "like"', source[start:end])
        reels = (JAVA/'reels/ReelsFragment.java').read_text()
        self.assertIn('subRef.set(rxEntry).addOnSuccessListener(v -> notifyReaction.run())', reels)
        self.assertIn('reelRef.update(dot).addOnSuccessListener(v -> notifyReaction.run())', reels)

    def test_chat_and_post_fanout_is_durable_and_account_scoped(self):
        source = (JAVA/'push/FCMPush.java').read_text()
        self.assertIn('PushFanoutWorker.enqueue("chat", chatId, messageId)', source)
        self.assertIn('PushFanoutWorker.enqueue("friend_post", postId', source)
        worker = (JAVA/'push/PushFanoutWorker.java').read_text()
        for part in ['NetworkType.CONNECTED', 'uid.equals(Fb.uid())', 'ExistingWorkPolicy.KEEP', 'getRunAttemptCount() < 8', '"friend_post_" + id + "_" + to', 'Tasks.await(ref.create(n)' ]:
            self.assertIn(part, worker)
        chat = (JAVA/'chat/ChatActivity.java').read_text()
        self.assertEqual(chat.count('FCMPush.message(chatId, msgRef.getId(), isGroupChat ? null : peerId)'), 3)
        self.assertIn('FCMPush.friendPost(docRef.getId())', (JAVA/'post/CreatePostActivity.java').read_text())

    def test_gateway_is_authenticated_and_legacy_devices_do_not_need_fgs(self):
        gateway = (JAVA/'push/PushGateway.java').read_text()
        self.assertIn('/functions/v1/send-fcm-notification', gateway)
        self.assertIn('setRequestProperty("Authorization", "Bearer " + bearer)', gateway)
        source = (JAVA/'push/FCMPush.java').read_text()
        for key in ['"receiverId"', '"target_user_id"', '"title"', '"body"', '"notificationId"']:
            self.assertIn(key, source)
        for file in ['push/PushFanoutWorker.java', 'push/PushDeliveryWorker.java']:
            self.assertIn('if (android.os.Build.VERSION.SDK_INT >= 31)', (JAVA/file).read_text())

if __name__ == '__main__':
    unittest.main()
