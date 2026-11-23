from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
class EngagementReadBudget(unittest.TestCase):
    def test_coalescer_is_wired_only_for_bodyless_gets_and_each_consumer_parses_json(self):
        source=(ROOT/'app/src/main/java/com/friendbook/social/core/D1Client.java').read_text()
        self.assertIn('READS.execute("GET".equals(method) && body==null,actor,url.toString()',source)
        self.assertIn('final JSONObject data=new JSONObject(response)',source)
        self.assertIn('if(failure.status!=401 || !Objects.equals(actor,Fb.uid()))throw failure;',source)
        self.assertIn('MAIN.post(()->{if(Objects.equals(actor,Fb.uid()))',source)
    def test_no_ttl_cache_and_mutations_invalidate_before_and_after(self):
        source=(ROOT/'app/src/main/java/com/friendbook/social/core/EngagementReadCoalescer.java').read_text()
        self.assertIn('active.remove(key, candidate)',source)
        self.assertIn('finally { epoch.incrementAndGet(); }',source)
        self.assertNotIn('currentTimeMillis',source)
        self.assertNotIn('sleep(',source)
