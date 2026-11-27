from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]

class PushWorkIdentity(unittest.TestCase):
    def test_unique_queue_uses_event_key_without_changing_retry_policy(self):
        source=(ROOT/'app/src/main/java/com/friendbook/social/push/PushDeliveryWorker.java').read_text()
        self.assertIn('String key = PushWorkKey.of(uid, body);',source)
        self.assertIn('ExistingWorkPolicy.REPLACE : ExistingWorkPolicy.KEEP',source)
        self.assertIn('PushRetryPolicy.shouldRetry',source)
        key=(ROOT/'app/src/main/java/com/friendbook/social/push/PushWorkKey.java').read_text()
        self.assertIn('return "register_"+actor',key)
        self.assertIn('return actor+"_"+body.toString()',key)
        self.assertIn('MessageDigest.getInstance("SHA-256")',key)
