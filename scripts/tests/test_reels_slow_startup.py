import pathlib
import unittest

REELS = pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social/reels'

class ReelsSlowStartupTest(unittest.TestCase):
    def test_low_bandwidth_variant_used_for_playback_and_prefetch(self):
        cache = (REELS / 'ReelVideoCache.java').read_text()
        self.assertIn('preferLiteStartup(context) ? lowBandwidthUrl(url) : startup480Url(url)', cache)
        self.assertIn('startupUrl(appCtx, rawVideoUrl)', cache)
        reel = (REELS / 'ReelsFragment.java').read_text()
        self.assertIn('startupUrl(ctx, targetUrl)', reel)
        self.assertIn('!ReelVideoCache.preferLiteStartup(context)', reel)

    def test_buffering_timeout_exposes_tappable_retry(self):
        reel = (REELS / 'ReelsFragment.java').read_text()
        self.assertIn('watchStartup(player, context, null, false)', reel)
        self.assertIn('playbackRetry.setVisibility(View.VISIBLE)', reel)
        self.assertIn('playbackRetry.setOnClickListener(v -> togglePlayPause())', reel)

    def test_next_reel_warms_after_first_frame_and_yields_on_rebuffer(self):
        reel = (REELS / 'ReelsFragment.java').read_text()
        handler = reel.split('private boolean handlePlaybackInfo(', 1)[1].split('private void cancelStartupWatchdog', 1)[0]
        rendered, buffering = handler.split('MEDIA_INFO_BUFFERING_START', 1)
        self.assertIn('MEDIA_INFO_VIDEO_RENDERING_START', rendered)
        self.assertIn('warmUpcomingAfterPlay', rendered)
        self.assertIn('cancelUpcomingWarm', buffering)
