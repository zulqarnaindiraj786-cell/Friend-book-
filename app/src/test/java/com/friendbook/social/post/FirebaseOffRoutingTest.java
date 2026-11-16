package com.friendbook.social.post;
import android.os.Looper;
import com.friendbook.social.BuildConfig;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** OFF-mode behavioral regressions: real engine branches, mocked providers only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class FirebaseOffRoutingTest {
 private static void noD1(MockedStatic<D1Client> d1){
  d1.verify(()->D1Client.request(anyString(),anyString(),anyString(),anyString(),nullable(JSONObject.class),nullable(String.class)),never());
 }
 @Test public void authIdentityStillDelegatesToFirebase(){
  assertTrue("D1 routing is enabled in 2.8.92; Firebase stays the identity source", BuildConfig.D1_INTERACTIONS_ENABLED);
  try(MockedStatic<FirebaseAuth> factory=mockStatic(FirebaseAuth.class)){
   FirebaseAuth auth=mock(FirebaseAuth.class);FirebaseUser user=mock(FirebaseUser.class);
   factory.when(FirebaseAuth::getInstance).thenReturn(auth);when(auth.getCurrentUser()).thenReturn(user);when(user.getUid()).thenReturn("off-user");
   assertSame(auth,Fb.auth());assertEquals("off-user",Fb.uid());assertTrue(Fb.signedIn());
   when(auth.getCurrentUser()).thenReturn(null);assertNull(Fb.uid());assertFalse(Fb.signedIn());
  }
 }
 @Test public void offCommentSendCommitsCanonicalMirrorAndParentOnlyToFirestore(){
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> d1=mockStatic(D1Client.class,CALLS_REAL_METHODS)){
   d1.when(()->D1Client.enabled(anyString())).thenReturn(false);
   fb.when(Fb::uid).thenReturn("off-user");fb.when(Fb::myName).thenReturn("Owner");fb.when(Fb::myPhoto).thenReturn("");
   FirebaseFirestore db=mock(FirebaseFirestore.class);CollectionReference posts=mock(CollectionReference.class),comments=mock(CollectionReference.class),mirror=mock(CollectionReference.class);
   DocumentReference parent=mock(DocumentReference.class),canonical=mock(DocumentReference.class),copy=mock(DocumentReference.class);WriteBatch batch=mock(WriteBatch.class);
   fb.when(Fb::db).thenReturn(db);when(db.collection("posts")).thenReturn(posts);when(posts.document("p1")).thenReturn(parent);
   when(parent.collection("comments")).thenReturn(comments);when(comments.document("new-id")).thenReturn(canonical);
   when(db.collection("comments")).thenReturn(mirror);when(mirror.document()).thenReturn(copy);when(copy.getId()).thenReturn("new-id");when(mirror.document("new-id")).thenReturn(copy);
   when(db.batch()).thenReturn(batch);when(batch.commit()).thenReturn(Tasks.forResult(null));
   boolean[] ack={false};Models.Comment local=CommentsEngine.sendComment("posts","p1","p1","hello","","",(ok,error)->ack[0]=ok);
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertNotNull(local);assertEquals("new-id",local.id);assertTrue(ack[0]);
   verify(batch).set(eq(canonical),ArgumentMatchers.<Map<String,Object>>argThat(map->"hello".equals(map.get("content"))&&"off-user".equals(map.get("authorId"))));
   verify(batch).set(eq(copy),anyMap());verify(batch).update(eq(parent),ArgumentMatchers.<Map<String,Object>>argThat(map->map.containsKey("lastComment")&&map.containsKey("comments")));verify(batch).commit();noD1(d1);
  }
 }
 @Test public void offCommentWatchReadsFirebaseOnceAndStopsOnClose(){
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> d1=mockStatic(D1Client.class,CALLS_REAL_METHODS)){
   d1.when(()->D1Client.enabled(anyString())).thenReturn(false);
   // The comments router asks "is this collection routed to D1", not just "is D1 on".
   d1.when(()->D1Client.routed(anyString(),anyString())).thenReturn(false);
   fb.when(Fb::uid).thenReturn("off-user");FirebaseFirestore db=mock(FirebaseFirestore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference posts=mock(CollectionReference.class),comments=mock(CollectionReference.class);DocumentReference parent=mock(DocumentReference.class),child=mock(DocumentReference.class);
   Query query=mock(Query.class);DocumentSnapshot parentData=mock(DocumentSnapshot.class),doc=mock(DocumentSnapshot.class);QuerySnapshot page=mock(QuerySnapshot.class);SnapshotMetadata metadata=mock(SnapshotMetadata.class);
   when(db.collection("posts")).thenReturn(posts);when(posts.document("p1")).thenReturn(parent);when(parent.collection("comments")).thenReturn(comments);
   when(parent.get()).thenReturn(Tasks.forResult(parentData));when(parentData.exists()).thenReturn(false);
   when(comments.orderBy("createdAt",Query.Direction.DESCENDING)).thenReturn(query);when(query.limit(50)).thenReturn(query);
   // One read, not a subscription: the thread is fetched with get() and nothing stays attached.
   when(query.get()).thenReturn(Tasks.forResult(page));
   when(page.getDocuments()).thenReturn(Collections.singletonList(doc));when(page.size()).thenReturn(1);
   when(page.getMetadata()).thenReturn(metadata);when(metadata.isFromCache()).thenReturn(false);when(metadata.hasPendingWrites()).thenReturn(false);
   when(page.iterator()).thenAnswer(inv->Collections.singletonList(doc).iterator());
   when(doc.getId()).thenReturn("existing");when(doc.getReference()).thenReturn(child);when(child.getParent()).thenReturn(comments);when(comments.getParent()).thenReturn(parent);
   Map<String,Object> data=new HashMap<>();data.put("authorId","off-user");data.put("content","existing Firebase comment");data.put("createdAt",1000L);when(doc.getData()).thenReturn(data);
   List<Models.Comment> shown=new ArrayList<>();CommentsEngine.Handle handle=CommentsEngine.watch("posts","p1","p1",(all,pin)->{shown.clear();shown.addAll(all);});
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,shown.size());assertEquals("existing Firebase comment",shown.get(0).content);
   handle.remove();verify(query,never()).addSnapshotListener(any());
   noD1(d1);
  }
 }
 @Test public void offPostReactionWritesFirebaseNotWorker(){
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> d1=mockStatic(D1Client.class,CALLS_REAL_METHODS)){
   d1.when(()->D1Client.enabled(anyString())).thenReturn(false);
   fb.when(Fb::uid).thenReturn("off-owner");fb.when(Fb::myName).thenReturn("Owner");fb.when(Fb::myPhoto).thenReturn("");
   FirebaseFirestore db=mock(FirebaseFirestore.class);CollectionReference posts=mock(CollectionReference.class),reactions=mock(CollectionReference.class);
   DocumentReference parent=mock(DocumentReference.class),reaction=mock(DocumentReference.class);
   fb.when(Fb::db).thenReturn(db);when(db.collection("posts")).thenReturn(posts);when(posts.document("off-p2")).thenReturn(parent);
   when(parent.collection("reactions")).thenReturn(reactions);when(reactions.document("off-owner")).thenReturn(reaction);
   when(reaction.set(anyMap())).thenReturn(Tasks.forResult(null));when(parent.update(anyMap())).thenReturn(Tasks.forResult(null));
   Models.Post post=new Models.Post();post.id="off-p2";post.authorId="off-owner";
   Reactions.apply(RuntimeEnvironment.getApplication(),post,"like");Shadows.shadowOf(Looper.getMainLooper()).idle();
   verify(reaction).set(ArgumentMatchers.<Map<String,Object>>argThat(map->"like".equals(map.get("type"))&&"off-owner".equals(map.get("userId"))));
   verify(parent).update(anyMap());noD1(d1);
  }
 }
}
