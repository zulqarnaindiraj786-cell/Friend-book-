package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.*;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class QueuedQueryAccountTest {
    @Test public void queuedListAndDeltaNeverUseNewAccountsTransport() throws Exception {
        for(String parent:new String[]{"posts","users"}) {
            for(boolean delta:new boolean[]{false,true}) {
                D1DocumentStore store=spy(D1DocumentStore.getInstance());
                ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();
                doAnswer(call->Tasks.call(queue,(Callable<QuerySnapshot>)call.getArgument(0)))
                    .when(store).runAsync(org.mockito.ArgumentMatchers.<Callable<QuerySnapshot>>any());
                try(MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
                    MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)) {
                    identity.when(D1DocumentStore::authenticatedUid).thenReturn("first");
                    Query query=new Query(store,parent).orderBy("createdAt",Query.Direction.DESCENDING).limit(20);
                    Task<QuerySnapshot> pending;
                    pending=delta?query.refreshCachedHead(query.parseQuerySnapshot(new JSONArray(
                        "[{\"id\":\"p\",\"path\":\""+parent+"/p\",\"data\":{\"createdAt\":10}}]"))):query.get(Source.DEFAULT);
                    identity.when(D1DocumentStore::authenticatedUid).thenReturn("second");
                    queue.drain();
                    assertTrue(pending.isComplete());assertFalse(pending.isSuccessful());
                    assertEquals(D1DocumentStoreException.Code.CANCELLED,((D1DocumentStoreException)pending.getException()).getCode());
                    transport.verifyNoInteractions();
                }
            }
        }
    }
}
