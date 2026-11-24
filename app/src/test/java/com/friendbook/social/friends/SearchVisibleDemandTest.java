package com.friendbook.social.friends;

import android.os.Looper;
import android.widget.EditText;
import com.friendbook.social.core.*;
import com.friendbook.social.d1.documents.*;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.FeedAdapter;
import com.google.android.gms.tasks.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class SearchVisibleDemandTest {
 private static Object field(SearchActivity a,String name)throws Exception{
  Field f=SearchActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);
 }
 @Test public void typingPreviewIsCacheOnlyAndStoppedDebounceCannotStartAnyPage()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> fast=mockStatic(FastCache.class,CALLS_REAL_METHODS)){
   fb.when(Fb::uid).thenReturn("search-visible-actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference users=mock(CollectionReference.class),posts=mock(CollectionReference.class);
   Query userQuery=mock(Query.class),postQuery=mock(Query.class),userHead=mock(Query.class),postHead=mock(Query.class);
   when(db.collection("users")).thenReturn(users);when(db.collection("posts")).thenReturn(posts);
   when(users.orderBy("displayName",Query.Direction.ASCENDING)).thenReturn(userQuery);
   when(userQuery.startAt(anyString())).thenReturn(userQuery);when(userQuery.endAt(anyString())).thenReturn(userQuery);
   when(userQuery.limit(10)).thenReturn(userHead);when(posts.orderBy("createdAt",Query.Direction.DESCENDING)).thenReturn(postQuery);
   when(postQuery.limit(10)).thenReturn(postHead);
   QuerySnapshot cached=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(true,false));
   when(userHead.get(Source.CACHE)).thenReturn(Tasks.forResult(cached));when(postHead.get(Source.CACHE)).thenReturn(Tasks.forResult(cached));
   TaskCompletionSource<QuerySnapshot> peopleResult=new TaskCompletionSource<>(),postResult=new TaskCompletionSource<>();
   when(userHead.get(Source.DEFAULT)).thenReturn(peopleResult.getTask());when(postHead.get(Source.DEFAULT)).thenReturn(postResult.getTask());
   Models.Post local=new Models.Post();local.id="local-post";local.authorId="author";local.authorName="";local.content="Alice";
   fast.when(FastCache::getFeed).thenReturn(Collections.singletonList(local));
   ActivityController<SearchActivity> controller=Robolectric.buildActivity(SearchActivity.class).create().start().resume();
   SearchActivity a=controller.get();((EditText)field(a,"input")).setText("Alice");
   assertTrue(((FeedAdapter)field(a,"postsAdapter")).feedEngagementInPage);
   controller.pause().stop();Shadows.shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS);
   fb.verify(Fb::db,never());
   controller.restart().start().resume();Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.SECONDS);
   verify(userHead,times(1)).get(Source.DEFAULT);verify(postHead,times(1)).get(Source.DEFAULT);
   controller.pause().stop();
   QuerySnapshot server=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false));
   peopleResult.setResult(server);postResult.setResult(server);Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.SECONDS);
   verify(userHead,times(1)).get(Source.DEFAULT);verify(postHead,times(1)).get(Source.DEFAULT);
   assertTrue(((FeedAdapter)field(a,"postsAdapter")).feedEngagementInPage);
   controller.destroy();
  }
 }
}
