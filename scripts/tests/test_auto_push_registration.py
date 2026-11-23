"""Structural wiring checks supplement the retry-policy JVM and server tests."""
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'

class AutoPushRegistrationTest(unittest.TestCase):
    def test_bootstrap_and_login_are_automatic(self):
        self.assertIn('PushManager.init(this)', (JAVA/'App.java').read_text())
        source = (JAVA/'push/FCMTokenManager.java').read_text()
        self.assertIn('setAutoInitEnabled(true)', source)
        self.assertIn('addAuthStateListener', source)
        self.assertIn('sync(app)', source)
        self.assertNotIn('enqueueUniquePeriodicWork', source)
        self.assertIn('NetworkType.CONNECTED', (JAVA/'push/PushDeliveryWorker.java').read_text())

    def test_token_refresh_and_session_recovery(self):
        self.assertIn('PushManager.save(this, token)', (JAVA/'push/FriendMessagingService.java').read_text())
        self.assertIn('FCMTokenManager.onTokenChanged(c)', (JAVA/'push/PushManager.java').read_text())
        worker = (JAVA/'push/PushDeliveryWorker.java').read_text()
        self.assertIn('PushGateway.idToken(true)', worker)
        self.assertIn('!uid.equals(Fb.uid())', worker)
        self.assertIn('PushRetryPolicy.shouldRetry(register', worker)

    def test_permission_is_automatic_after_foreground_focus(self):
        main = (JAVA/'main/MainActivity.java').read_text()
        self.assertIn('NotificationPermissionPrompt.requestOnce(this)', main)
        self.assertIn('onWindowFocusChanged', main)
        self.assertIn('postDelayed(notificationPermissionPrompt, 2000L)', main)
        prompt = (JAVA/'push/NotificationPermissionPrompt.java').read_text()
        self.assertIn('POST_NOTIFICATIONS', prompt)
        self.assertIn('if (prefs.getBoolean(ATTEMPTED, false)) return', prompt)

    def test_manual_diagnostics_registration_flow_removed(self):
        self.assertFalse((JAVA/'push/PushDiagnosticsActivity.java').exists())
        for source in [JAVA/'settings/SettingsActivity.java', JAVA/'push/PushOpenActivity.java', ROOT/'app/src/main/AndroidManifest.xml']:
            self.assertNotIn('PushDiagnosticsActivity', source.read_text())
        self.assertNotIn('Refresh FCM registration', (JAVA/'settings/SettingsActivity.java').read_text())

if __name__ == '__main__':
    unittest.main()
