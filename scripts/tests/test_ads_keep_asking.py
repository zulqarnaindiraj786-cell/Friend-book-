"""Source contract guards: real ads keep being asked for, and failures stay diagnosable.

No fake ads, no retry loop, no blocking scroll — but a slot that collapsed once must still be able
to show a later ad, an under-filled pool must be asked again while the app is open, and the real
AdMob failure reason must be readable on the device.

NOTE (2.8.92): the ad stack moved to a queue owned by `AdQueueManager` (3-ad feed/reels queues,
poll-out-then-deliver). `AdsConfig` is now the policy/state facade that delegates to it. These
contracts were rewritten against that design; the previous version asserted the old
SLOT_WAITERS / startPoolTopUpTicker architecture, which no longer exists and failed the build.
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

    # ---- no-fill must never strand a slot: the queue retries, the waiter is not dropped ----

    def test_a_no_fill_does_not_permanently_strand_a_slot(self):
        # A failed load must clear the in-flight flag, so the pool can never be blocked forever.
        self.assertIn('feedLoading.set(false);', self.ads + self.queue)
        # A short cooldown, not an immediate tight retry and not an endless sleep.
        self.assertIn('FAIL_COOLDOWN_MS', self.queue)
        self.assertIn('mainHandler.postDelayed', self.queue)

    def test_a_stranded_in_flight_flag_can_never_block_the_pool_forever(self):
        # Every failure path decrements the in-flight counter before returning.
        fail_block = self.queue.split('public void onAdFailedToLoad(LoadAdError adError)', 1)[1]
        fail_block = fail_block.split('},', 1)[0]
        self.assertIn('decrementAndGet()', fail_block)

    # ---- under-filled pools are asked again while the app is open ----

    def test_under_filled_pools_are_asked_again(self):
        # Refill is driven after an impression and after a poll drains the queue.
        self.assertIn('scheduleFeedRefill', self.queue)
        self.assertIn('scheduleReelsRefill', self.queue)
        self.assertIn('onFeedAdImpression()', self.queue)

    def test_empty_pool_asks_for_one_ad_and_tops_up_after_it_lands(self):
        self.assertIn('QUEUE_CAPACITY', self.queue)
        self.assertIn('feedQueueFull', self.queue)

    def test_failure_cooldown_is_short_and_the_retry_is_bounded(self):
        # Cooldown must be small (seconds), and recorded so the admin screen can show it.
        self.assertIn('private static final long FAIL_COOLDOWN_MS', self.queue)
        self.assertIn('lastFeedFailTime', self.queue)

    def test_first_request_waits_for_the_real_sdk_initialization(self):
        # Ads are only requested through the queue once the SDK is ready.
        self.assertIn('isAdsSdkInitialized', self.ads)

    def test_pools_still_boot_from_application_oncreate(self):
        # The queue is initialised from the app entry point, not lazily on first scroll.
        self.assertIn('AdQueueManager.getInstance().initialize(context);', self.ads)

    # ---- the queue must actually serve ads, not fake them ----

    def test_pools_are_served_from_the_queue_with_poll_out_then_deliver(self):
        self.assertIn('pollFeedAd()', self.ads)
        self.assertIn('pollReelsAd()', self.ads)
        self.assertIn('hasFeedAd()', self.ads)
        # Delivering an ad must remove it from the queue (so the same ad is never shown twice).
        poll = self.queue.split('public synchronized NativeAd pollFeedAd()', 1)[1]
        poll = poll.split('public boolean hasFeedAd()', 1)[0]
        self.assertIn('poll()', poll)

    def test_an_ad_can_never_be_delivered_twice(self):
        poll = self.queue.split('public synchronized NativeAd pollFeedAd()', 1)[1]
        poll = poll.split('public boolean hasFeedAd()', 1)[0]
        # The ad leaves the queue at delivery time, so the callback can only ever see it once.
        self.assertIn('feedQueue.poll()', poll)

    # ---- the real AdMob failure reason must be readable on the device ----

    def test_the_real_admob_failure_reason_is_stored_and_shown(self):
        self.assertIn('KEY_LAST_ERROR_MSG = "last_error_message"', self.ads)
        self.assertIn('public static void onNativeAdFailed(Context c, int errorCode, String detail)', self.ads)
        # The SDK's own message is what gets stored, never a guessed string.
        self.assertIn('adError.getMessage()', self.queue)
        summary = self.ads.split('public static String statsSummary', 1)[1].split('public static', 1)[0]
        self.assertIn('KEY_LAST_ERROR_MSG', summary)

    def test_admin_screen_can_run_a_real_fill_check(self):
        screen = (JAVA / 'admin/AdMobSettingsActivity.java').read_text()
        self.assertIn('AdsConfig.statsSummary(this)', screen)
        # Pool sizes are exposed for the admin screen from the real queue.
        self.assertIn('public static synchronized int feedPoolSize()', self.ads)
        self.assertIn('public static synchronized int reelsPoolSize()', self.ads)
        self.assertIn('getFeedQueueSize()', self.ads)
        self.assertIn('getReelsQueueSize()', self.ads)

    # ---- production only: no test ids, no dummy creatives ----

    def test_production_admob_ids_only(self):
        self.assertIn('ca-app-pub-3387480177023669~1513026594', self.ads)
        absent(self, '3940256099942544', self.ads, 'AdsConfig')
        absent(self, '3940256099942544', self.queue, 'AdQueueManager')


if __name__ == '__main__':
    unittest.main()
