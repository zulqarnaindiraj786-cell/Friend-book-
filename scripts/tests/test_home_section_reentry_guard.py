from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class HomeSectionReentryGuard(unittest.TestCase):
 def test_secondary_launch_is_guarded_before_scheduling_and_claimed_only_when_visible(self):
  s=(ROOT/'main/HomeFragment.java').read_text().split('private void startVisibleHomeContent()',1)[1].split('@Override public void onDiscoveryVisible()',1)[0]
  self.assertLess(s.index('HomeSectionReadPolicy.shouldStart'),s.index('pendingHomeSecondary = () ->'))
  self.assertLess(s.index('if (!homeStarted'),s.index('headerRequested=true;headerRequestedActor=actor;'))
  self.assertLess(s.index('headerRequested=true;headerRequestedActor=actor;'),s.index('startHomeWatchers();'))
 def test_explicit_refresh_can_recover_failed_sections(self):
  s=(ROOT/'main/HomeFragment.java').read_text()
  refresh=s.split('private void refreshWithFriendbookAlgorithm',1)[1].split('public void onWarmSessionResume',1)[0]
  self.assertIn('headerRequested=false;',refresh)
  self.assertIn('startVisibleHomeContent();',refresh)
