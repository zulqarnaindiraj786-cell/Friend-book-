package com.friendbook.social.profile;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.*;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class ProfileDemandTest {
 private java.lang.reflect.Field field(String n)throws Exception{java.lang.reflect.Field f=ProfileActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
 private Object call(ProfileActivity a,String n,Class<?>[] types,Object...args)throws Exception{java.lang.reflect.Method m=ProfileActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(a,args);}
 private ProfileActivity activity(String actor,String target)throws Exception{
  ProfileActivity a=Robolectric.buildActivity(ProfileActivity.class).get();field("profileActor").set(a,actor);field("uid").set(a,target);field("mine").setBoolean(a,actor.equals(target));field("profileStarted").setBoolean(a,true);field("profileReadScope").set(a,new ForegroundReadScope());return a;
 }
 @Test public void ownAndVisitorRepeatedStartOnlyStartOnePostHeadAndVisitorRelationshipDemand()throws Exception{
  for(boolean own:new boolean[]{true,false}){
   String actor=own?"own-profile-actor":"visitor-profile-actor",target=own?actor:"visited-profile";
   try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<CountReads> counts=mockStatic(CountReads.class);
       MockedStatic<com.friendbook.social.App> app=mockStatic(com.friendbook.social.App.class)){
    fb.when(Fb::uid).thenReturn(actor);ProfileActivity a=activity(actor,target);field("profileStarted").setBoolean(a,false);
    D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference users=mock(CollectionReference.class),posts=mock(CollectionReference.class),friends=mock(CollectionReference.class),requests=mock(CollectionReference.class);DocumentReference profile=mock(DocumentReference.class);Query post=mock(Query.class),relationship=mock(Query.class);
    when(db.collection("users")).thenReturn(users);when(users.document(target)).thenReturn(profile);when(profile.withProfileAudience()).thenReturn(profile);when(profile.addSnapshotListener(any())).thenReturn(mock(ListenerRegistration.class));
    when(db.collection("posts")).thenReturn(posts);when(posts.whereEqualTo("authorId",target)).thenReturn(post);when(post.orderBy(eq("createdAt"),any(Query.Direction.class))).thenReturn(post);when(post.withFeedEngagement()).thenReturn(post);when(post.limit(10)).thenReturn(post);when(post.get(Source.CACHE)).thenReturn(new TaskCompletionSource<QuerySnapshot>().getTask());
    when(db.collection("friends")).thenReturn(friends);when(friends.whereIn(eq("participants"),anyList())).thenReturn(relationship);
    when(db.collection("friend_requests")).thenReturn(requests);when(requests.whereEqualTo(eq("fromUid"),any())).thenReturn(relationship);when(relationship.whereEqualTo(anyString(),any())).thenReturn(relationship);when(relationship.limit(1)).thenReturn(relationship);when(relationship.getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(300_000L))).thenReturn(new TaskCompletionSource<QuerySnapshot>().getTask());
    for(int i=0;i<100;i++)call(a,"onStart",new Class<?>[]{});
    int generation=field("profileStartGeneration").getInt(a);
    // Started-but-not-resumed and background states must not initiate reads.
    app.when(com.friendbook.social.App::isForeground).thenReturn(true);
    call(a,"startResumedProfile",new Class<?>[]{String.class,int.class},actor,generation);
    field("profileResumed").setBoolean(a,true);
    app.when(com.friendbook.social.App::isForeground).thenReturn(false);
    call(a,"startResumedProfile",new Class<?>[]{String.class,int.class},actor,generation);
    verify(db,never()).collection(anyString());
    app.when(com.friendbook.social.App::isForeground).thenReturn(true);
    for(int i=0;i<100;i++)call(a,"startResumedProfile",new Class<?>[]{String.class,int.class},actor,generation);
    verify(post,times(1)).get(Source.CACHE);verify(profile,times(1)).addSnapshotListener(any());verify(relationship,times(own?0:3)).getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(300_000L));verify(db,never()).collection("subscriptions");
    call(a,"onStop",new Class<?>[]{});
   }
  }
 }
 @Test public void countIsSingleFlightReusedAcrossProfileOpensAndInvalidatedByPostWrites()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<CountReads> counts=mockStatic(CountReads.class)){
   fb.when(Fb::uid).thenReturn("count-profile-actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference posts=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("posts")).thenReturn(posts);when(posts.whereEqualTo("authorId","count-profile-target")).thenReturn(q);
   final java.util.function.LongConsumer[] completion={null};counts.when(()->CountReads.onceAutomatic(eq(q),any(ForegroundReadScope.class),any(java.util.function.LongConsumer.class),any())).thenAnswer(inv->{completion[0]=inv.getArgument(2);return mock(ListenerRegistration.class);});
   ProfileActivity a=activity("count-profile-actor","count-profile-target");for(int i=0;i<100;i++)call(a,"refreshPostCount",new Class<?>[]{int.class},0);completion[0].accept(42);
   ProfileActivity next=activity("count-profile-actor","count-profile-target");for(int i=0;i<100;i++)call(next,"refreshPostCount",new Class<?>[]{int.class},0);
   counts.verify(()->CountReads.onceAutomatic(eq(q),any(ForegroundReadScope.class),any(java.util.function.LongConsumer.class),any()),times(1));assertEquals(42,field("userPostCount").getInt(next));
   when(db.postWriteVersion()).thenReturn(1L);call(next,"refreshPostCount",new Class<?>[]{int.class},0);counts.verify(()->CountReads.onceAutomatic(eq(q),any(ForegroundReadScope.class),any(java.util.function.LongConsumer.class),any()),times(2));
  }
 }
 @Test public void friendsProfilesBatchOnceAndStoppedResponseDoesNotPopulateCache()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class)){
   fb.when(Fb::uid).thenReturn("friends-profile-actor");ProfileActivity a=activity("friends-profile-actor","friend-owner");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference users=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("users")).thenReturn(users);when(users.whereIn(any(FieldPath.class),anyList())).thenReturn(q);when(q.limit(10)).thenReturn(q);TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.get()).thenReturn(pending.getTask());
   for(int i=0;i<100;i++){for(int j=0;j<10;j++)call(a,"ensureUser",new Class<?>[]{String.class},"profile-friend-"+j);call(a,"hydrateFriendProfiles",new Class<?>[]{String.class,int.class},"friends-profile-actor",0);}
   verify(q,times(1)).get();fb.verify(()->Fb.getUser(anyString()),never());
   field("profileStarted").setBoolean(a,false);QueryDocumentSnapshot doc=mock(QueryDocumentSnapshot.class);pending.setResult(new QuerySnapshot(Collections.singletonList(doc),null,new SnapshotMetadata(false,false)));Shadows.shadowOf(Looper.getMainLooper()).idle();cache.verify(()->FastCache.putUser(any()),never());
  }
 }
 @Test public void disabledOrOwnSubscriptionsDoNotFetchAndEnabledVisitorFetchesOnce()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("subscription-profile-actor");ProfileActivity a=activity("subscription-profile-actor","subscription-profile-actor");Models.User u=new Models.User();u.subscriptionsEnabled=true;field("user").set(a,u);
   for(int i=0;i<100;i++)call(a,"watchSubscription",new Class<?>[]{});fb.verify(Fb::db,never());
   field("mine").setBoolean(a,false);field("uid").set(a,"subscription-target");u.subscriptionsEnabled=false;for(int i=0;i<100;i++)call(a,"watchSubscription",new Class<?>[]{});fb.verify(Fb::db,never());
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference subscriptions=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("subscriptions")).thenReturn(subscriptions);when(subscriptions.whereEqualTo("creatorId","subscription-target")).thenReturn(q);when(q.whereEqualTo("subscriberId","subscription-profile-actor")).thenReturn(q);when(q.limit(1)).thenReturn(q);when(q.getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(300_000L))).thenReturn(new TaskCompletionSource<QuerySnapshot>().getTask());
   u.subscriptionsEnabled=true;for(int i=0;i<100;i++)call(a,"watchSubscription",new Class<?>[]{});verify(q,times(1)).getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(300_000L));
  }
 }
 @Test public void repeatedProfileHeaderRendersDoNotFetchHighlightsUntilExplicitDemand()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("highlights-profile-actor");ProfileActivity a=activity("highlights-profile-actor","highlights-owner");Models.User u=new Models.User();for(int i=0;i<30;i++)u.highlightStoryIds.add("highlight-"+i);field("user").set(a,u);
   for(String name:new String[]{"personalDetailsCard","highlightsRow","highlightsWrap"})field(name).set(a,new android.widget.LinearLayout(a));
   for(int i=0;i<100;i++)call(a,"renderPersonalDetailsAndHighlights",new Class<?>[]{boolean.class},false);fb.verify(Fb::db,never());
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference stories=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("stories")).thenReturn(stories);when(stories.whereIn(any(FieldPath.class),anyList())).thenReturn(q);when(q.limit(10)).thenReturn(q);when(q.get()).thenReturn(new TaskCompletionSource<QuerySnapshot>().getTask());
   for(int i=0;i<100;i++)call(a,"loadHighlightPage",new Class<?>[]{});verify(q,times(1)).get();verify(stories).whereIn(any(FieldPath.class),argThat(ids->ids.size()==10));
  }
 }
 @Test public void requestBurstWritesOnceAndOldActorCompletionCannotNotify()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<Notify> notifications=mockStatic(Notify.class)){
   fb.when(Fb::uid).thenReturn("request-profile-actor");ProfileActivity a=activity("request-profile-actor","request-profile-target");
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference requests=mock(CollectionReference.class);when(db.collection("friend_requests")).thenReturn(requests);TaskCompletionSource<DocumentReference> pending=new TaskCompletionSource<>();when(requests.add(any())).thenReturn(pending.getTask());
   for(int i=0;i<100;i++)call(a,"sendProfileRequest",new Class<?>[]{boolean.class},false);
   verify(requests,times(1)).add(any());assertTrue(field("requestBusy").getBoolean(a));
   fb.when(Fb::uid).thenReturn("different-profile-actor");pending.setResult(mock(DocumentReference.class));Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertFalse(field("hasOut").getBoolean(a));notifications.verifyNoInteractions();
  }
 }

}
