package com.friendbook.social.d1.documents;

import com.friendbook.social.App;
import com.google.android.gms.tasks.Task;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
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
public class DocumentForegroundDemandTest {
    private static Field field(String name) throws Exception {
        Field f=D1DocumentStore.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private Task<String> automatic(D1DocumentStore store, String path, ExecutorService lane) throws Exception {
        Method m=D1DocumentStore.class.getDeclaredMethod("readDocumentResponse",String.class,String.class,
                boolean.class,boolean.class,ExecutorService.class,boolean.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked") Task<String> result=(Task<String>)m.invoke(store,"actor",path,false,true,lane,true);
        return result;
    }
    @Test public void listenerDefersBackgroundAndQueuedWorkThenReplaysInvalidation() throws Exception {
        D1DocumentStore store=D1DocumentStore.getInstance();
        Field executor=field("listenerExecutor");ExecutorService original=(ExecutorService)executor.get(store);
        ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();
        String path="users/foreground-listener-test";
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class,CALLS_REAL_METHODS)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
            foreground.when(App::isForeground).thenReturn(false);
            transport.when(()->D1DocumentTransport.getDocument(path,false))
                    .thenReturn(new JSONObject("{\"data\":{\"name\":\"A\"}}"));
            transport.clearInvocations();executor.set(store,queue);store.invalidateDoc(path);
            ListenerRegistration reg=store.registerDocListener(new DocumentReference(store,path),(value,error)->{});
            try {
                store.notifyDocListeners(path);assertEquals(0,queue.tasks.size());
                foreground.when(App::isForeground).thenReturn(true);store.resumeDeferredListeners();
                assertEquals(1,queue.tasks.size());
                foreground.when(App::isForeground).thenReturn(false);queue.drain();
                transport.verify(()->D1DocumentTransport.getDocument(path,false),never());
                foreground.when(App::isForeground).thenReturn(true);store.resumeDeferredListeners();
                assertEquals(1,queue.tasks.size());
                store.invalidateDoc(path);store.notifyDocListeners(path);
                queue.drain();
                transport.verify(()->D1DocumentTransport.getDocument(path,false),times(2));
            } finally { reg.remove(); }
        } finally { executor.set(store,original);store.documentReadFlights.invalidateAll(); }
    }
    @Test public void explicitReadPromotesQueuedListenerAndSharesOneTransport() throws Exception {
        D1DocumentStore store=D1DocumentStore.getInstance();store.documentReadFlights.invalidateAll();
        String path="users/promoted-test";
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class,CALLS_REAL_METHODS)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");foreground.when(App::isForeground).thenReturn(false);
            transport.when(()->D1DocumentTransport.getDocument(path,false)).thenReturn(new JSONObject("{\"data\":{}}"));
            transport.clearInvocations();
            ListenerDocumentFlightsTest.Queue listener=new ListenerDocumentFlightsTest.Queue(),user=new ListenerDocumentFlightsTest.Queue();
            Task<String> automatic=automatic(store,path,listener);
            Task<String> explicit=store.readDocumentResponse("actor",path,false,true,user);
            assertEquals(1,listener.tasks.size());assertEquals(0,user.tasks.size());
            listener.drain();assertTrue(automatic.isSuccessful());assertTrue(explicit.isSuccessful());
            transport.verify(()->D1DocumentTransport.getDocument(path,false),times(1));
        } finally { store.documentReadFlights.invalidateAll(); }
    }
    @Test public void explicitReadRetriesIfItJoinsDuringDeferralDecision() throws Exception {
        D1DocumentStore store=D1DocumentStore.getInstance();store.documentReadFlights.invalidateAll();
        String path="users/promotion-race-test";
        try(MockedStatic<App> foreground=mockStatic(App.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class,CALLS_REAL_METHODS)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
            transport.when(()->D1DocumentTransport.getDocument(path,false)).thenReturn(new JSONObject("{\"data\":{}}"));
            transport.clearInvocations();
            ListenerDocumentFlightsTest.Queue listener=new ListenerDocumentFlightsTest.Queue(),user=new ListenerDocumentFlightsTest.Queue();
            Task<String> automatic=automatic(store,path,listener);
            AtomicReference<Task<String>> explicit=new AtomicReference<>();
            foreground.when(App::isForeground).thenAnswer(call->{
                explicit.set(store.readDocumentResponse("actor",path,false,true,user));return false;
            });
            listener.drain();assertFalse(automatic.isSuccessful());assertNotNull(explicit.get());
            assertEquals(1,user.tasks.size());user.drain();assertTrue(explicit.get().isSuccessful());
            transport.verify(()->D1DocumentTransport.getDocument(path,false),times(1));
        } finally { store.documentReadFlights.invalidateAll(); }
    }
}
