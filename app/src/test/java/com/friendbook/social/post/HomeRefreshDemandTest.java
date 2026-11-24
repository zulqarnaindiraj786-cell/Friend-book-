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
}
