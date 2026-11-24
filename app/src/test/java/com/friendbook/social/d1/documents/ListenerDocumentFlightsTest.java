package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;
import java.util.*;
import java.util.concurrent.*;
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
public class ListenerDocumentFlightsTest {
    static final class Queue extends AbstractExecutorService {
        final List<Runnable> tasks=new ArrayList<>();
        public void execute(Runnable task){tasks.add(task);}
        void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}
        public void shutdown(){} public List<Runnable> shutdownNow(){return Collections.emptyList();}
        public boolean isShutdown(){return false;} public boolean isTerminated(){return false;}
        public boolean awaitTermination(long n,TimeUnit unit){return false;}
    }
    @Test public void actualStoreSharesAcrossListenerAndGetLanesBeforeAnyNetworkWork() throws Exception {
        D1DocumentStore store=D1DocumentStore.getInstance();store.documentReadFlights.invalidateAll();
        try(MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class,CALLS_REAL_METHODS)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
            transport.when(()->D1DocumentTransport.getDocument("users/u",false)).thenReturn(new JSONObject("{\"data\":{\"displayName\":\"User\"}}"));
            Queue listener=new Queue(),ordinary=new Queue();
            Task<String> first=store.readDocumentResponse("actor","users/u",false,true,listener);
            for(int i=0;i<100;i++)assertSame(first,store.readDocumentResponse("actor","users/u",false,true,ordinary));
            assertEquals(1,listener.tasks.size());assertEquals(0,ordinary.tasks.size());
            listener.drain();assertTrue(first.isSuccessful());
            transport.verify(()->D1DocumentTransport.getDocument("users/u",false),times(1));
        }
    }
    @Test public void requestOptionsForcedReadsAndMutationDoNotReuseOldFlight() {
        D1DocumentStore store=D1DocumentStore.getInstance();store.documentReadFlights.invalidateAll();
        try(MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");Queue queue=new Queue();
            Task<String> normal=store.readDocumentResponse("actor","users/u",false,true,queue);
            assertNotSame(normal,store.readDocumentResponse("actor","users/u",true,true,queue));
            assertNotSame(normal,store.readDocumentResponse("actor","users/u",false,false,queue));
            assertEquals(3,queue.tasks.size());
            store.invalidateDoc("users/u");
            assertNotSame(normal,store.readDocumentResponse("actor","users/u",false,true,queue));
            assertEquals(4,queue.tasks.size());
        }
    }
    @Test public void accountSwitchBeforeQueuedExecutionNeverCallsTransport() {
        D1DocumentStore store=D1DocumentStore.getInstance();store.documentReadFlights.invalidateAll();
        try(MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
            MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class,CALLS_REAL_METHODS)) {
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");Queue queue=new Queue();
            Task<String> pending=store.readDocumentResponse("actor","users/u",false,true,queue);
            identity.when(D1DocumentStore::authenticatedUid).thenReturn("other");queue.drain();
            assertFalse(pending.isSuccessful());
            transport.verify(()->D1DocumentTransport.getDocument("users/u",false),never());
        }
    }
}
