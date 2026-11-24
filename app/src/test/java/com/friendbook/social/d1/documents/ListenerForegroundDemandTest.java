package com.friendbook.social.d1.documents;

import com.friendbook.social.App;
import java.lang.reflect.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=android.app.Application.class)
public class ListenerForegroundDemandTest {
    private static Field field(Class<?> type, String name) throws Exception {
        Field field=type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @Test public void initialAndQueuedAutomaticReadsWaitForResumeAndRetainInvalidations() throws Exception {
        D1DocumentStore store=D1DocumentStore.getInstance();
        Class<?> type=Class.forName(D1DocumentStore.class.getName()+"$QueryListenerEntry");
        Constructor<?> constructor=type.getDeclaredConstructor(Query.class,EventListener.class);constructor.setAccessible(true);
        Method schedule=D1DocumentStore.class.getDeclaredMethod("scheduleQueryRead",type);schedule.setAccessible(true);
        Field executor=field(D1DocumentStore.class,"listenerExecutor");
        ExecutorService original=(ExecutorService)executor.get(store);
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();
        @SuppressWarnings("unchecked") CopyOnWriteArrayList<Object> listeners=(CopyOnWriteArrayList<Object>)field(D1DocumentStore.class,"queryListeners").get(store);
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class,CALLS_REAL_METHODS)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
            foreground.when(App::isForeground).thenReturn(false);
            transport.when(()->D1DocumentTransport.listenerQuery(any(JSONObject.class)))
                    .thenReturn(new JSONObject("{\"documents\":[],\"revisions\":{}}"));
            transport.clearInvocations();
            executor.set(store,queue);
            EventListener<QuerySnapshot> callback=(snapshot,error)->{};
            Object entry=constructor.newInstance(new Query(store,"posts").limit(20),callback);
            field(type,"cacheReady").setBoolean(entry,true);listeners.add(entry);
            try {
                schedule.invoke(store,entry);
                store.notifyCollectionListeners("posts");
                assertEquals(0,queue.tasks.size());
                foreground.when(App::isForeground).thenReturn(true);
                store.resumeDeferredListeners();
                assertEquals(1,queue.tasks.size());
                // A queued read must recheck lifecycle immediately before HTTP.
                foreground.when(App::isForeground).thenReturn(false);
                queue.drain();
                transport.verify(()->D1DocumentTransport.listenerQuery(any(JSONObject.class)),never());
                assertEquals(0,queue.tasks.size());
                foreground.when(App::isForeground).thenReturn(true);
                store.resumeDeferredListeners();
                assertEquals(1,queue.tasks.size());
                // An invalidation while this read is in flight schedules a new read,
                // rather than being lost or joining a pre-invalidation flight.
                store.notifyCollectionListeners("posts");
                queue.drain();
                transport.verify(()->D1DocumentTransport.listenerQuery(any(JSONObject.class)),times(2));
                assertEquals(0,queue.tasks.size());
            } finally { field(type,"active").setBoolean(entry,false); listeners.remove(entry); }
        } finally { executor.set(store,original); store.listenerQueryReadFlights.invalidateAll(); }
    }
    @Test public void explicitChatRetryCanStartWhileBackgrounded() throws Exception {
        D1DocumentStore store=D1DocumentStore.getInstance();
        Class<?> type=Class.forName(D1DocumentStore.class.getName()+"$QueryListenerEntry");
        Constructor<?> constructor=type.getDeclaredConstructor(Query.class,EventListener.class);constructor.setAccessible(true);
        Field executor=field(D1DocumentStore.class,"chatReadExecutor");
        ExecutorService original=(ExecutorService)executor.get(store);
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();
        @SuppressWarnings("unchecked") CopyOnWriteArrayList<Object> listeners=(CopyOnWriteArrayList<Object>)field(D1DocumentStore.class,"queryListeners").get(store);
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class,CALLS_REAL_METHODS)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
            foreground.when(App::isForeground).thenReturn(false);
            transport.when(()->D1DocumentTransport.listenerQuery(any(JSONObject.class)))
                    .thenReturn(new JSONObject("{\"documents\":[],\"revisions\":{}}"));
            transport.clearInvocations();executor.set(store,queue);
            EventListener<QuerySnapshot> callback=(snapshot,error)->{};
            Object entry=constructor.newInstance(new Query(store,"chats/c/messages").limit(20),callback);
            field(type,"cacheReady").setBoolean(entry,true);listeners.add(entry);
            try {
                store.refreshChatMessages("c");
                assertEquals(1,queue.tasks.size());queue.drain();
                transport.verify(()->D1DocumentTransport.listenerQuery(any(JSONObject.class)),times(1));
            } finally { field(type,"active").setBoolean(entry,false);listeners.remove(entry); }
        } finally { executor.set(store,original);store.listenerQueryReadFlights.invalidateAll(); }
    }

}
