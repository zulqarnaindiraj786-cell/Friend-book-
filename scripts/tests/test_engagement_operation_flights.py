from pathlib import Path
import unittest

JAVA = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social/core'


class EngagementOperationFlights(unittest.TestCase):
    def test_gets_share_before_executor_and_keep_private_json(self):
        source = (JAVA / 'D1Client.java').read_text()
        wrapper = source.split('private static Task<JSONObject> request(', 1)[1].split('private static Task<JSONObject> executeRequest', 1)[0]
        self.assertIn('READ_OPERATIONS.read(key,()->executeRequest(', wrapper)
        self.assertIn('Arrays.asList(actor,collection,id,kind,query,interactive?', wrapper)
        self.assertIn('return new JSONObject(completed.getResult())', wrapper)
        self.assertIn('if(!Objects.equals(actor,Fb.uid()))throw new IOException', wrapper)
        self.assertNotIn('pool.execute', wrapper)

    def test_writes_detach_reads_at_both_boundaries(self):
        source = (JAVA / 'D1Client.java').read_text()
        wrapper = source.split('private static Task<JSONObject> request(', 1)[1].split('private static Task<JSONObject> executeRequest', 1)[0]
        self.assertEqual(wrapper.count('READ_OPERATIONS.invalidateAll()'), 2)
        self.assertIn('if(!"GET".equals(method) || body!=null)', wrapper)
        self.assertLess(wrapper.index('READ_OPERATIONS.invalidateAll()'), wrapper.index('Task<JSONObject> write=executeRequest'))
        self.assertIn('write.addOnCompleteListener(Runnable::run', wrapper)
