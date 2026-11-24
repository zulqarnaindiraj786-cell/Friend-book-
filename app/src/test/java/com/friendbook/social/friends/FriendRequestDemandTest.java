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
 @Test public void failedDeleteRestoresRowLocallyWithoutReload()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<com.friendbook.social.main.MainActivity> main=mockStatic(com.friendbook.social.main.MainActivity.class)){
   fb.when(Fb::uid).thenReturn("delete-demand-actor");FriendsFragment f=fragment("delete-demand-actor");UsersAdapter.Row row=request();
   Models.FriendRequest original=new Models.FriendRequest();original.id="request";original.fromUid="sender";original.fromName="Sender";original.fromPhoto="";original.status="pending";((List<Models.FriendRequest>)field("incoming").get(f)).add(original);
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference requests=mock(CollectionReference.class);DocumentReference doc=mock(DocumentReference.class);when(db.collection("friend_requests")).thenReturn(requests);when(requests.document("request")).thenReturn(doc);TaskCompletionSource<Void> pending=new TaskCompletionSource<>();when(doc.delete()).thenReturn(pending.getTask());
   for(int i=0;i<100;i++)f.secondary(row);verify(doc,times(1)).delete();
   pending.setException(new java.io.IOException("offline"));Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertTrue(((List<?>)field("incoming").get(f)).contains(original));verify(requests,never()).whereEqualTo(anyString(),any());
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
}
