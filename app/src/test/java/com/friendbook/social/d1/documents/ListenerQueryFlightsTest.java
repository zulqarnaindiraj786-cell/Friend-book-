package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class ListenerQueryFlightsTest {
    @Test public void hundredIdenticalListenersQueueOneTransportWithIndependentJson() throws Exception {
        D1DocumentStore store=D1DocumentStore.getInstance();store.listenerQueryReadFlights.invalidateAll();
        for(String parent:new String[]{"posts","users","notifications","chats"}) {
            try(MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
                MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class,CALLS_REAL_METHODS)) {
                identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
                transport.when(()->D1DocumentTransport.listenerQuery(any(JSONObject.class)))
                    .thenReturn(new JSONObject("{\"documents\":[],\"revisions\":{}}"));
                transport.clearInvocations();
                ListenerDocumentFlightsTest.Queue first=new ListenerDocumentFlightsTest.Queue(),other=new ListenerDocumentFlightsTest.Queue();
                JSONObject body=new JSONObject().put("parent",parent).put("limit",20).put("knownRevisions",new JSONObject());
                Task<String> shared=store.readListenerQueryResponse("actor",body,first);
                for(int i=0;i<100;i++)assertSame(shared,store.readListenerQueryResponse("actor",new JSONObject(body.toString()),other));
                assertEquals(1,first.tasks.size());assertEquals(0,other.tasks.size());
                first.drain();assertTrue(shared.isSuccessful());
                transport.verify(()->D1DocumentTransport.listenerQuery(any(JSONObject.class)),times(1));
                JSONObject left=new JSONObject(shared.getResult()),right=new JSONObject(shared.getResult());
                left.getJSONArray("documents").put("changed");assertEquals(0,right.getJSONArray("documents").length());
                assertNotSame(shared,store.readListenerQueryResponse("actor",body,other));other.drain();
            }
        }
    }
    @Test public void revisionsAccountAndMutationAreIsolatedAndQueuedBodyIsFrozen() throws Exception {
        D1DocumentStore store=D1DocumentStore.getInstance();store.listenerQueryReadFlights.invalidateAll();
        try(MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class,CALLS_REAL_METHODS)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
            transport.when(()->D1DocumentTransport.listenerQuery(any(JSONObject.class))).thenAnswer(call->{
                JSONObject body=call.getArgument(0);assertFalse(body.has("mutated"));
                return new JSONObject("{\"documents\":[]}");
            });transport.clearInvocations();
            ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();
            JSONObject body=new JSONObject().put("parent","posts").put("limit",20);
            Task<String> old=store.readListenerQueryResponse("actor",body,queue);
            body.put("mutated",true);
            JSONObject original=new JSONObject().put("parent","posts").put("limit",20);
            assertNotSame(old,store.readListenerQueryResponse("actor",new JSONObject(original.toString()).put("knownRevisions",new JSONObject().put("p","v1")),queue));
            Task<String> other=store.readListenerQueryResponse("other",original,queue);assertNotSame(old,other);
            store.invalidateDoc("posts/p");
            assertNotSame(old,store.readListenerQueryResponse("actor",original,queue));
            queue.drain();assertTrue(old.isSuccessful());assertFalse(other.isSuccessful());
            transport.verify(()->D1DocumentTransport.listenerQuery(any(JSONObject.class)),times(3));
        }
    }
}
