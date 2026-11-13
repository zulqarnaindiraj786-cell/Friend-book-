"""Structural checks only, not a substitute for device execution."""
from pathlib import Path
import unittest
JAVA = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'
class PushTraceTest(unittest.TestCase):
    def test_debug_trace_is_redacted(self):
        trace = (JAVA/'push/PushTrace.java').read_text()
        self.assertIn('Log.d("FriendBookPush"', trace)
        self.assertIn('MessageDigest.getInstance("SHA-256")', trace)
        self.assertNotIn('body.toString()', trace)
        self.assertNotIn('optString("token")', trace)
    def test_stage_chain_is_wired(self):
        checks = {'post/Reactions.java': ['reaction_write_ok', 'reaction_push_skipped', 'self_like'],
                  'core/Notify.java': ['notification_write_ok', 'notification_write_failed'],
                  'push/PushDeliveryWorker.java': ['queue_requested', 'queue_failed', 'worker_start', 'fcm_token_ready', 'gateway_result'],
                  'push/PushGateway.java': ['http_start', 'http_response'],
                  'push/FCMTokenManager.java': ['auth_state', 'registration_enqueue'],
                  'push/FriendMessagingService.java': ['fcm_received'],
                  'push/PushManager.java': ['display_skipped', 'display_submitted']}
        for file, stages in checks.items():
            for stage in stages:
                self.assertIn(stage, (JAVA/file).read_text(), (file, stage))
if __name__ == '__main__': unittest.main()
