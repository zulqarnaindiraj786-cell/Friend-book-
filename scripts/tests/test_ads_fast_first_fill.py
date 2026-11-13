"""Source contract guards for fast ad first-fill (no retry loops, no premature SDK requests)."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


class AdsFastFirstFillGuards(unittest.TestCase):
    def test_empty_pool_asks_for_one_ad_and_tops_up_after_it_lands(self):
        source = (JAVA / 'core/AdsConfig.java').read_text()
        self.assertIn('int reqCount = FEED_POOL.isEmpty()', source)
        self.assertIn('int reqCount = REELS_POOL.isEmpty()', source)
        self.assertIn('if (isFeedPool) ensureFeedPoolReplenished(appCtx);', source)
        self.assertIn('else ensureReelsPoolReplenished(appCtx);', source)

    def test_failure_cooldown_is_short_and_the_retry_is_bounded_to_one(self):
        source = (JAVA / 'core/AdsConfig.java').read_text()
        self.assertIn('private static final long FAIL_COOLDOWN_MS = 8_000L;', source)
        self.assertIn('private static final long FIRST_FILL_RETRY_DELAY_MS = 3_000L;', source)
        # The retry flag is read and latched in the same guarded block: exactly one auto retry.
        for pool in ['feed', 'reels']:
            self.assertIn(f'scheduleFirstFillRetry = !{pool}FirstFillRetryUsed;', source)
            self.assertIn(f'{pool}FirstFillRetryUsed = true;', source)
        self.assertIn('if (scheduleFirstFillRetry)', source)

    def test_first_request_waits_for_the_real_sdk_initialization(self):
        ads = (JAVA / 'core/AdsConfig.java').read_text()
        self.assertIn('if (!ConsentManager.isAdsSdkInitialized())', ads)
        self.assertIn('ConsentManager.runWhenSdkInitialized(appCtx,', ads)
        consent = (JAVA / 'core/ConsentManager.java').read_text()
        self.assertIn('public static boolean isAdsSdkInitialized()', consent)
        self.assertIn('private static final long SDK_BOOT_PRELOAD_FALLBACK_MS = 4_000L;', consent)
        self.assertIn('markReadyAndFlush(appCtx, true)', consent)
        # The 900ms unblock still exists so no UI waiter can hang, but it no longer fires ads early.
        self.assertIn('markReadyAndFlush(appCtx, false), 900L', consent)
        self.assertIn('flushSdkReadyCallbacks();', consent)
        self.assertIn('if (sdkInitCompleted.compareAndSet(false, true))', consent)

    def test_pools_still_boot_from_application_oncreate(self):
        self.assertIn('ConsentManager.ensureInitialized(this)', (JAVA / 'App.java').read_text())
        consent = (JAVA / 'core/ConsentManager.java').read_text()
        self.assertIn('AdsConfig.preloadFeedBatchNow(appCtx, AdsConfig.AD_POOL_SIZE);', consent)
        self.assertIn('AdsConfig.warmUpPools(appCtx);', consent)


if __name__ == '__main__':
    unittest.main()
