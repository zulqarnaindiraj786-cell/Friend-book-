from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]

class ProfileReadFlightsWiring(unittest.TestCase):
    def test_only_cache_first_profiles_share_inflight_tasks(self):
        source=(ROOT/'app/src/main/java/com/friendbook/social/core/Fb.java').read_text()
        self.assertIn('USER_READS.read(java.util.Arrays.asList(actor,uid)',source)
        cached=source.split('private static Task<Models.User> readUserCacheFirst',1)[1].split('/** Refresh an author',1)[0]
        self.assertIn('get(Source.CACHE)',cached)
        self.assertIn('fetchUserNetwork(uid, actor, tcs)',cached)
        self.assertIn('java.util.Objects.equals(actor,Fb.uid())',cached)
        fresh=source.split('public static Task<Models.User> getUserFresh',1)[1].split('private static String lastCloudflareProfileKey',1)[0]
        self.assertNotIn('USER_READS',fresh)
        self.assertIn('fetchUserNetwork(uid,Fb.uid(),tcs)',fresh)
