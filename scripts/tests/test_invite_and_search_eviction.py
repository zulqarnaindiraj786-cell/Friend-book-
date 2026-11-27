from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class InviteAndSearchEviction(unittest.TestCase):
    def test_invite_card_is_below_logout_and_is_local_only(self):
        menu=(JAVA/'main/MenuFragment.java').read_text()
        self.assertLess(menu.index('body.addView(logout)'),menu.index('body.addView(inviteFriendsCard(a))'))
        helper=(JAVA/'core/InviteFriends.java').read_text()
        self.assertIn('AdsConfig.PLAY_STORE_URL',helper)
        for token in ['com.whatsapp','com.whatsapp.w4b','Intent.createChooser']:
            self.assertIn(token,helper)
        for token in ['Fb.db()', 'HttpURLConnection', 'newCall(', 'postDelayed']:
            self.assertNotIn(token,helper)
    def test_search_bucket_is_isolated_but_collection_invalidation_is_shared(self):
        store=(JAVA/'d1/documents/D1DocumentStore.java').read_text()
        self.assertIn('QueryCachePolicy.preferencesForQuery(key)',store)
        freshness=store.split('boolean isQueryCacheFresh(',1)[1].split('synchronized void cacheQuery',1)[0]
        self.assertIn('QueryCachePolicy.preferencesFor(parent)',freshness)
        self.assertIn('long saved=queryPreferences(app,key)',freshness)
        policy=(JAVA/'d1/documents/QueryCachePolicy.java').read_text()
        self.assertIn('d1_user_search_query_cache_v1',policy)
