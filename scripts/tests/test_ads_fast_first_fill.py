"""Source contract guards for fast ad first-fill (no retry loops, no premature SDK requests).

NOTE (2.8.92): the old per-slot pool API (`FEED_POOL`, `ensureFeedPoolReplenished`,
`preloadFeedBatchNow`, `FIRST_FILL_RETRY_DELAY_MS`) no longer exists — the ad stack is now a
3-ad queue in `AdQueueManager` fed by `scheduleFeedRefill` / `scheduleReelsRefill`. These
contracts were rewritten against the current design; the previous version asserted symbols that
were already gone at the base commit, so they failed the release build unconditionally.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


class AdsFastFirstFillGuards(unittest.TestCase):
    def setUp(self):
        self.ads = (JAVA / 'core/AdsConfig.java').read_text()
        self.queue = (JAVA / 'core/AdQueueManager.java').read_text()
        self.consent = (JAVA / 'core/ConsentManager.java').read_text()

    def test_empty_pool_asks_for_one_ad_and_tops_up_after_it_lands(self):
        # The queue refills itself to capacity instead of firing a burst of requests.
        self.assertIn('QUEUE_CAPACITY', self.queue)
        self.assertIn('scheduleFeedRefill', self.queue)
        self.assertIn('scheduleReelsRefill', self.queue)
        # Capacity enforcement: a full queue stops asking for more.
        self.assertIn('feedQueueFull', self.queue)
        self.assertIn('reelsQueueFull', self.queue)

    def test_failure_cooldown_is_short_and_the_retry_is_bounded_to_one(self):
        # A short, fixed cooldown — never an immediate tight retry loop.
        self.assertIn('private static final long FAIL_COOLDOWN_MS', self.queue)
        self.assertIn('FAIL_COOLDOWN_MS = 5_000L', self.queue)
        # Retry is scheduled through the handler, so a failure can only ever produce one
        # follow-up attempt per cooldown window.
        fail = self.queue.split('public void onAdFailedToLoad(LoadAdError adError)', 1)[1]
        fail = fail.split('},', 1)[0]
        self.assertIn('mainHandler.postDelayed', fail)
        self.assertIn('lastFeedFailTime', self.queue)

    def test_first_request_waits_for_the_real_sdk_initialization(self):
        # The SDK-ready gate is what stops requests being issued into a half-booted SDK.
        self.assertIn('public static boolean isAdsSdkInitialized()', self.consent)
        self.assertIn('ConsentManager.isAdsSdkInitialized()', self.ads)
        self.assertIn('runWhenSdkInitialized', self.consent)
        # A guaranteed fallback so an ad slot can never wait forever on Play Services.
        self.assertIn('SDK_BOOT_PRELOAD_FALLBACK_MS = 4_000L', self.consent)
        self.assertIn('markReadyAndFlush(appCtx, true)', self.consent)
        # The fast unblock path still exists, but it does not fire real ad requests early.
        self.assertIn('markReadyAndFlush(appCtx, false), 900L', self.consent)
        self.assertIn('flushSdkReadyCallbacks();', self.consent)

    def test_pools_still_boot_from_application_oncreate(self):
        self.assertIn('ConsentManager.ensureInitialized(this)', (JAVA / 'App.java').read_text())
        # Pools are warmed from the SDK-ready path, not lazily on first scroll.
        self.assertIn('AdsConfig.warmUpPools(appCtx);', self.consent)
        self.assertIn('warmUpPools', self.ads)
        self.assertIn('AdQueueManager.getInstance().initialize(context);', self.ads)


if __name__ == '__main__':
    unittest.main()
