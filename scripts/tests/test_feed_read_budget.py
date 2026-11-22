"""Source guards for bounded on-demand reads; not a 5k-device load test."""
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'
class FeedReadBudgetTest(unittest.TestCase):
    def test_feed_first_page_is_twenty_and_cache_first(self):
        source=(JAVA/'main/HomeFragment.java').read_text()
        self.assertIn('private static final int PAGE = 20;',source)
        first=source.split('private void startPostsQuery(boolean forceReload)',1)[1].split('private void consumePostsSnapshot',1)[0]
        self.assertIn('feedQuery.limit(PAGE)', first)
        self.assertIn('FEATURED_REELS_PAGE = 20;', source)
        self.assertIn('first.refreshCachedHead(saved)',first)
        self.assertIn('if(forceReload)first.get(Source.SERVER)',first)
        self.assertIn('first.get(Source.CACHE)',first)
        self.assertNotIn('addSnapshotListener',first)
        self.assertIn('generation!=feedGeneration',first)
        self.assertIn('orderBy("createdAt",Query.Direction.DESCENDING)',first)
    def test_home_uses_page_engagement_without_background_engagement_reads(self):
        source=(JAVA/'main/HomeFragment.java').read_text()
        self.assertIn('feedQuery=query.withFeedEngagement()', source)
        self.assertIn('D1Reactions.seedFeed', source)
        self.assertNotIn('refreshVisibleEngagement', source)
        self.assertNotIn('watchLatestPosts', source)
        self.assertIn('>= 120_000L', source)
        adapter=(JAVA/'post/FeedAdapter.java').read_text()
        self.assertIn('!feedEngagementInPage &&', adapter)
    def test_pages_reuse_query_guard_duplicates_and_unlock_on_error(self):
        source=(JAVA/'main/HomeFragment.java').read_text()
        more=source.split('public void loadMore()',1)[1].split('public boolean hasMore()',1)[0]
        for token in ['if(loadingPosts','feedQuery.startAfter(lastDoc).limit(PAGE)','loadedPostIds.add','generation!=feedGeneration','addOnFailureListener','loadingPosts=false','matchesFilter(post,filter)']:
            self.assertIn(token,more)
        self.assertIn('existing.add(post.id)',(JAVA/'post/FeedAdapter.java').read_text())
    def test_stories_have_cursor_pages_and_discoverable_more_control(self):
        source=(JAVA/'main/HomeFragment.java').read_text()
        stories=source.split('private void startStoriesListener()',1)[1].split('// ---------- Host ----------',1)[0]
        for token in ['page.limit(10).get()','storyQuery.startAfter(storyCursor)','if(loadingStories','generation!=storyGeneration','storyIds.add','moreStories=snapshot.size()==10']:
            self.assertIn(token,stories)
        self.assertNotIn('addSnapshotListener',stories)
        self.assertIn('More stories ›',(JAVA/'post/FeedAdapter.java').read_text())
    def test_no_reaction_collection_scan_on_feed_bind_or_tap(self):
        adapter=(JAVA/'post/FeedAdapter.java').read_text()
        self.assertNotIn('Reactions.syncPostReactions(c, p)',adapter)
        rx=(JAVA/'post/Reactions.java').read_text()
        tap=rx[rx.index('DocumentReference postRef ='):rx.index('public static void syncPostReactions')]
        self.assertNotIn('reconcileFromCollection(c, post.id',tap)
        self.assertIn('w_600,c_limit,f_webp,q_auto:good/',(JAVA/'core/Ui.java').read_text())
if __name__=='__main__':unittest.main()
