import pathlib
import unittest

HOME = pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social/main/HomeFragment.java'


class HomeNetworkPriorityTest(unittest.TestCase):
    def test_secondary_work_is_one_shot_and_account_scoped(self):
        source = HOME.read_text()
        start = source.split('private void startVisibleHomeContent()', 1)[1].split('private void startHomeWatchers()', 1)[0]
        self.assertIn('cancelHomeSecondary()', start)
        self.assertIn('pendingHomeSecondary = null', start)
        self.assertIn('!actor.equals(Fb.uid())', start)
        self.assertIn('loadingPosts ? HOME_SECONDARY_MAX_WAIT_MS : 0L', start)
        self.assertEqual(start.count('postDelayed('), 1)

    def test_manual_refresh_also_prioritizes_feed(self):
        source = HOME.read_text()
        refresh = source.split('void refreshWithFriendbookAlgorithm(boolean scrollTop)', 1)[1].split('startVisibleHomeContent();', 1)[0]
        self.assertIn('startPostsQuery(explicitUserRefresh)', refresh)
        for request in ('loadPymk()', 'startStoriesListener(true)', 'loadFeaturedReelsForHome()', 'loadNotesUsers()'):
            self.assertNotIn(request, refresh)
        self.assertIn('startStoriesListener();', source)
        for reset in ('pymkLoaded=false', 'featuredReelsLoaded=false', 'notesReadAt ='):
            self.assertNotIn(reset, refresh)

    def test_notes_and_suggestions_share_one_read(self):
        source = HOME.read_text()
        self.assertNotIn('private void loadPymk()', source)
        self.assertIn('pymkCandidates.size() < 10', source)
        self.assertIn('notesActor,Fb.uid()', source)

    def test_secondary_work_cancels_on_hide_and_stop(self):
        source = HOME.read_text()
        for method in ('void onHiddenChanged(boolean hidden)', 'void onStop()'):
            self.assertIn('invalidateHomeReads()', source.split(method, 1)[1][:360])
        self.assertIn('homeReadGeneration++;friendFactsGeneration++;friendFactsPending.clear()', source)
        self.assertIn('cancelHomeSecondary();engagementHandler.removeCallbacksAndMessages(null)',source)
        self.assertIn('consumePostsSnapshot(results,!offline);\n            releaseHomeSecondary();', source)
