from pathlib import Path
import unittest

JAVA = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social/core'


class EngagementOperationFlights(unittest.TestCase):
    def test_gets_share_before_executor_and_keep_private_json(self):
        source = (JAVA / 'D1Client.java').read_text()
        wrapper = source.split('private static Task<JSONObject> request(', 1)[1].split('private static Task<JSONObject> executeRequest', 1)[0]
        self.assertIn('READ_OPERATIONS.read(key,()->executeRequest(', wrapper)
        self.assertIn('Arrays.asList(actor,collection,id,kind,effectiveQuery,interactive?', wrapper)
        self.assertIn('return new JSONObject(completed.getResult())', wrapper)
        self.assertIn('if(!Objects.equals(actor,Fb.uid()))throw new IOException', wrapper)
        self.assertNotIn('pool.execute', wrapper)

    def test_writes_detach_reads_at_both_boundaries(self):
        source = (JAVA / 'D1Client.java').read_text()
        wrapper = source.split('private static Task<JSONObject> request(', 1)[1].split('private static Task<JSONObject> executeRequest', 1)[0]
        self.assertEqual(wrapper.count('invalidateResourceReads(READ_OPERATIONS,collection,id)'), 2)
        self.assertIn('invalidateMatching(key->key.size()<3 || (collection.equals(key.get(1)) && id.equals(key.get(2))))', (JAVA / 'D1Client.java').read_text())
        self.assertIn('if(!"GET".equals(method) || body!=null)', wrapper)
        self.assertLess(wrapper.index('invalidateResourceReads(READ_OPERATIONS,collection,id)'), wrapper.index('Task<JSONObject> write=executeRequest'))
        self.assertIn('write.addOnCompleteListener(Runnable::run', wrapper)
