import unittest
from pathlib import Path

SOURCE = Path('app/src/main/java/com/friendbook/social/reels/ReelsFragment.java')

class ReelVisibleDemandTest(unittest.TestCase):
    def test_page_selection_does_not_start_an_unseen_network_page(self):
        source = SOURCE.read_text()
        selection = source.split('public void onPageSelected(int position)', 1)[1].split('// Banner is outside', 1)[0]
        self.assertNotIn('loadNextVideos()', selection)
        self.assertIn('moreVideos.setOnClickListener(v -> loadNextVideos())', source)
        self.assertIn('appendEndlessCycleIfNeeded()', selection)  # cache-only loop remains

    def test_hidden_or_paused_fragment_does_not_start_or_replay_pages(self):
        source = SOURCE.read_text()
        onstart = source.split('public void onStart()', 1)[1].split('public void focusFeaturedReel', 1)[0]
        self.assertNotIn('resetVideoPages()', onstart)
        paging = source.split('private void loadNextVideos()', 1)[1].split('private void startVisibleVideosIfNeeded()', 1)[0]
        self.assertIn('!isResumed() || isHidden()', paging)
        resume = source.split('@Override public void onResume()', 1)[1].split('@Override public void onPause()', 1)[0]
        pause = source.split('@Override public void onPause()', 1)[1].split('@Override public void onDestroyView()', 1)[0]
        hidden = source.split('public void onHiddenChanged(boolean hidden)', 1)[1].split('public void onStop()', 1)[0]
        self.assertIn('startVisibleVideosIfNeeded()', resume)
        self.assertIn('retireVideoLoad()', pause)
        self.assertIn('retireVideoLoad()', hidden)
        self.assertNotIn('loadNextVideos()', hidden)
        helper = source.split('private void startVisibleVideosIfNeeded()', 1)[1].split('@Override public void onResume()', 1)[0]
        for guard in ('isAdded()', 'isResumed()', '!isHidden()', '!pagingVideos', '!reelsLoadFailed'):
            self.assertIn(guard, helper)

if __name__ == '__main__':
    unittest.main()
