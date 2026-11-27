package com.friendbook.social.core;
import android.os.Looper;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class FeedSafetyDemandTest {
 @Test public void hundredFlaggedCachePaintsDoNotResubmitModerationWritesEvenOnFailure(){
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("feed-safety-actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference posts=mock(CollectionReference.class),reports=mock(CollectionReference.class);
   DocumentReference post=mock(DocumentReference.class);when(db.collection("posts")).thenReturn(posts);when(posts.document("flagged")).thenReturn(post);when(db.collection("reports")).thenReturn(reports);
   when(post.set(anyMap(),any(SetOptions.class))).thenReturn(Tasks.forException(new java.io.IOException("denied/offline")));
   when(post.delete()).thenReturn(Tasks.forException(new java.io.IOException("denied/offline")));
   when(reports.add(anyMap())).thenReturn(Tasks.forResult(mock(DocumentReference.class)));
   AtomicInteger localUpdates=new AtomicInteger();
   for(int paint=0;paint<100;paint++)ContentSafetyEngine.autoDeleteContent(null,"posts","flagged","author","policy_violation","flag",localUpdates::incrementAndGet);
   Shadows.shadowOf(Looper.getMainLooper()).idle();
   verify(post,times(1)).set(anyMap(),any(SetOptions.class));verify(post,times(1)).delete();verify(reports,times(1)).add(anyMap());assertEquals(100,localUpdates.get());
  }
 }
 @Test public void oldActorFlagCompletionCannotDeleteAsNewActor(){
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("safety-old");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
   CollectionReference posts=mock(CollectionReference.class),reports=mock(CollectionReference.class);DocumentReference post=mock(DocumentReference.class);
   when(db.collection("posts")).thenReturn(posts);when(posts.document("switch")).thenReturn(post);when(db.collection("reports")).thenReturn(reports);
   TaskCompletionSource<Void> pending=new TaskCompletionSource<>();when(post.set(anyMap(),any(SetOptions.class))).thenReturn(pending.getTask());when(reports.add(anyMap())).thenReturn(Tasks.forResult(mock(DocumentReference.class)));
   ContentSafetyEngine.autoDeleteContent(null,"posts","switch","author","policy_violation","flag",null);
   fb.when(Fb::uid).thenReturn("safety-new");pending.setResult(null);Shadows.shadowOf(Looper.getMainLooper()).idle();verify(post,never()).delete();
  }
 }
}
