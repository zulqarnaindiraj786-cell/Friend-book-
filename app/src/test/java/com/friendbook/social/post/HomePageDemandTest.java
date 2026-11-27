package com.friendbook.social.post;
import android.app.Activity;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.main.HomeFragment;
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
public class HomePageDemandTest {
 private java.lang.reflect.Field field(String n)throws Exception{java.lang.reflect.Field f=HomeFragment.class.getDeclaredField(n);f.setAccessible(true);return f;}
 @Test public void hundredPageDemandsUseOneTenRowQueryAndOfflineResultKeepsCursor()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("page-actor");HomeFragment h=spy(new HomeFragment());doReturn(true).when(h).isAdded();doReturn(false).when(h).isHidden();
   doReturn(Robolectric.buildActivity(Activity.class).setup().get()).when(h).getActivity();field("adapter").set(h,mock(FeedAdapter.class));
   field("homeStarted").setBoolean(h,true);field("feedAccount").set(h,"page-actor");field("moreAvailable").setBoolean(h,true);
   DocumentSnapshot cursor=mock(DocumentSnapshot.class);field("lastDoc").set(h,cursor);
   Query q=mock(Query.class);when(q.startAfter(cursor)).thenReturn(q);when(q.limit(10)).thenReturn(q);field("feedQuery").set(h,q);
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.getCachedOrFetch(false)).thenReturn(pending.getTask());
   for(int i=0;i<100;i++)h.loadMore();verify(q,times(1)).getCachedOrFetch(false);verify(q).limit(10);
   pending.setResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(true,false)));Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertSame(cursor,field("lastDoc").get(h));assertTrue(field("moreAvailable").getBoolean(h));assertTrue(field("pageError").getBoolean(h));assertFalse(field("loadingPosts").getBoolean(h));
   verify(q,times(1)).getCachedOrFetch(false);fb.verify(Fb::db,never());
   TaskCompletionSource<QuerySnapshot> retry=new TaskCompletionSource<>();when(q.get(Source.SERVER)).thenReturn(retry.getTask(),Tasks.forResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false))));
   h.loadMore();for(int i=0;i<100;i++)h.loadMore();verify(q,times(1)).get(Source.SERVER);
   assertSame(cursor,field("lastDoc").get(h));assertTrue(field("loadingPosts").getBoolean(h));
   retry.setException(new RuntimeException("offline"));Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertSame(cursor,field("lastDoc").get(h));assertTrue(field("pageError").getBoolean(h));
   h.loadMore();Shadows.shadowOf(Looper.getMainLooper()).idle();verify(q,times(2)).get(Source.SERVER);
   assertFalse(field("moreAvailable").getBoolean(h));assertFalse(field("pageError").getBoolean(h));
  }
 }
 @Test public void authoritativeEmptyPageStopsWithoutDrainingAnotherPage()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("empty-actor");HomeFragment h=spy(new HomeFragment());doReturn(true).when(h).isAdded();doReturn(false).when(h).isHidden();
   doReturn(Robolectric.buildActivity(Activity.class).setup().get()).when(h).getActivity();
   field("homeStarted").setBoolean(h,true);field("feedAccount").set(h,"empty-actor");field("moreAvailable").setBoolean(h,true);field("adapter").set(h,mock(FeedAdapter.class));
   DocumentSnapshot cursor=mock(DocumentSnapshot.class);field("lastDoc").set(h,cursor);
   Query q=mock(Query.class);when(q.startAfter(cursor)).thenReturn(q);when(q.limit(10)).thenReturn(q);field("feedQuery").set(h,q);
   when(q.getCachedOrFetch(false)).thenReturn(Tasks.forResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false))));
   h.loadMore();Shadows.shadowOf(Looper.getMainLooper()).idle();for(int i=0;i<100;i++)h.loadMore();
   assertFalse(field("moreAvailable").getBoolean(h));verify(q,times(1)).getCachedOrFetch(false);
  }
 }
}
