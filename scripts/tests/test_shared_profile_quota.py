"""Source wiring guards; no device/network benchmark claimed."""
from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class SharedProfileQuota(unittest.TestCase):
    def test_menu_shares_local_profile_and_detaches_when_hidden(self):
        s=(JAVA/'main/MenuFragment.java').read_text()
        self.assertNotIn('addSnapshotListener',s)
        self.assertIn('Fb.addMeObserver(meObserver)',s)
        self.assertIn('if(hidden) Fb.removeMeObserver(meObserver)',s)
        self.assertIn('u.uid,Fb.uid()',s)
        self.assertIn('Icons.setTickBadge(tickIv,u.tickLevel,false)',s)
    def test_profile_reuse_keeps_account_switch_error_and_ban_handling(self):
        s=(JAVA/'core/Fb.java').read_text()
        ensure=s.split('public static void ensureMeFresh()',1)[1].split('public static String myName()',1)[0]
        self.assertIn('OnDemandLoads.profileListenerNeedsRestart',ensure)
        self.assertNotIn('60_000',ensure)
        self.assertIn('meListenerFailed=true',s)
        self.assertIn('observer.changed(u)',s)
        self.assertIn('if (u.banned',s)
        self.assertIn('auth().signOut()',s)
    def test_reel_cache_has_no_speculative_network_downloader(self):
        s=(JAVA/'reels/ReelVideoCache.java').read_text()
        for forbidden in ['HttpURLConnection','prefetchSingle','warmUpcomingAfterPlay','PREFETCH_POOL']:
            self.assertNotIn(forbidden,s)
