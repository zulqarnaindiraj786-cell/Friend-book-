package com.friendbook.social.post;
import android.os.Looper;
import androidx.recyclerview.widget.RecyclerView;
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
public class HomeLateReadDemandTest {
 private void set(HomeFragment home,String name,Object value)throws Exception{java.lang.reflect.Field f=HomeFragment.class.getDeclaredField(name);f.setAccessible(true);f.set(home,value);}
 private Object get(HomeFragment home,String name)throws Exception{java.lang.reflect.Field f=HomeFragment.class.getDeclaredField(name);f.setAccessible(true);return f.get(home);}
 private void call(HomeFragment home,String name)throws Exception{java.lang.reflect.Method m=HomeFragment.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(home);}
 @Test public void priorVisibleSessionResponseCannotRepaintNewVisibleSession()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("home-lifetime-actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference lives=mock(CollectionReference.class);Query query=mock(Query.class);when(db.collection("lives")).thenReturn(lives);
   when(lives.whereEqualTo("status","live")).thenReturn(query);when(query.limit(3)).thenReturn(query);
   TaskCompletionSource<QuerySnapshot> old=new TaskCompletionSource<>(),current=new TaskCompletionSource<>();
   when(query.getCachedOrFetch(false)).thenReturn(old.getTask(),current.getTask());
   HomeFragment home=spy(new HomeFragment());doReturn(true).when(home).isAdded();doReturn(false).when(home).isHidden();
   FeedAdapter adapter=mock(FeedAdapter.class);set(home,"adapter",adapter);set(home,"rv",new RecyclerView(RuntimeEnvironment.getApplication()));set(home,"homeStarted",true);
   call(home,"startLivesListener");call(home,"invalidateHomeReads");call(home,"startLivesListener");
   QuerySnapshot empty=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false));
   current.setResult(empty);Shadows.shadowOf(Looper.getMainLooper()).idle();verify(adapter,times(1)).setLives(anyList());
   old.setResult(empty);Shadows.shadowOf(Looper.getMainLooper()).idle();verify(adapter,times(1)).setLives(anyList());
  }
 }
 @Test public void pauseMakesInterruptedInitialStoriesAndSuggestionsRetryableAndStopsHiddenPagination()throws Exception{
  HomeFragment home=spy(new HomeFragment());doReturn(true).when(home).isHidden();
  set(home,"storyQuery",mock(Query.class));set(home,"loadingStories",true);set(home,"notesInFlight",true);set(home,"loadingPosts",true);set(home,"headerRequested",true);
  call(home,"invalidateHomeReads");
  assertNull(get(home,"storyQuery"));assertEquals(false,get(home,"loadingStories"));assertEquals(false,get(home,"notesInFlight"));assertEquals(false,get(home,"loadingPosts"));assertEquals(false,get(home,"headerRequested"));
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   for(int i=0;i<100;i++){home.loadMore();home.loadMoreStories();}fb.verifyNoInteractions();
  }
 }
}
