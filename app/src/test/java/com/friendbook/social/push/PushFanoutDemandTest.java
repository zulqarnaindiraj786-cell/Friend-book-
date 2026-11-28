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
 @Test public void friendPostIdsShareOnlyTheirAuthorRecipientTwelveHourWindow() {
  long bucket=FriendPostNotificationId.windowBucket(1_800_000_000_000L);
  String event=FriendPostNotificationId.of("actor","alice",bucket);
  assertEquals(event,FriendPostNotificationId.of("actor","alice",bucket)); // second post in same window
  assertNotEquals(event,FriendPostNotificationId.of("actor","alice",bucket+1));
  assertNotEquals(event,FriendPostNotificationId.of("other","alice",bucket));
  assertNotEquals(event,FriendPostNotificationId.of("actor","bob",bucket));
  assertEquals(bucket+1,FriendPostNotificationId.windowBucket((bucket+1)*FriendPostNotificationId.WINDOW_MS));
 }
 @Test public void durableEnqueueRejectsChangedOrSignedOutAccount() throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("other",null);
   assertNull(PushDeliveryWorker.enqueueAcknowledged(new org.json.JSONObject(),false,"actor"));
   assertNull(PushDeliveryWorker.enqueueAcknowledged(new org.json.JSONObject(),false,"actor"));
  }
 }

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
     CollectionReference posts=mock(CollectionReference.class),notifications=mock(CollectionReference.class);
     when(store.collection("posts")).thenReturn(posts);when(store.collection("notifications")).thenReturn(notifications);
     DocumentReference postRef=mock(DocumentReference.class),alice=mock(DocumentReference.class),bob=mock(DocumentReference.class);
     when(posts.document("post")).thenReturn(postRef);when(notifications.document("friend_post_actor_alice_123")).thenReturn(alice);when(notifications.document("friend_post_actor_bob_123")).thenReturn(bob);
     when(alice.getId()).thenReturn("friend_post_actor_alice_123");when(bob.getId()).thenReturn("friend_post_actor_bob_123");
     DocumentSnapshot post=mock(DocumentSnapshot.class);when(post.exists()).thenReturn(true);when(post.getString("authorId")).thenReturn("actor");when(postRef.get(Source.SERVER)).thenReturn(Tasks.forResult(post));
     when(store.friendPostRecipients()).thenReturn(Tasks.forResult(java.util.Arrays.asList("alice","bob")));
     when(alice.create(anyMap())).thenReturn(Tasks.forResult(null));
     when(bob.create(anyMap())).thenReturn(Tasks.forException(new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE,"offline")),Tasks.forResult(null));
     Operation operation=mock(Operation.class);when(operation.getResult()).thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(null));
     push.when(()->FCMPush.enqueueNotificationDurable(anyString(),anyString(),anyMap())).thenReturn(operation);
     WorkerParameters params=mock(WorkerParameters.class);
     when(params.getInputData()).thenReturn(new Data.Builder().putString("uid","actor").putString("kind","friend_post").putString("document","post").putLong("friend_post_window",123).build());
     when(params.getRunAttemptCount()).thenReturn(0,1);
     assertEquals(ListenableWorker.Result.retry(),new PushFanoutWorker(RuntimeEnvironment.getApplication(),params).doWork());
     assertEquals(ListenableWorker.Result.success(),new PushFanoutWorker(RuntimeEnvironment.getApplication(),params).doWork());
     verify(alice,times(1)).create(anyMap());verify(bob,times(2)).create(anyMap());verify(store,times(2)).friendPostRecipients();
     push.verify(()->FCMPush.enqueueNotificationDurable(eq("actor"),eq("friend_post_actor_alice_123"),anyMap()),times(1));
     push.verify(()->FCMPush.enqueueNotificationDurable(eq("actor"),eq("friend_post_actor_bob_123"),anyMap()),times(1));
    }return null;
   }).get(15,TimeUnit.SECONDS);
  }finally{executor.shutdownNow();}
 }

 @Test public void postsInSameWindowCreateOneInboxRowAndGatewayEventIdentityIsShared()throws Exception{
  java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();
  try{executor.submit(()->{
   try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FCMPush> push=mockStatic(FCMPush.class)){
    RuntimeEnvironment.getApplication().getSharedPreferences("friend_post_fanout_progress",0).edit().clear().commit();
    fb.when(Fb::uid).thenReturn("actor");fb.when(Fb::myName).thenReturn("Actor");fb.when(Fb::myPhoto).thenReturn("");
    D1DocumentStore store=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(store);
    CollectionReference posts=mock(CollectionReference.class),notifications=mock(CollectionReference.class);
    when(store.collection("posts")).thenReturn(posts);when(store.collection("notifications")).thenReturn(notifications);
    DocumentReference postA=mock(DocumentReference.class),postB=mock(DocumentReference.class),note=mock(DocumentReference.class);
    when(posts.document("postA")).thenReturn(postA);when(posts.document("postB")).thenReturn(postB);
    String event=FriendPostNotificationId.of("actor","alice",123);
    when(notifications.document(event)).thenReturn(note);when(note.getId()).thenReturn(event);
    for(DocumentReference ref:new DocumentReference[]{postA,postB}){
     DocumentSnapshot post=mock(DocumentSnapshot.class);when(post.exists()).thenReturn(true);when(post.getString("authorId")).thenReturn("actor");
     when(ref.get(Source.SERVER)).thenReturn(Tasks.forResult(post));
    }
    when(store.friendPostRecipients()).thenReturn(Tasks.forResult(java.util.Collections.singletonList("alice")));
    when(note.create(anyMap())).thenReturn(Tasks.forResult(null),Tasks.forException(new D1DocumentStoreException(D1DocumentStoreException.Code.ALREADY_EXISTS,"exists")));
    Operation operation=mock(Operation.class);when(operation.getResult()).thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(null));
    push.when(()->FCMPush.enqueueNotificationDurable(anyString(),anyString(),anyMap())).thenReturn(operation);
    WorkerParameters params=mock(WorkerParameters.class);
    when(params.getInputData()).thenReturn(
      new Data.Builder().putString("uid","actor").putString("kind","friend_post").putString("document","postA").putLong("friend_post_window",123).build(),
      new Data.Builder().putString("uid","actor").putString("kind","friend_post").putString("document","postB").putLong("friend_post_window",123).build());
    when(params.getRunAttemptCount()).thenReturn(0);
    assertEquals(ListenableWorker.Result.success(),new PushFanoutWorker(RuntimeEnvironment.getApplication(),params).doWork());
    assertEquals(ListenableWorker.Result.success(),new PushFanoutWorker(RuntimeEnvironment.getApplication(),params).doWork());
    verify(note,times(2)).create(anyMap()); // create-only: exactly one insert succeeds
    push.verify(()->FCMPush.enqueueNotificationDurable(eq("actor"),eq(event),anyMap()),times(2));
   }return null;
  }).get(15,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
 }

 @Test public void failedEnqueueIsNotCheckpointedAndExistingNotificationCanRetry()throws Exception{
  java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();
  try{executor.submit(()->{
   try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FCMPush> push=mockStatic(FCMPush.class)){
    RuntimeEnvironment.getApplication().getSharedPreferences("friend_post_fanout_progress",0).edit().clear().commit();
    fb.when(Fb::uid).thenReturn("actor");fb.when(Fb::myName).thenReturn("Actor");fb.when(Fb::myPhoto).thenReturn("");
    D1DocumentStore store=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(store);
    CollectionReference posts=mock(CollectionReference.class),notifications=mock(CollectionReference.class);
    when(store.collection("posts")).thenReturn(posts);when(store.collection("notifications")).thenReturn(notifications);
    DocumentReference postRef=mock(DocumentReference.class),target=mock(DocumentReference.class);when(posts.document("post")).thenReturn(postRef);
    when(notifications.document("friend_post_actor_alice_123")).thenReturn(target);when(target.getId()).thenReturn("friend_post_actor_alice_123");
    DocumentSnapshot post=mock(DocumentSnapshot.class);when(post.exists()).thenReturn(true);when(post.getString("authorId")).thenReturn("actor");when(postRef.get(Source.SERVER)).thenReturn(Tasks.forResult(post));
    when(store.friendPostRecipients()).thenReturn(Tasks.forResult(java.util.Collections.singletonList("alice")));
    when(target.create(anyMap())).thenReturn(Tasks.forResult(null),Tasks.forException(new D1DocumentStoreException(D1DocumentStoreException.Code.ALREADY_EXISTS,"exists")));
    Operation failed=mock(Operation.class),success=mock(Operation.class);
    when(failed.getResult()).thenReturn(com.google.common.util.concurrent.Futures.immediateFailedFuture(new java.io.IOException("queue unavailable")));
    when(success.getResult()).thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(null));
    push.when(()->FCMPush.enqueueNotificationDurable(anyString(),anyString(),anyMap())).thenReturn(failed,success);
    WorkerParameters params=mock(WorkerParameters.class);when(params.getInputData()).thenReturn(new Data.Builder().putString("uid","actor").putString("kind","friend_post").putString("document","post").putLong("friend_post_window",123).build());
    when(params.getRunAttemptCount()).thenReturn(0);
    assertEquals(ListenableWorker.Result.retry(),new PushFanoutWorker(RuntimeEnvironment.getApplication(),params).doWork());
    assertEquals(ListenableWorker.Result.success(),new PushFanoutWorker(RuntimeEnvironment.getApplication(),params).doWork());
    verify(target,times(2)).create(anyMap());push.verify(()->FCMPush.enqueueNotificationDurable(eq("actor"),eq("friend_post_actor_alice_123"),anyMap()),times(2));
   }return null;
  }).get(15,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
 }
}
