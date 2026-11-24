package com.friendbook.social.post;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.d1.documents.*;
import com.friendbook.social.groups.GroupDetailActivity;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class GroupPageDemandTest {
 private void set(Object object,String name,Object value)throws Exception{java.lang.reflect.Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 @Test public void repeatedGroupEntryAndLoadMoreWhilePendingStartOnlyOneTenItemQuery()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("group-actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference posts=mock(CollectionReference.class);Query query=mock(Query.class);when(db.collection("posts")).thenReturn(posts);
   when(posts.whereEqualTo("groupId","g")).thenReturn(query);
   when(query.orderBy("createdAt",Query.Direction.DESCENDING)).thenReturn(query);when(query.withFeedEngagement()).thenReturn(query);when(query.limit(10)).thenReturn(query);
   when(query.get(Source.CACHE)).thenReturn(Tasks.forException(new Exception("no cache")));
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(query.get(Source.DEFAULT)).thenReturn(pending.getTask());
   GroupDetailActivity activity=Robolectric.buildActivity(GroupDetailActivity.class).get();
   Models.Group group=new Models.Group();group.privacy="Public";
   set(activity,"id","g");set(activity,"group",group);set(activity,"groupScreenStarted",true);set(activity,"adapter",mock(FeedAdapter.class));
   java.lang.reflect.Method start=activity.getClass().getDeclaredMethod("startGroupPostPages");start.setAccessible(true);
   start.invoke(activity);Shadows.shadowOf(Looper.getMainLooper()).idle();
   for(int repeat=0;repeat<100;repeat++){start.invoke(activity);activity.loadMore();}
   verify(query,times(1)).get(Source.DEFAULT);verify(query,never()).limit(120);verify(query,times(1)).limit(10);
   java.lang.reflect.Field field=activity.getClass().getDeclaredField("groupPostPager");field.setAccessible(true);
   ((CursorPager)field.get(activity)).close();
   pending.setResult(new QuerySnapshot(java.util.Collections.emptyList(),null,new SnapshotMetadata(false,false)));Shadows.shadowOf(Looper.getMainLooper()).idle();
   verify(query,times(1)).get(Source.DEFAULT);
  }
 }
}
