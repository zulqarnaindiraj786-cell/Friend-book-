package com.friendbook.social.post;
import android.app.Activity;
import com.friendbook.social.core.*;
import com.friendbook.social.main.HomeFragment;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class HomeRefreshDemandTest {
 @Test public void hundredRefreshGesturesDuringPendingPageDoNotStartWork()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FeedAlgorithm> algorithm=mockStatic(FeedAlgorithm.class)){
   HomeFragment home=spy(new HomeFragment());doReturn(Robolectric.buildActivity(Activity.class).setup().get()).when(home).getActivity();
   java.lang.reflect.Field loading=HomeFragment.class.getDeclaredField("loadingPosts");loading.setAccessible(true);loading.setBoolean(home,true);
   java.lang.reflect.Method refresh=HomeFragment.class.getDeclaredMethod("refreshWithFriendbookAlgorithm",boolean.class,boolean.class);refresh.setAccessible(true);
   for(int tap=0;tap<100;tap++)refresh.invoke(home,true,true);
   fb.verifyNoInteractions();algorithm.verifyNoInteractions();
  }
 }
 @Test public void confirmedEmptyFeedClearsSavedPostsButCachedPaintDoesNot()throws Exception{
  try(MockedStatic<FastCache> cache=mockStatic(FastCache.class)){
   HomeFragment home=new HomeFragment();
   java.lang.reflect.Method consume=HomeFragment.class.getDeclaredMethod("consumePostsSnapshot",List.class,boolean.class);consume.setAccessible(true);
   consume.invoke(home,Collections.emptyList(),true);
   consume.invoke(home,Collections.emptyList(),false);
   cache.verify(()->FastCache.saveFeed(isNull(),argThat(List::isEmpty)),times(1));
  }
 }
 @Test public void automaticHomeEntryWaitsUntilResumedAndForeground()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<com.friendbook.social.App> app=mockStatic(com.friendbook.social.App.class)){
   HomeFragment home=spy(new HomeFragment());doReturn(false).when(home).isResumed();
   java.lang.reflect.Method refresh=HomeFragment.class.getDeclaredMethod("refreshPostsIfNeeded");refresh.setAccessible(true);
   refresh.invoke(home);
   doReturn(true).when(home).isResumed();app.when(com.friendbook.social.App::isForeground).thenReturn(false);
   refresh.invoke(home);fb.verifyNoInteractions();
  }
 }
 @Test public void loadedHomeDoesNotRefreshOnRepeatedReturnWithoutInvalidation()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<com.friendbook.social.App> app=mockStatic(com.friendbook.social.App.class)){
   fb.when(Fb::uid).thenReturn("actor");app.when(com.friendbook.social.App::isForeground).thenReturn(true);
   com.friendbook.social.d1.documents.D1DocumentStore db=mock(com.friendbook.social.d1.documents.D1DocumentStore.class);
   when(db.postWriteVersion()).thenReturn(7L);fb.when(Fb::db).thenReturn(db);
   HomeFragment home=spy(new HomeFragment());doReturn(false).when(home).isHidden();doReturn(true).when(home).isResumed();
   java.lang.reflect.Field loaded=HomeFragment.class.getDeclaredField("loadedPosts");loaded.setAccessible(true);loaded.setBoolean(home,true);
   java.lang.reflect.Field actor=HomeFragment.class.getDeclaredField("feedAccount");actor.setAccessible(true);actor.set(home,"actor");
   java.lang.reflect.Field version=HomeFragment.class.getDeclaredField("lastFeedWriteVersion");version.setAccessible(true);version.setLong(home,7L);
   java.lang.reflect.Method refresh=HomeFragment.class.getDeclaredMethod("refreshPostsIfNeeded");refresh.setAccessible(true);
   for(int i=0;i<100;i++)refresh.invoke(home);
   verify(db,never()).collection(anyString());
  }
 }

}
