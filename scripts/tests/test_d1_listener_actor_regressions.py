import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social/d1/documents'

class D1ActorAndListenerRegression(unittest.TestCase):
    def test_queued_operations_and_pollers_are_bound_to_submitting_actor(self):
        store = (ROOT / 'D1DocumentStore.java').read_text()
        transport = (ROOT / 'D1DocumentTransport.java').read_text()
        self.assertIn('final String actor=authenticatedUid();\n        try {\n            executor.execute', store)
        self.assertIn('D1DocumentTransport.runForActor(actor, task)', store)
        self.assertEqual(store.count('D1DocumentTransport.runForActor(entry.actor'), 2)
        self.assertIn('if (!actor.equals(user.getUid()))', transport)
        self.assertGreaterEqual(transport.count('requireActor(actor);'), 3)
        self.assertIn('finally { REQUEST_ACTOR.remove(); }', transport)

    def test_document_listener_compares_response_and_recovers_after_error(self):
        source = (ROOT / 'D1DocumentStore.java').read_text()
        doc = source[source.index('private void deliverDoc'):source.index('private void deliverQuery')]
        self.assertIn('entry.deliveryState.server(response.toString())', doc)
        self.assertIn('entry.deliveryState.error()', doc)
        self.assertNotIn('lastUpdate', doc)

if __name__ == '__main__':
    unittest.main()
