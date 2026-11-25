package com.friendbook.social.friends;
import android.app.Activity;
import android.os.Looper;
import android.widget.TextView;
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
public class FriendRequestDemandTest {
 private java.lang.reflect.Field field(String n)throws Exception{java.lang.reflect.Field f=FriendsFragment.class.getDeclaredField(n);f.setAccessible(true);return f;}
 private FriendsFragment fragment(String actor)throws Exception{
  Activity a=Robolectric.buildActivity(Activity.class).setup().get();FriendsFragment f=spy(new FriendsFragment());doReturn(a).when(f).getActivity();doReturn(true).when(f).isAdded();doReturn(false).when(f).isHidden();
  field("started").setBoolean(f,true);field("pageActor").set(f,actor);field("adapter").set(f,mock(UsersAdapter.class));field("empty").set(f,new TextView(a));field("swipe").set(f,new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(a));
  TextView[] tabs=(TextView[])field("tabs").get(f);for(int i=0;i<tabs.length;i++)tabs[i]=new TextView(a);return f;
 }
 private UsersAdapter.Row request(){UsersAdapter.Row r=new UsersAdapter.Row();r.user=new Models.User();r.user.uid="sender";r.user.displayName="Sender";r.requestId="request";r.primaryLabel="Confirm";r.secondaryLabel="Delete";return r;}
 @Test public void hundredConfirmDemandsCommitOneBatchAndDoNotReloadList()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<Notify> notify=mockStatic(Notify.class);MockedStatic<com.friendbook.social.main.MainActivity> main=mockStatic(com.friendbook.social.main.MainActivity.class)){
   fb.when(Fb::uid).thenReturn("accept-demand-actor");FriendsFragment f=fragment("accept-demand-actor");UsersAdapter.Row row=request();
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference requests=mock(CollectionReference.class),friends=mock(CollectionReference.class),users=mock(CollectionReference.class);
   when(db.collection("friend_requests")).thenReturn(requests);when(db.collection("friends")).thenReturn(friends);when(db.collection("users")).thenReturn(users);when(requests.document("request")).thenReturn(mock(DocumentReference.class));when(friends.document()).thenReturn(mock(DocumentReference.class));when(users.document(anyString())).thenReturn(mock(DocumentReference.class));
   WriteBatch batch=mock(WriteBatch.class);when(db.batch()).thenReturn(batch);TaskCompletionSource<Void> pending=new TaskCompletionSource<>();when(batch.commit()).thenReturn(pending.getTask());
   for(int i=0;i<100;i++)f.primary(row);verify(batch,times(1)).commit();
   pending.setResult(null);Shadows.shadowOf(Looper.getMainLooper()).idle();
   for(int i=0;i<100;i++)f.primary(row);verify(batch,times(1)).commit();verify(requests,never()).whereEqualTo(anyString(),any());
   notify.verify(()->Notify.send("sender","friend_accept","accepted your friend request"),times(1));
  }
 }
 @Test public void failedConfirmRestoresRequestAndRelationshipLocally()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<Notify> notify=mockStatic(Notify.class);MockedStatic<com.friendbook.social.main.MainActivity> main=mockStatic(com.friendbook.social.main.MainActivity.class)){
   fb.when(Fb::uid).thenReturn("accept-failure-actor");FriendsFragment f=fragment("accept-failure-actor");UsersAdapter.Row row=request();
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference requests=mock(CollectionReference.class),friends=mock(CollectionReference.class),users=mock(CollectionReference.class);
   when(db.collection("friend_requests")).thenReturn(requests);when(db.collection("friends")).thenReturn(friends);when(db.collection("users")).thenReturn(users);when(requests.document("request")).thenReturn(mock(DocumentReference.class));when(friends.document()).thenReturn(mock(DocumentReference.class));when(users.document(anyString())).thenReturn(mock(DocumentReference.class));
   WriteBatch batch=mock(WriteBatch.class);when(db.batch()).thenReturn(batch);TaskCompletionSource<Void> pending=new TaskCompletionSource<>();when(batch.commit()).thenReturn(pending.getTask());
   Models.FriendRequest original=new Models.FriendRequest();original.id="request";original.fromUid="sender";original.fromName="Sender";original.fromPhoto="";original.status="pending";
   ((List<Models.FriendRequest>)field("incoming").get(f)).add(original);
   f.primary(row);verify(batch,times(1)).commit();
   pending.setException(new java.io.IOException("offline"));Shadows.shadowOf(Looper.getMainLooper()).idle();
   for(int i=0;i<100;i++)f.primary(row);verify(batch,times(1)).commit();verify(requests,never()).whereEqualTo(anyString(),any());
   notify.verifyNoInteractions();
   assertFalse(((Set<?>)field("dismissedReqIds").get(f)).contains("request"));
   assertFalse(((List<?>)field("friendIds").get(f)).contains("sender"));
   assertTrue(((List<?>)field("incoming").get(f)).contains(original));
  }
 }
 @Test public void failedDeleteRestoresRowLocallyWithoutReload()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<com.friendbook.social.main.MainActivity> main=mockStatic(com.friendbook.social.main.MainActivity.class)){
   fb.when(Fb::uid).thenReturn("delete-demand-actor");FriendsFragment f=fragment("delete-demand-actor");UsersAdapter.Row row=request();
   CursorPager pager=mock(CursorPager.class);field("activePager").set(f,pager);
   Models.FriendRequest original=new Models.FriendRequest();original.id="request";original.fromUid="sender";original.fromName="Sender";original.fromPhoto="";original.status="pending";((List<Models.FriendRequest>)field("incoming").get(f)).add(original);
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference requests=mock(CollectionReference.class);DocumentReference doc=mock(DocumentReference.class);when(db.collection("friend_requests")).thenReturn(requests);when(requests.document("request")).thenReturn(doc);TaskCompletionSource<Void> pending=new TaskCompletionSource<>();when(doc.delete()).thenReturn(pending.getTask());
   for(int i=0;i<100;i++)f.secondary(row);verify(doc,times(1)).delete();
   pending.setException(new java.io.IOException("offline"));Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertTrue(((List<?>)field("incoming").get(f)).contains(original));verify(requests,never()).whereEqualTo(anyString(),any());
   verify(pager,never()).forget("request");
  }
 }
 @Test public void repeatedPullJoinsOneTenRowHeadAndStopRejectsLateResult()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("requests-page-actor");FriendsFragment f=fragment("requests-page-actor");
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference requests=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("friend_requests")).thenReturn(requests);when(requests.whereEqualTo("toUid","requests-page-actor")).thenReturn(q);when(q.whereEqualTo("status","pending")).thenReturn(q);when(q.orderBy(eq("createdAt"),any(Query.Direction.class))).thenReturn(q);when(q.limit(10)).thenReturn(q);
   when(q.get(Source.CACHE)).thenReturn(Tasks.forException(new java.io.IOException("no cache")));TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.get(Source.SERVER)).thenReturn(pending.getTask());
   java.lang.reflect.Method start=FriendsFragment.class.getDeclaredMethod("startSection");start.setAccessible(true);
   for(int i=0;i<100;i++){field("forceSectionRefresh").setBoolean(f,true);start.invoke(f);Shadows.shadowOf(Looper.getMainLooper()).idle();}
   verify(q,times(1)).get(Source.SERVER);verify(q).limit(10);
   field("started").setBoolean(f,false);pending.setResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false)));Shadows.shadowOf(Looper.getMainLooper()).idle();
   verify(q,times(1)).get(Source.SERVER);
  }
 }
 @Test public void hundredSendDemandsUseOneWriteAndOldActorCannotNotify()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<Notify> notify=mockStatic(Notify.class)){
   fb.when(Fb::uid).thenReturn("send-request-actor");FriendsFragment f=fragment("send-request-actor");UsersAdapter.Row row=request();row.primaryLabel="＋ Add friend";
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference requests=mock(CollectionReference.class);when(db.collection("friend_requests")).thenReturn(requests);TaskCompletionSource<DocumentReference> pending=new TaskCompletionSource<>();when(requests.add(any())).thenReturn(pending.getTask());
   for(int i=0;i<100;i++)f.primary(row);verify(requests,times(1)).add(any());
   fb.when(Fb::uid).thenReturn("replacement-actor");pending.setResult(mock(DocumentReference.class));Shadows.shadowOf(Looper.getMainLooper()).idle();notify.verifyNoInteractions();
  }
 }
 @Test public void hundredSentRequestCancelsUseOneDeleteAndNoReload()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("cancel-request-actor");FriendsFragment f=fragment("cancel-request-actor");UsersAdapter.Row row=request();row.primaryLabel="✓ Requested";
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference requests=mock(CollectionReference.class);DocumentReference doc=mock(DocumentReference.class);when(db.collection("friend_requests")).thenReturn(requests);when(requests.document("request")).thenReturn(doc);TaskCompletionSource<Void> pending=new TaskCompletionSource<>();when(doc.delete()).thenReturn(pending.getTask());
   for(int i=0;i<100;i++)f.primary(row);verify(doc,times(1)).delete();
   pending.setResult(null);Shadows.shadowOf(Looper.getMainLooper()).idle();for(int i=0;i<100;i++)f.primary(row);verify(doc,times(1)).delete();verify(requests,never()).whereEqualTo(anyString(),any());
  }
 }
 @Test public void candidateChecksWaitForFriendFactsBeforePendingRequestReadsAndRemainScoped()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<com.friendbook.social.App> app=mockStatic(com.friendbook.social.App.class)){
   fb.when(Fb::uid).thenReturn("candidate-actor");app.when(com.friendbook.social.App::isForeground).thenReturn(false);
   FriendsFragment f=fragment("candidate-actor");Models.User u=new Models.User();u.uid="candidate";((List<Models.User>)field("suggestions").get(f)).add(u);
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   TaskCompletionSource<Map<String,Boolean>> facts=new TaskCompletionSource<>();when(db.friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class))).thenReturn(facts.getTask());
   CollectionReference requests=mock(CollectionReference.class);Query received=mock(Query.class),sent=mock(Query.class);when(db.collection("friend_requests")).thenReturn(requests);
   when(requests.whereEqualTo("toUid","candidate-actor")).thenReturn(received);when(requests.whereEqualTo("fromUid","candidate-actor")).thenReturn(sent);
   when(received.whereIn(eq("fromUid"),anyList())).thenReturn(received);when(sent.whereIn(eq("toUid"),anyList())).thenReturn(sent);
   when(received.whereEqualTo("status","pending")).thenReturn(received);when(sent.whereEqualTo("status","pending")).thenReturn(sent);
   when(received.limit(10)).thenReturn(received);when(sent.limit(10)).thenReturn(sent);
   when(received.getAutomatic(any(ForegroundReadScope.class))).thenReturn(new TaskCompletionSource<QuerySnapshot>().getTask());when(sent.getAutomatic(any(ForegroundReadScope.class))).thenReturn(new TaskCompletionSource<QuerySnapshot>().getTask());
   java.lang.reflect.Method check=FriendsFragment.class.getDeclaredMethod("checkCandidates",String.class,int.class);check.setAccessible(true);
   for(int i=0;i<100;i++)check.invoke(f,"candidate-actor",0);
   ForegroundReadScope scope=(ForegroundReadScope)field("sectionReadScope").get(f);
   verify(db,times(1)).friendshipFactsAutomatic(anyList(),eq(scope));verify(received,never()).getAutomatic(scope);verify(sent,never()).getAutomatic(scope);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21));
   assertTrue(((Set<?>)field("checkingUsers").get(f)).contains("candidate"));assertFalse(((Set<?>)field("failedCandidates").get(f)).contains("candidate"));
   facts.setResult(Map.of("candidate",false));Shadows.shadowOf(Looper.getMainLooper()).idle();
   verify(received,times(1)).getAutomatic(scope);verify(sent,times(1)).getAutomatic(scope);
   java.lang.reflect.Method close=FriendsFragment.class.getDeclaredMethod("closePage",boolean.class);close.setAccessible(true);close.invoke(f,false);
   assertFalse(scope.whenForeground().isSuccessful());assertTrue(((Set<?>)field("checkingUsers").get(f)).isEmpty());
  }
 }
 @Test public void alreadyFriendCandidatesSkipBothPendingRequestQueries()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<com.friendbook.social.App> app=mockStatic(com.friendbook.social.App.class)){
   fb.when(Fb::uid).thenReturn("existing-friend-actor");app.when(com.friendbook.social.App::isForeground).thenReturn(true);
   FriendsFragment f=fragment("existing-friend-actor");Models.User u=new Models.User();u.uid="already-friend";((List<Models.User>)field("suggestions").get(f)).add(u);
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   when(db.friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class))).thenReturn(Tasks.forResult(Map.of("already-friend",true)));
   CollectionReference requests=mock(CollectionReference.class);when(db.collection("friend_requests")).thenReturn(requests);
   java.lang.reflect.Method check=FriendsFragment.class.getDeclaredMethod("checkCandidates",String.class,int.class);check.setAccessible(true);
   check.invoke(f,"existing-friend-actor",0);Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertTrue(((Set<String>)field("candidateFriends").get(f)).contains("already-friend"));
   assertTrue(((Set<String>)field("checkedCandidates").get(f)).contains("already-friend"));
   verify(db,times(1)).friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class));
   verify(requests,never()).whereEqualTo(anyString(),any());
  }
 }
 @Test public void tenMissingFriendProfilesUseOneBatchWithoutForcedPerUserGets()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("profiles-request-actor");FriendsFragment f=fragment("profiles-request-actor");
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference users=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("users")).thenReturn(users);when(users.whereIn(any(com.friendbook.social.d1.documents.FieldPath.class),anyList())).thenReturn(q);when(q.limit(10)).thenReturn(q);when(q.getAutomatic(any(ForegroundReadScope.class))).thenReturn(new TaskCompletionSource<QuerySnapshot>().getTask());
   java.lang.reflect.Method ensure=FriendsFragment.class.getDeclaredMethod("ensureUser",String.class);ensure.setAccessible(true);java.lang.reflect.Method hydrate=FriendsFragment.class.getDeclaredMethod("hydrateFriendProfiles",String.class,int.class);hydrate.setAccessible(true);
   for(int demand=0;demand<100;demand++){for(int uid=0;uid<10;uid++)ensure.invoke(f,"friend-profile-"+uid);hydrate.invoke(f,"profiles-request-actor",0);}
   verify(q,times(1)).getAutomatic((ForegroundReadScope)field("sectionReadScope").get(f));verify(q,never()).get();verify(users).whereIn(any(com.friendbook.social.d1.documents.FieldPath.class),argThat(ids->ids.size()==10));fb.verify(()->Fb.getUserFresh(anyString()),never());
  }
 }

}
