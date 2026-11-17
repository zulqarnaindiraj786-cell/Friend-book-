"""Source guards for bounded on-demand reads; not a 5k-device load test."""
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'
class FeedReadBudgetTest(unittest.TestCase):
    def test_feed_first_page_is_fifteen_and_reads_only_d1(self):
        # The first page is one bounded D1 query. It used to be a Firestore get() with a cache
        # variant and an unordered retry behind it - three reads, from a database the app no
        # longer writes the feed to. 15 posts per page: a 7-post page cost a round trip every
        # few swipes, which is what made scrolling feel like fetching.
        source=(JAVA/'main/HomeFragment.java').read_text()
        self.assertIn('private static final int PAGE = 15;',source)
        first=source.split('private void startPostsQuery(boolean ordered)',1)[1].split('private void consumePostsFresh',1)[0]
        self.assertIn('CloudflareStore.query(feedQuery',first)
        self.assertIn('DocQuery.of("posts")',first)
        self.assertIn('orderByDescending("createdAt")',first)
        self.assertNotIn('first.get()',first)
        self.assertNotIn('Source.CACHE',first)
        self.assertNotIn('addSnapshotListener',first)
        self.assertIn('generation != feedGeneration',first)
    def test_pages_reuse_query_guard_duplicates_and_unlock_on_error(self):
        source=(JAVA/'main/HomeFragment.java').read_text()
        more=source.split('public void loadMore()',1)[1].split('public boolean hasMore()',1)[0]
        # Page 2 must use the same feedQuery as page 1, so the worker's cursor is always
        # understood by the request that uses it.
        for token in ['if (loadingPosts)','CloudflareStore.query(feedQuery','loadedPostIds.add','generation != feedGeneration','loadingPosts = false','matchesFilter(post, filter)']:
            self.assertIn(token,more)
        self.assertNotIn('feedQuery.startAfter(lastDoc)',more)
        self.assertIn('existing.add(post.id)',(JAVA/'post/FeedAdapter.java').read_text())
    def test_stories_have_cursor_pages_and_discoverable_more_control(self):
        source=(JAVA/'main/HomeFragment.java').read_text()
        stories=source.split('private void startStoriesListener()',1)[1].split('// ---------- Host ----------',1)[0]
        # The story strip pages on a D1 DocQuery with the server's own cursor now; the tokens pin
        # the same contract (a bounded page, a resumable cursor, generation guarding, more-detection)
        # against the query object that carries it.
        for token in ['limit(10)','qb.startAfter(storyCursor)','if(loadingStories','generation!=storyGeneration','storyIds.add','moreStories=page!=null && page.hasMore']:
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
