package com.friendbook.social.post;

import androidx.recyclerview.widget.RecyclerView;
import com.friendbook.social.core.Fb;
import com.friendbook.social.d1.documents.*;
import com.friendbook.social.main.HomeFragment;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.runner.RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class HomeVisibleFriendDemandTest {
 private Field field(String n)throws Exception{Field f=HomeFragment.class.getDeclaredField(n);f.setAccessible(true);return f;}
 private void invoke(HomeFragment h,String n,Class<?>[] types,Object...args)throws Exception{Method m=HomeFragment.class.getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(h,args);}
 private HomeFragment home()throws Exception{
  HomeFragment h=spy(new HomeFragment());doReturn(true).when(h).isAdded();doReturn(false).when(h).isHidden();
  field("homeStarted").setBoolean(h,true);field("rv").set(h,mock(RecyclerView.class));field("adapter").set(h,mock(FeedAdapter.class));return h;
 }
 @Test public void repeatedVisibleCandidatesShareOneBoundedReadAndUseConfirmedFacts()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   TaskCompletionSource<Map<String,Boolean>> pending=new TaskCompletionSource<>();when(db.friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class))).thenReturn(pending.getTask());
   HomeFragment h=home();List<String> ids=Arrays.asList("alice","bob","alice");
   for(int i=0;i<100;i++)invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);
   verify(db,times(1)).friendshipFactsAutomatic(argThat(list->list.equals(Arrays.asList("alice","bob"))),any(ForegroundReadScope.class));
   pending.setResult(Map.of("alice",true,"bob",false));Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertTrue(((Set<?>)field("friendIds").get(h)).contains("alice"));assertFalse(((Set<?>)field("friendIds").get(h)).contains("bob"));
   assertEquals(2,((Map<?,?>)field("friendFactsChecked").get(h)).size());
   for(int i=0;i<100;i++)invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);
   verify(db,times(1)).friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class));
  }
 }
 @Test public void stoppedScreenIgnoresLateFriendResults()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   TaskCompletionSource<Map<String,Boolean>> pending=new TaskCompletionSource<>();when(db.friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class))).thenReturn(pending.getTask());
   HomeFragment h=home();invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},Collections.singletonList("alice"));
   invoke(h,"invalidateHomeReads",new Class<?>[]{});field("homeStarted").setBoolean(h,false);
   pending.setResult(Collections.singletonMap("alice",true));Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertFalse(((Set<?>)field("friendIds").get(h)).contains("alice"));
  }
 }
 @Test public void incompleteResponseNeverProvesNonFriend()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   TaskCompletionSource<Map<String,Boolean>> pending=new TaskCompletionSource<>();when(db.friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class))).thenReturn(pending.getTask());
   HomeFragment h=home();List<String> ids=Arrays.asList("alice","bob");invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);
   pending.setResult(Collections.singletonMap("alice",true));Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertTrue(((Map<?,?>)field("friendFactsChecked").get(h)).isEmpty());assertTrue(((Set<?>)field("friendFactsFailed").get(h)).containsAll(ids));
   invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);verify(db,times(1)).friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class));
  }
 }
 @Test public void timeoutBlocksRetryAndIgnoresLateResultUntilNewEntry()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<com.friendbook.social.App> app=mockStatic(com.friendbook.social.App.class)){
   app.when(com.friendbook.social.App::isForeground).thenReturn(true);
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   TaskCompletionSource<Map<String,Boolean>> pending=new TaskCompletionSource<>();when(db.friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class))).thenReturn(pending.getTask());
   HomeFragment h=home();List<String> ids=Collections.singletonList("alice");invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21));
   assertTrue(((Set<?>)field("friendFactsFailed").get(h)).contains("alice"));
   for(int i=0;i<100;i++)invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);verify(db,times(1)).friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class));
   pending.setResult(Collections.singletonMap("alice",true));Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertFalse(((Set<?>)field("friendIds").get(h)).contains("alice"));
  }
 }

 @Test public void backgroundWaitDoesNotExpireFriendshipFactsBeforeForeground()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<com.friendbook.social.App> app=mockStatic(com.friendbook.social.App.class)){
   fb.when(Fb::uid).thenReturn("actor");app.when(com.friendbook.social.App::isForeground).thenReturn(false);
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   TaskCompletionSource<Map<String,Boolean>> pending=new TaskCompletionSource<>();
   when(db.friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class))).thenReturn(pending.getTask());
   HomeFragment h=home();invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},Collections.singletonList("alice"));
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21));
   assertTrue(((Set<?>)field("friendFactsPending").get(h)).contains("alice"));
   assertFalse(((Set<?>)field("friendFactsFailed").get(h)).contains("alice"));
   app.when(com.friendbook.social.App::isForeground).thenReturn(true);ForegroundReadScope.resume();
   pending.setResult(Collections.singletonMap("alice",true));Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertTrue(((Set<?>)field("friendIds").get(h)).contains("alice"));
   invoke(h,"invalidateHomeReads",new Class<?>[]{});
  }
 }

 @Test public void expiredOrWrongAccountFriendFactCannotRevealCachedContent()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");HomeFragment h=home();field("friendGraphActor").set(h,"actor");
   ((Set<String>)field("friendIds").get(h)).add("alice");
   ((Map<String,Long>)field("friendFactsChecked").get(h)).put("alice",android.os.SystemClock.elapsedRealtime());
   Method check=HomeFragment.class.getDeclaredMethod("isConfirmedFriend",String.class);check.setAccessible(true);
   assertTrue((Boolean)check.invoke(h,"alice"));
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(301));
   assertFalse((Boolean)check.invoke(h,"alice"));
   ((Map<String,Long>)field("friendFactsChecked").get(h)).put("alice",android.os.SystemClock.elapsedRealtime());
   fb.when(Fb::uid).thenReturn("other");assertFalse((Boolean)check.invoke(h,"alice"));
  }
 }

 @Test public void publicPostsDoNotStartMembershipReadsButFriendsPostsDo()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   when(db.friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class))).thenReturn(new TaskCompletionSource<Map<String,Boolean>>().getTask());
   HomeFragment h=home();List<String> candidates=new ArrayList<>();
   for(String privacy:new String[]{"Public","Only me",null}){
    com.friendbook.social.models.Models.Post post=new com.friendbook.social.models.Models.Post();post.authorId="public";post.privacy=privacy;
    invoke(h,"addPostFriendDemand",new Class<?>[]{List.class,com.friendbook.social.models.Models.Post.class},candidates,post);
   }
   invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},candidates);verifyNoInteractions(db);
   DocumentSnapshot doc=mock(DocumentSnapshot.class);when(doc.getData()).thenReturn(new HashMap<String,Object>(){{put("authorId","alice");put("privacy","friends");}});
   invoke(h,"addPostFriendDemand",new Class<?>[]{List.class,DocumentSnapshot.class},candidates,doc);
   invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},candidates);
   assertEquals(Collections.singletonList("alice"),candidates);verify(db,times(1)).friendshipFactsAutomatic(anyList(),any(ForegroundReadScope.class));
  }
 }

 @Test public void storyDemandSkipsAlreadyVisibleFollowedAndNeverVisibleStories()throws Exception{
  com.friendbook.social.models.Models.User previous=Fb.me;
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");Fb.me=new com.friendbook.social.models.Models.User();
   Fb.me.following.add("followed");Fb.me.blocked.add("blocked");HomeFragment h=home();List<String> ids=new ArrayList<>();
   for(String[] item:new String[][]{{"followed","Public"},{"followed","Friends"},{"stranger","Public"},{"blocked","Friends"},{"private","Only me"},{"actor","Friends"}}){
    DocumentSnapshot doc=mock(DocumentSnapshot.class);Map<String,Object> data=new HashMap<>();data.put("authorId",item[0]);data.put("privacy",item[1]);data.put("expiresAt",System.currentTimeMillis()+60000L);
    when(doc.getData()).thenReturn(data);invoke(h,"addStoryFriendDemand",new Class<?>[]{List.class,DocumentSnapshot.class},ids,doc);
   }
   assertEquals(Arrays.asList("followed","stranger"),ids);
  }finally{Fb.me=previous;}
 }

}
