package com.friendbook.social.post;

import android.app.Activity;
import com.friendbook.social.core.*;
import com.friendbook.social.main.HomeFragment;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.firebase.Timestamp;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class HomeStoryAnchorDemandTest {
 private static java.lang.reflect.Field field(String n)throws Exception{java.lang.reflect.Field f=HomeFragment.class.getDeclaredField(n);f.setAccessible(true);return f;}
 private static void start(HomeFragment h)throws Exception{java.lang.reflect.Method m=HomeFragment.class.getDeclaredMethod("startStoriesListener");m.setAccessible(true);m.invoke(h);}
 @Test public void oldExpiryAnchorRenewsOnVisibleDemandAndPendingOrFreshPageDoesNotRepeat()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   String actor="story-anchor-actor";fb.when(Fb::uid).thenReturn(actor);
   D1DocumentStore db=mock(D1DocumentStore.class);CollectionReference stories=mock(CollectionReference.class);Query q=mock(Query.class);
   fb.when(Fb::db).thenReturn(db);when(db.collection("stories")).thenReturn(stories);
   when(stories.whereGreaterThan(eq("expiresAt"),any(Timestamp.class))).thenReturn(q);
   when(q.orderBy("expiresAt",Query.Direction.ASCENDING)).thenReturn(q);when(q.limit(3)).thenReturn(q);
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();
   when(q.getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(StoryWindowPolicy.REUSE_MS))).thenReturn(pending.getTask());
   when(q.getCachedOrFetch(false,StoryWindowPolicy.REUSE_MS)).thenReturn(pending.getTask());
   Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
   long now=System.currentTimeMillis(),old=now-3_600_000L;
   activity.getSharedPreferences("fb_prefs",0).edit().putLong("storyAnchor:"+actor,old).commit();
   HomeFragment h=spy(new HomeFragment());doReturn(activity).when(h).getActivity();doReturn(false).when(h).isHidden();
   field("homeStarted").setBoolean(h,true);
   start(h);for(int i=0;i<100;i++)start(h);
   ArgumentCaptor<Timestamp> anchors=ArgumentCaptor.forClass(Timestamp.class);
   verify(stories,times(1)).whereGreaterThan(eq("expiresAt"),anchors.capture());
   long selected=anchors.getValue().toDate().getTime();assertTrue(selected>=now && selected<=System.currentTimeMillis());
   assertEquals(selected,activity.getSharedPreferences("fb_prefs",0).getLong("storyAnchor:"+actor,0));
   verify(q,times(1)).getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(StoryWindowPolicy.REUSE_MS));
   field("loadingStories").setBoolean(h,false);start(h);verify(stories,times(1)).whereGreaterThan(eq("expiresAt"),any(Timestamp.class));
   field("storyQueryAnchor").setLong(h,old);activity.getSharedPreferences("fb_prefs",0).edit().putLong("storyAnchor:"+actor,old).commit();
   h.loadMoreStories();verify(stories,times(2)).whereGreaterThan(eq("expiresAt"),any(Timestamp.class));
   verify(q,times(1)).getCachedOrFetch(false,StoryWindowPolicy.REUSE_MS);
  }
 }
}
