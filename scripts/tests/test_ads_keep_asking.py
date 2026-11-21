"""Source contract guards: real ads keep being asked for, and failures stay diagnosable.

No fake ads, no retry loop, no blocking scroll — but a slot that collapsed once must still be able
to show a later ad, an under-filled pool must be asked again while the app is open, and the real
AdMob failure reason must be readable on the device.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


def absent(test, token, source, where):
    test.assertFalse(token in source, '%s must not contain %s' % (where, token))


class AdsKeepAskingGuards(unittest.TestCase):
    def setUp(self):
        self.ads = (JAVA / 'core/AdsConfig.java').read_text()

    def test_a_no_fill_keeps_the_slot_waiter_registered(self):
        self.assertIn('private static void notifyWaitersNoAdYet(final boolean isFeedPool)', self.ads)
        self.assertEqual(self.ads.count('notifyWaitersNoAdYet(isFeedPool);'), 2)
        # Clearing the waiters here is what left a collapsed slot empty until the next rebind, so
        # the notification path must only snapshot them (clearing stays in clearFeed/ReelsSlotAds).
        notify = self.ads.split('private static void notifyWaitersNoAdYet', 1)[1]
        notify = notify.split('private static void preloadBatchIntoPool', 1)[0]
        absent(self, 'SLOT_WAITERS.clear()', notify, 'notifyWaitersNoAdYet')
        self.assertIn('callbacks.addAll(FEED_SLOT_WAITERS.values());', notify)
        absent(self, 'failPendingWaiters', self.ads, 'AdsConfig')

    def test_no_periodic_refill_and_impressions_earn_one_request(self):
        self.assertNotIn('POOL_TOP_UP_INTERVAL_MS', self.ads)
        self.assertIn('REQUEST_BUDGET.onImpression(isFeedPool)', self.ads)
        self.assertIn('if (replacementEarned) return;', self.ads)

    def test_the_real_admob_failure_reason_is_stored_and_shown(self):
        self.assertIn('private static final String KEY_LAST_ERROR_MSG = "last_error_message";', self.ads)
        self.assertIn('adError.getDomain() + ": " + adError.getMessage()', self.ads)
        self.assertIn('public static void onNativeAdFailed(Context c, int errorCode, String detail)', self.ads)
        summary = self.ads.split('public static String statsSummary', 1)[1].split('public static', 1)[0]
        self.assertIn('KEY_LAST_ERROR_MSG', summary)
        self.assertIn('Switches: native ads %s (Admin master %s, Remote Config %s)', summary)
        self.assertIn('ConsentManager.isAdsSdkInitialized()', summary)
        self.assertIn('public static synchronized int feedPoolSize()', self.ads)
        self.assertIn('public static synchronized int reelsPoolSize()', self.ads)

    def test_admin_screen_can_run_a_real_fill_check(self):
        screen = (JAVA / 'admin/AdMobSettingsActivity.java').read_text()
        self.assertIn('Run Real Ad Fill Check', screen)
        self.assertIn('AdsConfig.preloadFeedBatchNow(getApplicationContext(), 1);', screen)
        self.assertIn('AdsConfig.preloadReelsBatchNow(getApplicationContext(), 1);', screen)
        self.assertIn('AdsConfig.statsSummary(this)', screen)
        self.assertIn('Code 3 can mean AdMob has no ad to serve', screen)
        # Zero test ads and zero dummy creatives anywhere in the ad path.
        for path in [JAVA / 'core/AdsConfig.java', JAVA / 'admin/AdMobSettingsActivity.java']:
            absent(self, 'isTestDevice', path.read_text(), path.name)
            absent(self, 'TEST_DEVICE', path.read_text(), path.name)

    def test_a_stranded_in_flight_flag_can_never_block_the_pool_forever(self):
        self.assertIn('private static final long IN_FLIGHT_STALE_MS = 15_000L;', self.ads)
        self.assertIn('if (feedInFlight > 0 && now - feedInFlightSinceMs > IN_FLIGHT_STALE_MS) feedInFlight = 0;', self.ads)
        self.assertIn('if (reelsInFlight > 0 && now - reelsInFlightSinceMs > IN_FLIGHT_STALE_MS) reelsInFlight = 0;', self.ads)

    def test_first_fill_rules_from_the_previous_speed_up_are_still_in_place(self):
        self.assertIn('preloadMediatedAd(appCtx, feedNativeId(), true)', self.ads)
        self.assertIn('preloadMediatedAd(appCtx, reelsNativeId(), false)', self.ads)
        self.assertIn('private static final long FAIL_COOLDOWN_MS = 10_000L;', self.ads)
        self.assertIn('if (!ConsentManager.isAdsSdkInitialized())', self.ads)
        consent = (JAVA / 'core/ConsentManager.java').read_text()
        self.assertIn('public static boolean isAdsSdkInitialized()', consent)
        self.assertIn('markReadyAndFlush(appCtx, true)', consent)


if __name__ == '__main__':
    unittest.main()
