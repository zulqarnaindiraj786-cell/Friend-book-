from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'app/src/main/java/com/friendbook/social/d1/documents'


class DocumentReadFlightsWiring(unittest.TestCase):
    def test_only_default_network_reads_share_and_cache_still_bypasses(self):
        source = (BASE / 'DocumentReference.java').read_text()
        read = source.split('public Task<DocumentSnapshot> get(Source source)', 1)[1].split('DocumentSnapshot parseSnapshot', 1)[0]
        cache, network = read.split('final String actor', 1)
        self.assertIn('source == Source.CACHE', cache)
        self.assertNotIn('documentReadFlights', cache)
        self.assertIn('readDocumentResponse(actor,path,profileAudience,source == Source.DEFAULT)', network)
        self.assertIn('new JSONObject(completed.getResult())', network)
        self.assertEqual(network.count('actor.equals(D1DocumentStore.authenticatedUid())'), 1)
        self.assertIn('firestore.cacheDoc(path, snap, actor)', network)

    def test_mutation_invalidation_detaches_flights_without_caching_results(self):
        store = (BASE / 'D1DocumentStore.java').read_text()
        invalidation = store.split('void invalidateDoc(String path)', 1)[1].split('ListenerRegistration registerDocListener', 1)[0]
        self.assertIn('documentReadFlights.invalidate(actor,documentReadKey(path,false))', invalidation)
        self.assertIn('documentReadFlights.invalidate(actor,documentReadKey(path,true))', invalidation)
        helper = (BASE / 'DocumentReadFlights.java').read_text()
        self.assertIn('active.remove(key, flight)', helper)
        self.assertNotIn('postDelayed', helper)
        self.assertNotIn('cacheDoc', helper)
