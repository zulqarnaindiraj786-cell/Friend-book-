package com.friendbook.social.main;
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
public class NotificationDemandTest {
 private java.lang.reflect.Field field(String n)throws Exception{java.lang.reflect.Field f=NotificationsFragment.class.getDeclaredField(n);f.setAccessible(true);return f;}
 private void call(NotificationsFragment f,String name)throws Exception{java.lang.reflect.Method m=NotificationsFragment.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(f);}
 private NotificationsFragment fragment(String actor)throws Exception{
  Activity a=Robolectric.buildActivity(Activity.class).setup().get();NotificationsFragment f=spy(new NotificationsFragment());
  doReturn(a).when(f).getActivity();doReturn(true).when(f).isAdded();doReturn(false).when(f).isHidden();doNothing().when(f).startActivity(any(android.content.Intent.class));
  field("notificationsStarted").setBoolean(f,true);field("loadedActor").set(f,actor);field("adapter").set(f,f.new NotifAdapter());
  field("moreButton").set(f,new TextView(a));field("empty").set(f,new TextView(a));field("swipe").set(f,new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(a));return f;
 }
 @Test public void hundredPullRefreshesJoinOnePendingTenRowRequest()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("notif-refresh-actor");NotificationsFragment f=fragment("notif-refresh-actor");
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference collection=mock(CollectionReference.class);Query q=mock(Query.class);
   when(db.collection("notifications")).thenReturn(collection);when(collection.whereEqualTo("toUserId","notif-refresh-actor")).thenReturn(q);when(q.orderBy(eq("createdAt"),any(Query.Direction.class))).thenReturn(q);when(q.limit(10)).thenReturn(q);
   when(q.get(Source.CACHE)).thenReturn(Tasks.forException(new java.io.IOException("no cache")));TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.get(Source.SERVER)).thenReturn(pending.getTask());
   java.lang.reflect.Method start=NotificationsFragment.class.getDeclaredMethod("startPages",boolean.class);start.setAccessible(true);
   start.invoke(f,true);Shadows.shadowOf(Looper.getMainLooper()).idle();for(int i=0;i<100;i++)start.invoke(f,true);
   verify(q,times(1)).get(Source.SERVER);verify(q,times(1)).limit(10);
   call(f,"stopPages");pending.setResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false)));Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertFalse(((androidx.swiperefreshlayout.widget.SwipeRefreshLayout)field("swipe").get(f)).isRefreshing());
  }
 }
 @Test public void hiddenBulkReadCompletionCannotStartWrites()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("notif-bulk-actor");NotificationsFragment f=fragment("notif-bulk-actor");
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference collection=mock(CollectionReference.class);Query q=mock(Query.class);
   when(db.collection("notifications")).thenReturn(collection);when(collection.whereEqualTo("toUserId","notif-bulk-actor")).thenReturn(q);when(q.whereEqualTo("isRead",false)).thenReturn(q);when(q.orderBy(eq("createdAt"),any(Query.Direction.class))).thenReturn(q);when(q.limit(10)).thenReturn(q);
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.get(Source.SERVER)).thenReturn(pending.getTask());
   for(int i=0;i<100;i++)call(f,"markAllReadReal");verify(q,times(1)).get(Source.SERVER);verify(q).limit(10);
   doReturn(true).when(f).isHidden();
   pending.setResult(new QuerySnapshot(Collections.singletonList(mock(QueryDocumentSnapshot.class)),null,new SnapshotMetadata(false,false)));Shadows.shadowOf(Looper.getMainLooper()).idle();
   verify(db,never()).batch();
  }
 }
 @Test public void failedSingleReadDoesNotReplayWritesOnRepeatedTaps()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<InteractionCooldown> cooldown=mockStatic(InteractionCooldown.class)){
   fb.when(Fb::uid).thenReturn("notif-open-actor");cooldown.when(()->InteractionCooldown.acquire(anyString(),anyString())).thenReturn(true);NotificationsFragment f=fragment("notif-open-actor");
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference collection=mock(CollectionReference.class);DocumentReference doc=mock(DocumentReference.class);when(db.collection("notifications")).thenReturn(collection);when(collection.document("read-id")).thenReturn(doc);
   TaskCompletionSource<Void> pending=new TaskCompletionSource<>();when(doc.update("isRead",true)).thenReturn(pending.getTask());
   Models.Notif n=new Models.Notif();n.id="read-id";n.type="follow";n.fromUserId="author";
   java.lang.reflect.Method open=NotificationsFragment.class.getDeclaredMethod("open",Models.Notif.class);open.setAccessible(true);
   for(int i=0;i<100;i++)open.invoke(f,n);verify(doc,times(1)).update("isRead",true);
   pending.setException(new java.io.IOException("offline"));Shadows.shadowOf(Looper.getMainLooper()).idle();
   for(int i=0;i<100;i++)open.invoke(f,n);verify(doc,times(1)).update("isRead",true);assertFalse(n.isRead);
  }
 }
 @Test public void hundredNotificationRebindsDoNotFetchCosmeticAuthorBadges()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("notif-bind-actor");NotificationsFragment f=fragment("notif-bind-actor");NotificationsFragment.NotifAdapter adapter=(NotificationsFragment.NotifAdapter)field("adapter").get(f);
   Models.Notif n=new Models.Notif();n.id="bind-id";n.fromUserId="uncached-author";n.fromUserName="Author";n.type="like";
   ((List<Models.Notif>)field("data").get(f)).add(n);
   NotificationsFragment.NotifAdapter.VH holder=adapter.onCreateViewHolder(new android.widget.FrameLayout(f.getActivity()),0);
   for(int i=0;i<100;i++)adapter.onBindViewHolder(holder,0);fb.verify(Fb::db,never());fb.verify(()->Fb.getUser(anyString()),never());
  }
 }
}
