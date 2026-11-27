import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social/d1/documents'

class D1ActorAndListenerRegression(unittest.TestCase):
    def test_queued_operations_and_pollers_are_bound_to_submitting_actor(self):
        store = (ROOT / 'D1DocumentStore.java').read_text()
        transport = (ROOT / 'D1DocumentTransport.java').read_text()
        self.assertIn('final String actor=authenticatedUid();\n        try {\n            lane.execute', store)
        self.assertIn('D1DocumentTransport.runForActor(actor, task)', store)
        self.assertIn('readListenerQueryResponse(entry.actor,body,lane,forceDelivery)',store)
        self.assertIn('listenerQueryReadFlights.read(actor,(explicit ? "explicit:" : "automatic:")+wire,true',store)
        self.assertIn('D1DocumentTransport.runForActor(actor,()->', store)
        self.assertIn('entry.ref.includesProfileAudience(),true,listenerExecutor,true)',store)
        self.assertIn('documentReadFlights.readPromotable(',store)
        self.assertIn('actor,documentReadKey(path,audience),share,!automatic',store)
        self.assertIn(': inbox ? inboxExecutor : listenerExecutor;',store)
        self.assertIn('if(entry.refreshPending.get())scheduleQueryRead(entry);',store)
        self.assertIn('new java.util.concurrent.ArrayBlockingQueue<>(128)', store)
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
