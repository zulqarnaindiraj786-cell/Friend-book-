package com.friendbook.social.post;
import android.app.Activity;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.d1.documents.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.*;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class FeedShareDemandTest {
 @Test public void repeatedPendingShareUsesOneAtomicBatchAndFailureDoesNotNotify()throws Exception{exercise(false,false);}
 @Test public void successfulShareUpdatesLocalCountAndNotifiesOnce()throws Exception{exercise(true,false);}
 @Test public void oldActorSuccessCannotNotifyAsNewActor()throws Exception{exercise(true,true);}
 private void exercise(boolean success,boolean switched)throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<Notify> notify=mockStatic(Notify.class);MockedStatic<Ui> ui=mockStatic(Ui.class);
      MockedStatic<AdsConfig> ads=mockStatic(AdsConfig.class);MockedStatic<ConsentManager> consent=mockStatic(ConsentManager.class);MockedStatic<InteractionCooldown> cooldown=mockStatic(InteractionCooldown.class)){
   fb.when(Fb::uid).thenReturn("share-demand-actor");cooldown.when(()->InteractionCooldown.acquire(anyString(),anyString())).thenReturn(true);
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);CollectionReference posts=mock(CollectionReference.class);when(db.collection("posts")).thenReturn(posts);
   when(posts.document()).thenReturn(mock(DocumentReference.class));when(posts.document("original")).thenReturn(mock(DocumentReference.class));
   WriteBatch batch=mock(WriteBatch.class);when(db.batch()).thenReturn(batch);TaskCompletionSource<Void> pending=new TaskCompletionSource<>();when(batch.commit()).thenReturn(pending.getTask());
   FeedAdapter adapter=new FeedAdapter(Robolectric.buildActivity(Activity.class).setup().get(),null);Models.Post post=new Models.Post();post.id="original";post.authorId="author";
   java.lang.reflect.Method submit=FeedAdapter.class.getDeclaredMethod("submitFeedShare",String.class,Models.Post.class,Map.class);submit.setAccessible(true);
   for(int i=0;i<100;i++)submit.invoke(adapter,"share-demand-actor",post,new HashMap<>());
   verify(batch,times(1)).commit();verify(batch).set(any(DocumentReference.class),anyMap());verify(batch).update(any(DocumentReference.class),eq("shares"),any(),any(Object[].class));
   if(switched)fb.when(Fb::uid).thenReturn("new-share-actor");
   if(success)pending.setResult(null);else pending.setException(new java.io.IOException("offline"));
   Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertEquals(success && !switched?1:0,post.shares);
   if(success && !switched)notify.verify(()->Notify.send("author","share","shared your post","original"),times(1));
   else notify.verifyNoInteractions();
   submit.invoke(adapter,"other-actor",post,new HashMap<>());verify(batch,times(1)).commit();
  }
 }
}
