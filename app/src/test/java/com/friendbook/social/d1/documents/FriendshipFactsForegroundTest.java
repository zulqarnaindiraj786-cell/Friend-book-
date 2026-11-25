package com.friendbook.social.d1.documents;

import com.friendbook.social.App;
import com.google.android.gms.tasks.*;
import java.util.*;
import java.util.concurrent.Callable;
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
public class FriendshipFactsForegroundTest {
    private D1DocumentStore queued(ListenerDocumentFlightsTest.Queue queue) {
        D1DocumentStore store=spy(D1DocumentStore.getInstance());store.friendshipFactsFlights.invalidateAll();
        doAnswer(call->Tasks.call(queue,(Callable<?>)call.getArgument(0)))
                .when(store).runAsync(org.mockito.ArgumentMatchers.<Callable<Object>>any());
        return store;
    }
    @Test public void identicalAutomaticFactsDeferAndShareTransportOnResume() throws Exception {
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");foreground.when(App::isForeground).thenReturn(true);
            transport.when(()->D1DocumentTransport.friendshipFacts(anyList())).thenReturn(new JSONObject("{\"facts\":[{\"uid\":\"bob\",\"friend\":true}]}"));
            ForegroundReadScope scope=new ForegroundReadScope();List<String> ids=Collections.singletonList("bob");List<Task<Map<String,Boolean>>> tasks=new ArrayList<>();
            try {
                for(int i=0;i<100;i++)tasks.add(store.friendshipFactsAutomatic(ids,scope));assertEquals(1,queue.tasks.size());
                foreground.when(App::isForeground).thenReturn(false);queue.drain();
                transport.verify(()->D1DocumentTransport.friendshipFacts(anyList()),never());
                foreground.when(App::isForeground).thenReturn(true);ForegroundReadScope.resume();assertEquals(1,queue.tasks.size());queue.drain();
                for(Task<Map<String,Boolean>> task:tasks)assertEquals(Boolean.TRUE,task.getResult().get("bob"));
                tasks.get(0).getResult().clear();assertEquals(Boolean.TRUE,tasks.get(1).getResult().get("bob"));
                transport.verify(()->D1DocumentTransport.friendshipFacts(anyList()),times(1));
            } finally {scope.close();store.friendshipFactsFlights.invalidateAll();}
        }
    }
    @Test public void closedScreenOrChangedAccountCannotStartDeferredFacts() throws Exception {
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");foreground.when(App::isForeground).thenReturn(false);
            ForegroundReadScope closed=new ForegroundReadScope(),switched=new ForegroundReadScope();
            Task<Map<String,Boolean>> cancelled=store.friendshipFactsAutomatic(Collections.singletonList("bob"),closed);
            Task<Map<String,Boolean>> oldActor=store.friendshipFactsAutomatic(Collections.singletonList("bob"),switched);
            closed.close();identity.when(D1DocumentStore::authenticatedUid).thenReturn("other");
            foreground.when(App::isForeground).thenReturn(true);ForegroundReadScope.resume();queue.drain();
            assertTrue(cancelled.isComplete());assertFalse(cancelled.isSuccessful());
            assertTrue(oldActor.isComplete());assertFalse(oldActor.isSuccessful());
            transport.verify(()->D1DocumentTransport.friendshipFacts(anyList()),never());
            switched.close();store.friendshipFactsFlights.invalidateAll();
        }
    }
    @Test public void explicitFactReadPromotesAutomaticFlightAndMutationDetachesOldWork() throws Exception {
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");foreground.when(App::isForeground).thenReturn(true);
            transport.when(()->D1DocumentTransport.friendshipFacts(anyList())).thenReturn(new JSONObject("{\"facts\":[{\"uid\":\"bob\",\"friend\":true}]}"));
            ForegroundReadScope scope=new ForegroundReadScope();List<String> ids=Collections.singletonList("bob");
            try {
                Task<Map<String,Boolean>> automatic=store.friendshipFactsAutomatic(ids,scope);
                foreground.when(App::isForeground).thenReturn(false);
                Task<Map<String,Boolean>> explicit=store.friendshipFacts(ids);assertEquals(1,queue.tasks.size());queue.drain();
                assertTrue(automatic.isSuccessful());assertTrue(explicit.isSuccessful());
                transport.verify(()->D1DocumentTransport.friendshipFacts(anyList()),times(1));
                foreground.when(App::isForeground).thenReturn(true);
                Task<Map<String,Boolean>> old=store.friendshipFactsAutomatic(ids,scope);
                store.invalidateDoc("friends/a");Task<Map<String,Boolean>> fresh=store.friendshipFactsAutomatic(ids,scope);
                assertNotSame(old,fresh);assertEquals(2,queue.tasks.size());queue.drain();
                assertTrue(old.isSuccessful());assertTrue(fresh.isSuccessful());
            } finally {scope.close();store.friendshipFactsFlights.invalidateAll();}
        }
    }
}
