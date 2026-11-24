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
 private QueryDocumentSnapshot friend(String a,String b){
  QueryDocumentSnapshot d=mock(QueryDocumentSnapshot.class);
  when(d.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)).thenReturn(Collections.singletonMap("participants",Arrays.asList(a,b)));
  return d;
 }
 @Test public void repeatedVisibleCandidatesShareOneBoundedReadAndUseConfirmedFacts()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference friends=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("friends")).thenReturn(friends);
   when(friends.whereIn(eq("participants"),anyList())).thenReturn(q);when(q.limit(10)).thenReturn(q);
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.get()).thenReturn(pending.getTask());
   HomeFragment h=home();List<String> ids=Arrays.asList("alice","bob","alice");
   for(int i=0;i<100;i++)invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);
   verify(q,times(1)).get();verify(friends).whereIn(eq("participants"),argThat(pairs->pairs.size()==4));
   pending.setResult(new QuerySnapshot(Collections.singletonList(friend("actor","alice")),null,new SnapshotMetadata(false,false)));
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertTrue(((Set<?>)field("friendIds").get(h)).contains("alice"));assertFalse(((Set<?>)field("friendIds").get(h)).contains("bob"));
   assertEquals(2,((Map<?,?>)field("friendFactsChecked").get(h)).size());
   for(int i=0;i<100;i++)invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);
   verify(q,times(1)).get();
  }
 }
 @Test public void stoppedScreenIgnoresLateFriendResults()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference friends=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("friends")).thenReturn(friends);
   when(friends.whereIn(eq("participants"),anyList())).thenReturn(q);when(q.limit(10)).thenReturn(q);
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.get()).thenReturn(pending.getTask());
   HomeFragment h=home();invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},Collections.singletonList("alice"));
   invoke(h,"invalidateHomeReads",new Class<?>[]{});field("homeStarted").setBoolean(h,false);
   pending.setResult(new QuerySnapshot(Collections.singletonList(friend("actor","alice")),null,new SnapshotMetadata(false,false)));
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertFalse(((Set<?>)field("friendIds").get(h)).contains("alice"));
  }
 }
 @Test public void saturatedPageNeverTreatsMissingCandidateAsNonFriend()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference friends=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("friends")).thenReturn(friends);
   when(friends.whereIn(eq("participants"),anyList())).thenReturn(q);when(q.limit(10)).thenReturn(q);
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.get()).thenReturn(pending.getTask());
   HomeFragment h=home();List<String> ids=Arrays.asList("alice","bob");invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);
   pending.setResult(new QuerySnapshot(Collections.nCopies(10,friend("actor","alice")),null,new SnapshotMetadata(false,false)));
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertTrue(((Map<?,?>)field("friendFactsChecked").get(h)).containsKey("alice"));assertFalse(((Map<?,?>)field("friendFactsChecked").get(h)).containsKey("bob"));
   assertTrue(((Set<?>)field("friendFactsFailed").get(h)).contains("bob"));
   invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);verify(q,times(1)).get();
  }
 }
 @Test public void timeoutBlocksRetryAndIgnoresLateResultUntilNewEntry()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference friends=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("friends")).thenReturn(friends);
   when(friends.whereIn(eq("participants"),anyList())).thenReturn(q);when(q.limit(10)).thenReturn(q);
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.get()).thenReturn(pending.getTask());
   HomeFragment h=home();List<String> ids=Collections.singletonList("alice");invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21));
   assertTrue(((Set<?>)field("friendFactsFailed").get(h)).contains("alice"));
   for(int i=0;i<100;i++)invoke(h,"resolveVisibleFriends",new Class<?>[]{Collection.class},ids);verify(q,times(1)).get();
   pending.setResult(new QuerySnapshot(Collections.singletonList(friend("actor","alice")),null,new SnapshotMetadata(false,false)));
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertFalse(((Set<?>)field("friendIds").get(h)).contains("alice"));
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

}
