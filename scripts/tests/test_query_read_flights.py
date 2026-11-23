from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'app/src/main/java/com/friendbook/social/d1/documents'


class QueryReadFlightsWiring(unittest.TestCase):
    def test_default_reads_share_exact_request_before_scheduling_not_cache_or_server(self):
        source = (BASE / 'Query.java').read_text()
        read = source.split('public Task<QuerySnapshot> get(Source source)', 1)[1].split('public boolean hasFreshLocalPage', 1)[0]
        self.assertIn('D1DocumentStore.queryCacheKey(this, actor)', read)
        self.assertIn('queryReadFlights.read(actor, key, source == Source.DEFAULT', read)
        self.assertIn('() -> firstFeedPage ? firestore.runFeedFirstPage(fetch) : firestore.runAsync(fetch)', read)
        cache = read.split('java.util.concurrent.Callable<QuerySnapshot> fetch', 1)[0]
        self.assertNotIn('queryReadFlights', cache)
        cached = source.split('public Task<QuerySnapshot> getCachedOrFetch(boolean force,long maxAgeMs)', 1)[1].split('public Task<QuerySnapshot> get(Source source)', 1)[0]
        self.assertIn('if (force) return get(Source.SERVER)', cached)
        self.assertIn('if (!hasFreshLocalPage(maxAgeMs)) return get(Source.DEFAULT)', cached)

    def test_mutations_and_pushes_detach_query_work(self):
        store = (BASE / 'D1DocumentStore.java').read_text()
        for start, end in [('void invalidateDoc(String path)', 'ListenerRegistration registerDocListener'),
                           ('void notifyCollectionListeners(String parentPath)', 'private void pollActiveListeners')]:
            self.assertIn('queryReadFlights.invalidateAll()', store.split(start, 1)[1].split(end, 1)[0])
