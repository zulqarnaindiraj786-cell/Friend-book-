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
public class AggregateDemandTest {
 private D1DocumentStore queued(ListenerDocumentFlightsTest.Queue queue) {
  D1DocumentStore store=spy(D1DocumentStore.getInstance());store.aggregateReadFlights.invalidateAll();
  doAnswer(call->Tasks.call(queue,(Callable<AggregateQuerySnapshot>)call.getArgument(0)))
   .when(store).runAsync(org.mockito.ArgumentMatchers.<Callable<AggregateQuerySnapshot>>any());
  return store;
 }
 @Test public void hundredOverlappingCountConsumersQueueOneFreshServerOperation() throws Exception {
  ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
  try(MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
      MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)){
   identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
   transport.when(()->D1DocumentTransport.count(eq("posts"),any(JSONArray.class))).thenReturn(42);
   Query query=new Query(store,"posts").whereEqualTo("authorId","author");
   Task<AggregateQuerySnapshot> first=query.count().get(AggregateSource.SERVER);
   for(int i=0;i<100;i++)assertSame(first,query.count().get(AggregateSource.SERVER));
   assertEquals(1,queue.tasks.size());queue.drain();assertEquals(42,first.getResult().getCount());
   transport.verify(()->D1DocumentTransport.count(eq("posts"),any(JSONArray.class)),times(1));
   assertNotSame(first,query.count().get(AggregateSource.SERVER));assertEquals(1,queue.tasks.size());queue.drain();
   transport.verify(()->D1DocumentTransport.count(eq("posts"),any(JSONArray.class)),times(2));
  }
 }
 @Test public void filtersParentsActorsAndPostMutationReadsDoNotJoinOldCounts() {
  ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
  try(MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS)){
   identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
   Query query=new Query(store,"posts").whereEqualTo("authorId","author");
   Task<AggregateQuerySnapshot> first=query.count().get(AggregateSource.SERVER);
   assertNotSame(first,new Query(store,"posts").whereEqualTo("authorId","other").count().get(AggregateSource.SERVER));
   assertNotSame(first,new Query(store,"referrals").whereEqualTo("authorId","author").count().get(AggregateSource.SERVER));
   identity.when(D1DocumentStore::authenticatedUid).thenReturn("other-account");
   assertNotSame(first,query.count().get(AggregateSource.SERVER));
   identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
   store.invalidateDoc("posts/new");assertNotSame(first,query.count().get(AggregateSource.SERVER));
   store.notifyCollectionListeners("posts");assertNotSame(first,query.count().get(AggregateSource.SERVER));
   assertEquals(6,queue.tasks.size());
  }
 }
 @Test public void queuedAccountSwitchAndTransportAccountSwitchCannotDeliverCounts() throws Exception {
  for(boolean before:new boolean[]{true,false}){
   ListenerDocumentFlightsTest.Queue queue=new ListenerDocumentFlightsTest.Queue();D1DocumentStore store=queued(queue);
   try(MockedStatic<D1DocumentStore> identity=mockStatic(D1DocumentStore.class,CALLS_REAL_METHODS);
       MockedStatic<D1DocumentTransport> transport=mockStatic(D1DocumentTransport.class)){
    identity.when(D1DocumentStore::authenticatedUid).thenReturn("actor");
    transport.when(()->D1DocumentTransport.count(eq("posts"),any(JSONArray.class))).thenAnswer(call->{
     identity.when(D1DocumentStore::authenticatedUid).thenReturn("other");return 42;
    });
    Task<AggregateQuerySnapshot> count=new Query(store,"posts").count().get(AggregateSource.SERVER);
    if(before)identity.when(D1DocumentStore::authenticatedUid).thenReturn("other");
    queue.drain();assertTrue(count.isComplete());assertFalse(count.isSuccessful());
    assertEquals(D1DocumentStoreException.Code.CANCELLED,((D1DocumentStoreException)count.getException()).getCode());
    transport.verify(()->D1DocumentTransport.count(eq("posts"),any(JSONArray.class)),times(before?0:1));
   }
  }
 }
}
