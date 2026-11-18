"""Source contract guards: real ads keep being asked for, and failures stay diagnosable.

No fake ads, no tight retry loops, no blocking scroll — but a queue that drains must be refilled
(self-heal after poll/impression/pull-to-refresh/failure), and the real AdMob failure reason must
be readable on the device.
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
        self.queue = (JAVA / 'core/AdQueueManager.java').read_text()

    def test_a_drained_queue_is_asked_again(self):
        # Empty poll triggers an emergency refill instead of parking the slot empty.
        self.assertIn('Log.w(TAG, "Feed queue EMPTY! Triggering emergency refill");', self.queue)
        self.assertIn('Log.w(TAG, "Reels queue EMPTY! Triggering emergency refill");', self.queue)
        # Pull-to-refresh tops the empty slots back up without discarding queued ads.
        refresh = self.queue.split('public void refreshQueue()', 1)[1]
        refresh = refresh.split('// ============ PRELOADING LOGIC', 1)[0]
        self.assertIn('if (feedQueue.size() < QUEUE_CAPACITY)', refresh)
        self.assertIn('if (reelsQueue.size() < QUEUE_CAPACITY)', refresh)
        absent(self, 'feedQueue.clear()', refresh, 'refreshQueue')
        absent(self, 'reelsQueue.clear()', refresh, 'refreshQueue')
        self.assertIn('AdQueueManager.getInstance().refreshQueue();',
                      (JAVA / 'post/FeedAdapter.java').read_text())
        self.assertIn('AdQueueManager.getInstance().refreshQueue();',
                      (JAVA / 'reels/ReelsFragment.java').read_text())

    def test_an_impression_schedules_an_immediate_refill(self):
        # Impression callbacks both refill the queue and count toward the admin diagnostics.
        self.assertIn('AdsConfig.recordAdImpression(appContext);', self.queue)
        self.assertIn('onFeedAdImpression();', self.queue)
        self.assertIn('onReelsAdImpression();', self.queue)
        on_feed = self.queue.split('public void onFeedAdImpression()', 1)[1].split('public void onReelsAdImpression()', 1)[0]
        self.assertIn('scheduleFeedRefill();', on_feed)

    def test_the_real_admob_failure_reason_is_stored_and_shown(self):
        self.assertIn('private static final String KEY_LAST_ERROR_MSG = "last_error_message";', self.ads)
        # The domain+message format AdQueueManager stores on failure.
        self.assertIn('adError.getDomain() + ": " + adError.getMessage()', self.queue)
        self.assertIn('AdsConfig.onNativeAdFailed(appContext, adError.getCode(), detail);', self.queue)
        self.assertIn('public static void onNativeAdFailed(Context c, int errorCode, String detail)', self.ads)
        summary = self.ads.split('public static String statsSummary', 1)[1].split('public static', 1)[0]
        self.assertIn('KEY_LAST_ERROR_MSG', summary)
        self.assertIn('Switches: native ads %s (Admin master %s, Remote Config %s)', summary)
        self.assertIn('ConsentManager.isAdsSdkInitialized()', summary)
        self.assertIn('public static synchronized int feedPoolSize()', self.ads)
        self.assertIn('public static synchronized int reelsPoolSize()', self.ads)

    def test_requests_loads_fails_clicks_and_impressions_are_counted(self):
        # The admin fill-check numbers must never be dead constants.
        self.assertIn('AdsConfig.recordNativeAdRequest(appContext);', self.queue)
        self.assertIn('AdsConfig.recordNativeAdLoaded(appContext);', self.queue)
        self.assertIn('AdsConfig.recordAdImpression(appContext);', self.queue)
        self.assertIn('AdsConfig.recordAdClick(appContext);', self.queue)
        self.assertIn('public static void recordNativeAdRequest(Context c)', self.ads)
        self.assertIn('public static void recordNativeAdLoaded(Context c)', self.ads)
        self.assertEqual(self.queue.count('recordNativeAdRequest(appContext);'), 2)
        self.assertEqual(self.queue.count('recordNativeAdLoaded(appContext);'), 2)

    def test_admin_screen_can_run_a_real_fill_check(self):
        screen = (JAVA / 'admin/AdMobSettingsActivity.java').read_text()
        self.assertIn('Run Real Ad Fill Check', screen)
        self.assertIn('AdQueueManager.getInstance().requestFeedAd();', screen)
        self.assertIn('AdQueueManager.getInstance().requestReelsAd();', screen)
        self.assertIn('AdsConfig.statsSummary(this)', screen)
        self.assertIn('Code 3 = AdMob has no ad to serve', screen)
        # Zero test ads and zero dummy creatives anywhere in the ad path.
        for path in [JAVA / 'core/AdsConfig.java', JAVA / 'admin/AdMobSettingsActivity.java',
                     JAVA / 'core/AdQueueManager.java']:
            absent(self, 'isTestDevice', path.read_text(), path.name)
            absent(self, 'TEST_DEVICE', path.read_text(), path.name)

    def test_a_stranded_loading_flag_cannot_block_the_queue_forever(self):
        # getAndSet(true) with matching set(false) in both success and failure callbacks.
        self.assertIn('if (feedLoading.getAndSet(true)) return;', self.queue)
        self.assertIn('if (reelsLoading.getAndSet(true)) return;', self.queue)
        # success, failure, and clearAll are the three writers — none can strand the flag.
        self.assertEqual(self.queue.count('feedLoading.set(false);'), 3)
        self.assertEqual(self.queue.count('reelsLoading.set(false);'), 3)

    def test_requests_are_not_issued_into_a_half_booted_sdk(self):
        # AdQueueManager never boots MobileAds itself; ConsentManager owns the single init.
        absent(self, 'import com.google.android.gms.ads.MobileAds;', self.queue, 'AdQueueManager')
        self.assertIn('ConsentManager.runWhenSdkInitialized(appContext, this::startPreloading)', self.queue)


if __name__ == '__main__':
    unittest.main()
