package com.friendbook.social.d1.documents;

import com.friendbook.social.App;
import com.google.android.gms.tasks.*;
import java.util.*;
import java.util.concurrent.Callable;
import org.json.JSONArray;
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
public class AutomaticQueryForegroundTest {
    private D1DocumentStore queued(ListenerDocumentFlightsTest.Queue queue) {
        D1DocumentStore store=spy(D1DocumentStore.getInstance());
        store.queryReadFlights.invalidateAll();store.aggregateReadFlights.invalidateAll();
        doAnswer(call->Tasks.call(queue,(Callable<?>)call.getArgument(0)))
                .when(store).runAsync(org.mockito.ArgumentMatchers.<Callable<Object>>any());
        return store;
    }
    @Test public void queuedAutomaticQueriesDeferAndOneHundredIdenticalCallsShareOnResume() throws Exception {
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");foreground.when(App::isForeground).thenReturn(true);
            transport.when(()->D1DocumentTransport.query(any(JSONObject.class))).thenReturn(new JSONArray());
            Query query=new Query(store,"friends").limit(20);
            ForegroundReadScope scope=new ForegroundReadScope();List<Task<QuerySnapshot>> tasks=new ArrayList<>();
            try {
                for(int i=0;i<100;i++)tasks.add(query.getAutomatic(scope));
                assertEquals(1,queue.tasks.size());
                foreground.when(App::isForeground).thenReturn(false);queue.drain();
                transport.verify(()->D1DocumentTransport.query(any(JSONObject.class)),never());
                for(Task<QuerySnapshot> task:tasks)assertFalse(task.isComplete());
                foreground.when(App::isForeground).thenReturn(true);ForegroundReadScope.resume();
                assertEquals(1,queue.tasks.size());queue.drain();
                for(Task<QuerySnapshot> task:tasks)assertTrue(task.isSuccessful());
                transport.verify(()->D1DocumentTransport.query(any(JSONObject.class)),times(1));
            } finally {scope.close();store.queryReadFlights.invalidateAll();}
        }
    }
    @Test public void explicitQueryPromotesAutomaticFlightAndStillRunsInBackground() throws Exception {
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");foreground.when(App::isForeground).thenReturn(true);
            transport.when(()->D1DocumentTransport.query(any(JSONObject.class))).thenReturn(new JSONArray());
            Query query=new Query(store,"friends").limit(20);ForegroundReadScope scope=new ForegroundReadScope();
            try {
                Task<QuerySnapshot> automatic=query.getAutomatic(scope);foreground.when(App::isForeground).thenReturn(false);
                Task<QuerySnapshot> explicit=query.get();assertEquals(1,queue.tasks.size());queue.drain();
                assertTrue(automatic.isSuccessful());assertTrue(explicit.isSuccessful());
                transport.verify(()->D1DocumentTransport.query(any(JSONObject.class)),times(1));
            } finally {scope.close();store.queryReadFlights.invalidateAll();}
        }
    }
    @Test public void explicitQueryRetriesWhenPromotionRacesDeferral() throws Exception {
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");foreground.when(App::isForeground).thenReturn(true);
            transport.when(()->D1DocumentTransport.query(any(JSONObject.class))).thenReturn(new JSONArray());
            Query query=new Query(store,"friends").limit(20);ForegroundReadScope scope=new ForegroundReadScope();
            try {
                Task<QuerySnapshot> automatic=query.getAutomatic(scope);
                java.util.concurrent.atomic.AtomicReference<Task<QuerySnapshot>> explicit=new java.util.concurrent.atomic.AtomicReference<>();
                foreground.when(App::isForeground).thenAnswer(call->{explicit.set(query.get());return false;});
                queue.drain();assertNotNull(explicit.get());assertTrue(explicit.get().isSuccessful());
                assertFalse(automatic.isComplete());
                transport.verify(()->D1DocumentTransport.query(any(JSONObject.class)),times(1));
            } finally {scope.close();store.queryReadFlights.invalidateAll();}
        }
    }
    @Test public void stoppedScreenAndChangedAccountNeverStartWaitingTransport() throws Exception {
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");foreground.when(App::isForeground).thenReturn(false);
            Query query=new Query(store,"friends").limit(20);ForegroundReadScope closed=new ForegroundReadScope(),switched=new ForegroundReadScope();
            Task<QuerySnapshot> stopped=query.getAutomatic(closed),wrongAccount=query.getAutomatic(switched);
            closed.close();identity.when(D1DocumentStore::authenticatedUid).thenReturn("other");
            foreground.when(App::isForeground).thenReturn(true);ForegroundReadScope.resume();queue.drain();
            assertTrue(stopped.isComplete());assertFalse(stopped.isSuccessful());
            assertTrue(wrongAccount.isComplete());assertFalse(wrongAccount.isSuccessful());
            transport.verify(()->D1DocumentTransport.query(any(JSONObject.class)),never());
            switched.close();store.queryReadFlights.invalidateAll();
        }
    }
    @Test public void automaticCountDefersButExplicitCountPromotesSameFlight() throws Exception {
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");foreground.when(App::isForeground).thenReturn(true);
            transport.when(()->D1DocumentTransport.count(eq("posts"),any(JSONArray.class))).thenReturn(42);
            AggregateQuery count=new Query(store,"posts").whereEqualTo("authorId","u").count();ForegroundReadScope scope=new ForegroundReadScope();
            try {
                Task<AggregateQuerySnapshot> automatic=count.getAutomatic(scope);
                foreground.when(App::isForeground).thenReturn(false);queue.drain();
                assertFalse(automatic.isComplete());transport.verify(()->D1DocumentTransport.count(eq("posts"),any(JSONArray.class)),never());
                foreground.when(App::isForeground).thenReturn(true);ForegroundReadScope.resume();
                foreground.when(App::isForeground).thenReturn(false);
                Task<AggregateQuerySnapshot> explicit=count.get(AggregateSource.SERVER);
                assertEquals(1,queue.tasks.size());queue.drain();
                assertEquals(42,automatic.getResult().getCount());assertEquals(42,explicit.getResult().getCount());
                transport.verify(()->D1DocumentTransport.count(eq("posts"),any(JSONArray.class)),times(1));
            } finally {scope.close();store.aggregateReadFlights.invalidateAll();}
        }
    }
}
