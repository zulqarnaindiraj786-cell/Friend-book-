"""Contract guards for the ad behaviour the product spec actually asks for.

The rules, verbatim from the requirement: one native ad after every three posts, three ads
preloaded when the app opens, and as soon as one has been shown a fresh one is fetched so the
queue never runs dry. Also that Meta answers through AdMob mediation rather than on its own
code path - running both would double-request and let Meta fill outside the waterfall.

These are source-contract tests, not runtime tests: the ad stack needs a device, Play
services and a live AdMob account, none of which exist on CI.
"""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/com/friendbook/social"


class FeedIntervalTest(unittest.TestCase):
    def setUp(self):
        self.feed = (JAVA / "post/FeedAdapter.java").read_text()

    def test_one_ad_after_every_three_posts(self):
        self.assertRegex(self.feed, r"NATIVE_AD_EVERY_N_POSTS\s*=\s*3\b")
        # Step is "3 posts then the ad", i.e. every fourth slot.
        self.assertRegex(self.feed, r"NATIVE_AD_STEP\s*=\s*NATIVE_AD_EVERY_N_POSTS\s*\+\s*1")

    def test_no_ad_before_the_first_three_posts(self):
        # A feed of two posts must not open with an advertisement.
        self.assertIn("posts.size() < NATIVE_AD_EVERY_N_POSTS", self.feed)

    def test_ad_slots_are_interleaved_not_appended(self):
        # Post index and slot index have to account for the ads already inserted, otherwise
        # the fourth post renders under the first ad.
        self.assertIn("postIndexForSlot", self.feed)
        self.assertIn("slotCount()", self.feed)


class PreloadThreeTest(unittest.TestCase):
    def setUp(self):
        self.queue = (JAVA / "core/AdQueueManager.java").read_text()

    def test_three_ads_are_preloaded_per_queue(self):
        self.assertRegex(self.queue, r"QUEUE_CAPACITY\s*=\s*3\b")

    def test_both_queues_start_at_app_open(self):
        self.assertIn("preloadFeedQueue()", self.queue)
        self.assertIn("preloadReelsQueue()", self.queue)

    def test_serving_one_ad_immediately_asks_for_another(self):
        # The requirement is "show one, get the next one". A queue that only refills when it
        # hits zero leaves a visible gap for the user, so the top-up has to be triggered by
        # the pop, not by the emptiness.
        pop = self.queue.index("public synchronized NativeAd pollFeedAd()")
        body = self.queue[pop:pop + 900]
        self.assertIn("feedQueue.poll()", body)
        self.assertIn("scheduleFeedRefill()", body)
        self.assertRegex(body, r"feedQueue\.size\(\)\s*<\s*QUEUE_CAPACITY")

    def test_a_full_queue_does_not_keep_requesting(self):
        self.assertIn("feedQueueFull", self.queue)
        self.assertIn("reelsQueueFull", self.queue)

    def test_showing_a_reels_ad_refills_the_reels_queue(self):
        self.assertIn("onReelsAdImpression", self.queue)
        pop = self.queue.index("public void onReelsAdImpression()")
        body = self.queue[pop:pop + 400]
        self.assertIn("scheduleReelsRefill()", body)


class MediationTest(unittest.TestCase):
    def setUp(self):
        self.ads = (JAVA / "core/AdsConfig.java").read_text()
        self.queue = (JAVA / "core/AdQueueManager.java").read_text()
        self.gradle = (ROOT / "app/build.gradle").read_text()
        self.manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()

    def test_meta_reaches_the_app_through_the_admob_adapter(self):
        self.assertIn("com.google.ads.mediation:facebook", self.gradle)
        self.assertIn("com.facebook.android:audience-network-sdk", self.gradle)

    def test_the_meta_ids_agree_with_each_other(self):
        app_id = re.search(r'META_APP_ID\s*=\s*"([^"]+)"', self.ads).group(1)
        placement = re.search(r'META_PLACEMENT_ID\s*=\s*"([^"]+)"', self.ads).group(1)
        self.assertEqual("4871960679794940", app_id)
        self.assertEqual("4871960679794940_4871960806461594", placement)
        # A placement from a different app ID silently never fills.
        self.assertTrue(placement.startswith(app_id + "_"))

    def test_the_manifest_initialises_the_same_meta_app(self):
        self.assertIn('name="facebook_app_id">4871960679794940<',
                      (ROOT / "app/src/main/res/values/strings.xml").read_text())
        self.assertIn("com.facebook.sdk.ApplicationId", self.manifest)

    def test_ads_come_from_the_admob_feed_unit_not_a_meta_unit(self):
        # Every request must name the AdMob unit. Requesting a Meta placement directly would
        # bypass the waterfall and double-count the impression.
        self.assertIn("FEED_NATIVE_ID", self.queue)
        self.assertIn("ca-app-pub-3387480177023669/4957447582", self.ads)


if __name__ == "__main__":
    unittest.main()
