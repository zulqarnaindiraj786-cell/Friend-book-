import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'

class ReelTapMediation(unittest.TestCase):
    def test_poster_and_overlay_open_the_same_selected_reel(self):
        source = (JAVA / 'post/FeedAdapter.java').read_text()
        handler = source.split('vh.playBadge.setText("Tap to watch Reel")', 1)[1].split('// ----------', 1)[0]
        for token in ['watchReel', 'playBadge.setOnClickListener(watchReel)', 'image.setOnClickListener(watchReel)', 'mediaWrap.setOnClickListener(watchReel)', 'i.putExtra("video_url", p.mediaUrl)']:
            self.assertIn(token, handler)
        self.assertIn('vh.playBadge.setOnClickListener(null)', source)

    def test_native_ad_pool_does_not_bypass_mediation(self):
        source = (JAVA / 'core/AdsConfig.java').read_text()
        self.assertIn('adLoader.loadAd(', source)
        self.assertNotIn('adLoader.loadAds(', source)
        self.assertIn('!ConsentManager.canRequestAds(appCtx)', source)
