"""Source guards for bounded on-demand reads; not a 5k-device load test."""
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'
class FeedReadBudgetTest(unittest.TestCase):
    def test_feed_first_page_is_ten_and_cache_first(self):
        source=(JAVA/'main/HomeFragment.java').read_text()
        self.assertIn('private static final int PAGE = 10;',source)
        first=source.split('private void startPostsQuery(boolean forceReload)',1)[1].split('private void consumePostsSnapshot',1)[0]
        self.assertIn('feedQuery.limit(PAGE)', first)
        self.assertNotIn('loadFeaturedReelsForHome', source)
        loader=(JAVA/'core/FeedHeadReads.java').read_text()
        self.assertIn('FeedHeadReads.load(first,forceReload',first)
        self.assertIn('first.get(Source.DEFAULT)',loader)
        self.assertNotIn('refreshCachedHead(saved)',loader)
        self.assertIn('if(forced){first.get(Source.SERVER)',loader)
        self.assertIn('first.get(Source.CACHE)',loader)
        self.assertNotIn('addSnapshotListener',first)
        self.assertIn('generation!=feedGeneration',first)
        self.assertIn('orderBy("createdAt",Query.Direction.DESCENDING)',first)
    def test_home_uses_page_engagement_without_background_engagement_reads(self):
        source=(JAVA/'main/HomeFragment.java').read_text()
        self.assertIn('feedQuery=query.withFeedEngagement()', source)
        self.assertIn('D1Reactions.seedFeed', source)
        self.assertNotIn('refreshVisibleEngagement', source)
        self.assertNotIn('watchLatestPosts', source)
        self.assertNotIn('>= 120_000L', source)
        self.assertIn('first.hasFreshLocalPage(FeedReadPolicy.REUSE_MS)', (JAVA/'core/FeedHeadReads.java').read_text())
        adapter=(JAVA/'post/FeedAdapter.java').read_text()
        self.assertIn('!feedEngagementInPage &&', adapter)
    def test_pages_reuse_query_guard_duplicates_and_unlock_on_error(self):
        source=(JAVA/'main/HomeFragment.java').read_text()
        more=source.split('public void loadMore()',1)[1].split('public boolean hasMore()',1)[0]
        for token in ['if(!homeStarted || isHidden() || loadingPosts','feedQuery.startAfter(lastDoc).limit(PAGE)','loadedPostIds.add','generation!=feedGeneration','addOnFailureListener','loadingPosts=false','matchesFilter(post,filter)']:
            self.assertIn(token,more)
        self.assertIn('existing.add(post.id)',(JAVA/'post/FeedAdapter.java').read_text())
    def test_stories_have_cursor_pages_and_discoverable_more_control(self):
        source=(JAVA/'main/HomeFragment.java').read_text()
        stories=source.split('private void startStoriesListener()',1)[1].split('// ---------- Host ----------',1)[0]
        for token in ['page.limit(3).getCachedOrFetch(forceSections,com.friendbook.social.core.StoryWindowPolicy.REUSE_MS)','storyQuery.startAfter(storyCursor)','if(loadingStories','generation!=storyGeneration','storyIds.add','moreStories=snapshot.size()==3']:
            self.assertIn(token,stories)
        self.assertNotIn('addSnapshotListener',stories)
        self.assertIn('More stories ›',(JAVA/'post/FeedAdapter.java').read_text())
        adapter=(JAVA/'post/FeedAdapter.java').read_text()
        rail=adapter.split('// 2. Tall Vertical Rounded Story Cards Rail',1)[1].split('View gap = new View(c);',1)[0]
        self.assertIn('storyLayout.setItemPrefetchEnabled(false)',rail)
        self.assertNotIn('loadMoreStories()',rail)
        self.assertIn('moreStories.setOnClickListener(v -> { if(host!=null) host.loadMoreStories(); });',adapter)
    def test_no_reaction_collection_scan_on_feed_bind_or_tap(self):
        adapter=(JAVA/'post/FeedAdapter.java').read_text()
        self.assertNotIn('Reactions.syncPostReactions(c, p)',adapter)
        rx=(JAVA/'post/Reactions.java').read_text()
        tap=rx[rx.index('DocumentReference postRef ='):rx.index('public static void syncPostReactions')]
        self.assertNotIn('reconcileFromCollection(c, post.id',tap)
        self.assertIn('w_600,c_limit,f_webp,q_auto:good/',(JAVA/'core/Ui.java').read_text())
if __name__=='__main__':unittest.main()
