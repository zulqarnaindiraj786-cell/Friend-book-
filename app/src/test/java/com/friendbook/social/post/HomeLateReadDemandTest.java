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
   when(query.getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(Long.MAX_VALUE))).thenReturn(old.getTask(),current.getTask());
   HomeFragment home=spy(new HomeFragment());doReturn(true).when(home).isAdded();doReturn(false).when(home).isHidden();
   FeedAdapter adapter=mock(FeedAdapter.class);set(home,"adapter",adapter);set(home,"rv",new RecyclerView(RuntimeEnvironment.getApplication()));set(home,"homeStarted",true);
   call(home,"startLivesListener");call(home,"invalidateHomeReads");call(home,"startLivesListener");
   QuerySnapshot empty=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false));
   current.setResult(empty);Shadows.shadowOf(Looper.getMainLooper()).idle();verify(adapter,times(1)).setLives(anyList());
   old.setResult(empty);Shadows.shadowOf(Looper.getMainLooper()).idle();verify(adapter,times(1)).setLives(anyList());
  }
 }
 @Test public void deferredAutomaticFeedTimeoutStartsAfterForegroundButExplicitRefreshStillTimesOut()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<com.friendbook.social.App> app=mockStatic(com.friendbook.social.App.class);
      MockedStatic<FeedHeadReads> feed=mockStatic(FeedHeadReads.class)){
   fb.when(Fb::uid).thenReturn("actor");app.when(com.friendbook.social.App::isForeground).thenReturn(true);
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference posts=mock(CollectionReference.class);Query query=mock(Query.class);
   when(db.collection("posts")).thenReturn(posts);when(posts.orderBy(eq("createdAt"),any(Query.Direction.class))).thenReturn(query);
   when(query.withFeedEngagement()).thenReturn(query);when(query.limit(anyLong())).thenReturn(query);
   HomeFragment home=spy(new HomeFragment());doReturn(true).when(home).isAdded();doReturn(false).when(home).isHidden();
   set(home,"adapter",mock(FeedAdapter.class));set(home,"rv",mock(RecyclerView.class));set(home,"homeStarted",true);
   java.lang.reflect.Method start=HomeFragment.class.getDeclaredMethod("startPostsQuery",boolean.class);start.setAccessible(true);
   start.invoke(home,false);app.when(com.friendbook.social.App::isForeground).thenReturn(false);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21));
   assertEquals(true,get(home,"loadingPosts"));assertEquals(false,get(home,"pageError"));
   app.when(com.friendbook.social.App::isForeground).thenReturn(true);ForegroundReadScope.resume();
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21));
   assertEquals(false,get(home,"loadingPosts"));assertEquals(true,get(home,"pageError"));
   start.invoke(home,true);app.when(com.friendbook.social.App::isForeground).thenReturn(false);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21));
   assertEquals(false,get(home,"loadingPosts"));assertEquals(true,get(home,"pageError"));
   call(home,"invalidateHomeReads");
  }
 }
 @Test public void scrollPageWaitsForForegroundAndExplicitRetryRemainsAvailable()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<com.friendbook.social.App> app=mockStatic(com.friendbook.social.App.class)){
   fb.when(Fb::uid).thenReturn("actor");app.when(com.friendbook.social.App::isForeground).thenReturn(false);
   HomeFragment home=spy(new HomeFragment());doReturn(true).when(home).isAdded();doReturn(false).when(home).isHidden();
   set(home,"adapter",mock(FeedAdapter.class));set(home,"rv",mock(RecyclerView.class));set(home,"homeStarted",true);
   set(home,"feedAccount","actor");set(home,"moreAvailable",true);
   DocumentSnapshot cursor=mock(DocumentSnapshot.class);Query query=mock(Query.class),page=mock(Query.class);
   set(home,"lastDoc",cursor);set(home,"feedQuery",query);when(query.startAfter(cursor)).thenReturn(page);when(page.limit(anyLong())).thenReturn(page);
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();
   when(page.getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(Long.MAX_VALUE))).thenReturn(pending.getTask());
   when(page.getCachedOrFetch(false)).thenReturn(new TaskCompletionSource<QuerySnapshot>().getTask());
   java.lang.reflect.Method scroll=HomeFragment.class.getDeclaredMethod("loadMore",boolean.class);scroll.setAccessible(true);scroll.invoke(home,true);
   ForegroundReadScope scope=(ForegroundReadScope)get(home,"pageReadScope");verify(page).getCachedOrFetchAutomatic(scope,Long.MAX_VALUE);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21));
   assertEquals(true,get(home,"loadingPosts"));assertEquals(false,get(home,"pageError"));
   app.when(com.friendbook.social.App::isForeground).thenReturn(true);ForegroundReadScope.resume();
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21));
   assertEquals(false,get(home,"loadingPosts"));assertEquals(true,get(home,"pageError"));assertFalse(scope.whenForeground().isSuccessful());
   home.loadMore();verify(page).getCachedOrFetch(false);
   call(home,"invalidateHomeReads");
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
