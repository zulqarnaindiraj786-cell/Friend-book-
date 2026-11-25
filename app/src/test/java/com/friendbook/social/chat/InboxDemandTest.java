package com.friendbook.social.chat;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.*;
import java.time.Duration;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class InboxDemandTest {
 private java.lang.reflect.Field field(String n)throws Exception{java.lang.reflect.Field f=ConversationsActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
 @Test public void hundredInboxDemandsUseOneTenRowQueryAndTimeoutDoesNotAutoRetry()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class)){
   fb.when(Fb::uid).thenReturn("inbox-actor");ConversationsActivity a=Robolectric.buildActivity(ConversationsActivity.class).get();
   field("inboxStarted").setBoolean(a,true);field("inboxActor").set(a,"inbox-actor");field("inboxSwipe").set(a,new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(a));
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference chats=mock(CollectionReference.class);when(db.collection("chats")).thenReturn(chats);Query q=mock(Query.class);
   when(chats.whereArrayContains("participants","inbox-actor")).thenReturn(q);when(q.orderBy(eq("inboxActivityAt"),any(Query.Direction.class))).thenReturn(q);when(q.limit(10)).thenReturn(q);
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.getCachedOrFetch(false)).thenReturn(pending.getTask());
   java.lang.reflect.Method load=ConversationsActivity.class.getDeclaredMethod("loadInboxPage",boolean.class);load.setAccessible(true);
   for(int i=0;i<100;i++)load.invoke(a,true);verify(q,times(1)).getCachedOrFetch(false);verify(q).limit(10);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));assertFalse(field("inboxLoading").getBoolean(a));
   pending.setResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false)));Shadows.shadowOf(Looper.getMainLooper()).idle();
   cache.verify(()->FastCache.saveChats(anyList()),never());verify(q,times(1)).getCachedOrFetch(false);
   field("inboxStarted").setBoolean(a,false);load.invoke(a,true);verify(q,times(1)).getCachedOrFetch(false);
  }
 }
}
