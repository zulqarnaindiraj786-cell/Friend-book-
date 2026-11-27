from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]

class BadgeLookupBudget(unittest.TestCase):
    def test_badge_reads_use_single_flight_cache_and_scoped_completion(self):
        source=(ROOT/'app/src/main/java/com/friendbook/social/core/TickCache.java').read_text()
        self.assertIn('STATE.bind(actor,uid,iv',source)
        self.assertIn('if (flight==null) return;',source)
        self.assertIn('author.get(Source.CACHE)',source)
        self.assertIn('STATE.current(Fb.uid(),flight)',source)
        self.assertIn('STATE.finish(actor,flight,level',source)
        self.assertNotIn('iv.setTag(',source)
        self.assertNotIn('postDelayed',source)
        self.assertNotIn('fetchRemote(',source)
