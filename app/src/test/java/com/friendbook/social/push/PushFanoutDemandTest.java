package com.friendbook.social.push;

import androidx.work.*;
import com.friendbook.social.core.Fb;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class PushFanoutDemandTest {
 @Test public void actualWorkerStopsDeniedLookupsButRetriesOutages() throws Exception {
  for(D1DocumentStoreException.Code code:new D1DocumentStoreException.Code[]{
    D1DocumentStoreException.Code.PERMISSION_DENIED,D1DocumentStoreException.Code.NOT_FOUND,
    D1DocumentStoreException.Code.UNAVAILABLE}){
   java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();
   try {
    ListenableWorker.Result result=executor.submit(()->{
     try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
      fb.when(Fb::uid).thenReturn("actor");
      D1DocumentStore store=mock(D1DocumentStore.class);CollectionReference chats=mock(CollectionReference.class);
      DocumentReference chat=mock(DocumentReference.class);fb.when(Fb::db).thenReturn(store);
      when(store.collection("chats")).thenReturn(chats);when(chats.document("room")).thenReturn(chat);
      when(chat.get(Source.SERVER)).thenReturn(Tasks.forException(new D1DocumentStoreException(code,"lookup failed")));
      WorkerParameters parameters=mock(WorkerParameters.class);
      when(parameters.getInputData()).thenReturn(new Data.Builder().putString("uid","actor").putString("kind","chat").putString("document","room").build());
      when(parameters.getRunAttemptCount()).thenReturn(0);
      PushFanoutWorker worker=new PushFanoutWorker(RuntimeEnvironment.getApplication(),parameters);
      ListenableWorker.Result outcome=worker.doWork();verify(chat,times(1)).get(Source.SERVER);return outcome;
     }
    }).get(10,TimeUnit.SECONDS);
    assertEquals(code.toString(),code==D1DocumentStoreException.Code.UNAVAILABLE?ListenableWorker.Result.retry():ListenableWorker.Result.failure(),result);
   } finally {executor.shutdownNow();}
  }
 }

 @Test public void partialFriendPostRetrySkipsDurablyQueuedRecipients()throws Exception{
  java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();
  try{
   executor.submit(()->{
    try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FCMPush> push=mockStatic(FCMPush.class)){
     RuntimeEnvironment.getApplication().getSharedPreferences("friend_post_fanout_progress",0).edit().clear().commit();
     fb.when(Fb::uid).thenReturn("actor");fb.when(Fb::myName).thenReturn("Actor");fb.when(Fb::myPhoto).thenReturn("");
     D1DocumentStore store=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(store);
     CollectionReference posts=mock(CollectionReference.class),friends=mock(CollectionReference.class),notifications=mock(CollectionReference.class);
     when(store.collection("posts")).thenReturn(posts);when(store.collection("friends")).thenReturn(friends);when(store.collection("notifications")).thenReturn(notifications);
     DocumentReference postRef=mock(DocumentReference.class),alice=mock(DocumentReference.class),bob=mock(DocumentReference.class);
     when(posts.document("post")).thenReturn(postRef);when(notifications.document("friend_post_post_alice")).thenReturn(alice);when(notifications.document("friend_post_post_bob")).thenReturn(bob);
     when(alice.getId()).thenReturn("friend_post_post_alice");when(bob.getId()).thenReturn("friend_post_post_bob");
     DocumentSnapshot post=mock(DocumentSnapshot.class);when(post.exists()).thenReturn(true);when(post.getString("authorId")).thenReturn("actor");when(postRef.get(Source.SERVER)).thenReturn(Tasks.forResult(post));
     Query query=mock(Query.class);when(friends.whereArrayContains("participants","actor")).thenReturn(query);
     QueryDocumentSnapshot first=mock(QueryDocumentSnapshot.class),second=mock(QueryDocumentSnapshot.class);
     when(first.get("participants")).thenReturn(java.util.Arrays.asList("actor","alice"));when(second.get("participants")).thenReturn(java.util.Arrays.asList("actor","bob"));
     QuerySnapshot snap=new QuerySnapshot(java.util.Arrays.asList(first,second),null,new SnapshotMetadata(false,false));when(query.get(Source.SERVER)).thenReturn(Tasks.forResult(snap));
     when(alice.create(anyMap())).thenReturn(Tasks.forResult(null));
     when(bob.create(anyMap())).thenReturn(Tasks.forException(new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE,"offline")),Tasks.forResult(null));
     Operation operation=mock(Operation.class);when(operation.getResult()).thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(null));
     push.when(()->FCMPush.enqueueNotificationDurable(anyString(),anyMap())).thenReturn(operation);
     WorkerParameters params=mock(WorkerParameters.class);
     when(params.getInputData()).thenReturn(new Data.Builder().putString("uid","actor").putString("kind","friend_post").putString("document","post").build());
     when(params.getRunAttemptCount()).thenReturn(0,1);
     assertEquals(ListenableWorker.Result.retry(),new PushFanoutWorker(RuntimeEnvironment.getApplication(),params).doWork());
     assertEquals(ListenableWorker.Result.success(),new PushFanoutWorker(RuntimeEnvironment.getApplication(),params).doWork());
     verify(alice,times(1)).create(anyMap());verify(bob,times(2)).create(anyMap());verify(query,times(2)).get(Source.SERVER);
     push.verify(()->FCMPush.enqueueNotificationDurable(eq("friend_post_post_alice"),anyMap()),times(1));
     push.verify(()->FCMPush.enqueueNotificationDurable(eq("friend_post_post_bob"),anyMap()),times(1));
    }return null;
   }).get(15,TimeUnit.SECONDS);
  }finally{executor.shutdownNow();}
 }

 @Test public void failedEnqueueIsNotCheckpointedAndExistingNotificationCanRetry()throws Exception{
  java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();
  try{executor.submit(()->{
   try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FCMPush> push=mockStatic(FCMPush.class)){
    RuntimeEnvironment.getApplication().getSharedPreferences("friend_post_fanout_progress",0).edit().clear().commit();
    fb.when(Fb::uid).thenReturn("actor");fb.when(Fb::myName).thenReturn("Actor");fb.when(Fb::myPhoto).thenReturn("");
    D1DocumentStore store=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(store);
    CollectionReference posts=mock(CollectionReference.class),friends=mock(CollectionReference.class),notifications=mock(CollectionReference.class);
    when(store.collection("posts")).thenReturn(posts);when(store.collection("friends")).thenReturn(friends);when(store.collection("notifications")).thenReturn(notifications);
    DocumentReference postRef=mock(DocumentReference.class),target=mock(DocumentReference.class);when(posts.document("post")).thenReturn(postRef);
    when(notifications.document("friend_post_post_alice")).thenReturn(target);when(target.getId()).thenReturn("friend_post_post_alice");
    DocumentSnapshot post=mock(DocumentSnapshot.class);when(post.exists()).thenReturn(true);when(post.getString("authorId")).thenReturn("actor");when(postRef.get(Source.SERVER)).thenReturn(Tasks.forResult(post));
    Query query=mock(Query.class);when(friends.whereArrayContains("participants","actor")).thenReturn(query);
    QueryDocumentSnapshot friend=mock(QueryDocumentSnapshot.class);when(friend.get("participants")).thenReturn(java.util.Arrays.asList("actor","alice"));
    when(query.get(Source.SERVER)).thenReturn(Tasks.forResult(new QuerySnapshot(java.util.Collections.singletonList(friend),null,new SnapshotMetadata(false,false))));
    when(target.create(anyMap())).thenReturn(Tasks.forResult(null),Tasks.forException(new D1DocumentStoreException(D1DocumentStoreException.Code.ALREADY_EXISTS,"exists")));
    Operation failed=mock(Operation.class),success=mock(Operation.class);
    when(failed.getResult()).thenReturn(com.google.common.util.concurrent.Futures.immediateFailedFuture(new java.io.IOException("queue unavailable")));
    when(success.getResult()).thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(null));
    push.when(()->FCMPush.enqueueNotificationDurable(anyString(),anyMap())).thenReturn(failed,success);
    WorkerParameters params=mock(WorkerParameters.class);when(params.getInputData()).thenReturn(new Data.Builder().putString("uid","actor").putString("kind","friend_post").putString("document","post").build());
    when(params.getRunAttemptCount()).thenReturn(0);
    assertEquals(ListenableWorker.Result.retry(),new PushFanoutWorker(RuntimeEnvironment.getApplication(),params).doWork());
    assertEquals(ListenableWorker.Result.success(),new PushFanoutWorker(RuntimeEnvironment.getApplication(),params).doWork());
    verify(target,times(2)).create(anyMap());push.verify(()->FCMPush.enqueueNotificationDurable(eq("friend_post_post_alice"),anyMap()),times(2));
   }return null;
  }).get(15,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
 }
}
