from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


class SearchReadBudget(unittest.TestCase):
    def test_typing_is_debounced_and_all_search_heads_use_short_exact_query_reuse(self):
        search = (JAVA / 'friends/SearchActivity.java').read_text()
        self.assertIn('handler.postDelayed(pending, com.friendbook.social.core.SearchReadPolicy.DEBOUNCE_MS)', search)
        self.assertEqual(search.count('startCachedFor(com.friendbook.social.core.SearchReadPolicy.EXACT_PAGE_REUSE_MS)'), 3)
        self.assertIn('handler.removeCallbacks(pending)', search)
        self.assertIn('if(tagSearch)postQuery=postQuery.matchingPostTag(q)', search)
        self.assertIn('if(tagSearch || hay.contains(needle))', search)
        self.assertIn('postPages.next()', search)
        self.assertNotIn('startCachedDay()', search)

    def test_reuse_keeps_existing_account_invalidation_and_freshness_checks(self):
        pager = (JAVA / 'core/CursorPager.java').read_text()
        self.assertIn('startLocal(Math.max(0L,maxAgeMs),true)', pager)
        self.assertIn('cached!=null && maxAgeMs>0 && head.hasFreshLocalPage(maxAgeMs)', pager)
        self.assertIn('head.get(maxAgeMs>0 ? Source.DEFAULT : Source.SERVER)', pager)
        self.assertIn('request!=requestGeneration', pager)
        policy = (JAVA / 'core/SearchReadPolicy.java').read_text()
        self.assertIn('500L', policy)
        self.assertIn('30_000L', policy)

    def test_people_prefix_uses_bounded_ordered_server_path(self):
        search = (JAVA / 'friends/SearchActivity.java').read_text()
        for term in ['q', 'cap']:
            self.assertIn('orderBy("displayName",Query.Direction.ASCENDING).startAt('+term+').endAt('+term+'+"\\uf8ff")', search)
        self.assertNotIn('whereGreaterThanOrEqualTo("displayName"', search)
        self.assertIn('peopleA.next()', search)
        self.assertIn('peopleB.next()', search)
