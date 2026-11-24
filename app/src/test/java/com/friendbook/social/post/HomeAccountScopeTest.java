package com.friendbook.social.post;
import com.friendbook.social.core.Fb;
import com.friendbook.social.main.HomeFragment;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class HomeAccountScopeTest {
 private java.lang.reflect.Field field(String name)throws Exception{java.lang.reflect.Field f=HomeFragment.class.getDeclaredField(name);f.setAccessible(true);return f;}
 private void prepare(HomeFragment h)throws Exception{java.lang.reflect.Method m=HomeFragment.class.getDeclaredMethod("prepareFeedAccount");m.setAccessible(true);m.invoke(h);}
 @Test public void switchClearsOldActorsModelsAndCursorBeforeNewRead()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   HomeFragment home=new HomeFragment();FeedAdapter adapter=mock(FeedAdapter.class);field("adapter").set(home,adapter);
   fb.when(Fb::uid).thenReturn("actor-a");prepare(home);verifyNoInteractions(adapter);
   ((Set<String>)field("loadedPostIds").get(home)).add("private-post-a");((List<String>)field("sessionOrderIds").get(home)).add("private-post-a");
   field("lastDoc").set(home,mock(DocumentSnapshot.class));field("loadedPosts").setBoolean(home,true);field("friendGraphActor").set(home,"actor-a");
   fb.when(Fb::uid).thenReturn("actor-b");prepare(home);
   assertEquals("actor-b",field("feedAccount").get(home));assertNull(field("lastDoc").get(home));assertFalse(field("loadedPosts").getBoolean(home));
   assertTrue(((Set<?>)field("loadedPostIds").get(home)).isEmpty());assertTrue(((List<?>)field("sessionOrderIds").get(home)).isEmpty());assertNull(field("friendGraphActor").get(home));
   verify(adapter).setPosts(argThat(List::isEmpty));verify(adapter).setStories(argThat(List::isEmpty));verify(adapter).setPymk(argThat(List::isEmpty));verify(adapter).setLives(argThat(List::isEmpty));
   fb.verify(Fb::db,never());
  }
 }
}
