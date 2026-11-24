package com.friendbook.social.post;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class CommentMutationDemandTest {
 private MockedStatic<D1Client> client(){return mockStatic(D1Client.class,c->{String n=c.getMethod().getName();if("enabled".equals(n))return true;return Arrays.asList("object","key","map","maps").contains(n)?c.callRealMethod():RETURNS_DEFAULTS.answer(c);});}
 @Test public void hundredPendingEditsUseOneWriteFailureUnlocksWithoutRead()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> d=client()){
   fb.when(Fb::uid).thenReturn("comment-burst-actor");TaskCompletionSource<JSONObject> pending=new TaskCompletionSource<>();
   d.when(()->D1Client.request(eq("posts"),eq("burst"),eq("comments"),eq("PUT"),any(),isNull())).thenReturn(pending.getTask());
   Models.Comment c=new Models.Comment();c.id="c1";
   for(int i=0;i<100;i++)CommentsEngine.editComment("posts","burst",c,"edited");
   d.verify(()->D1Client.request(eq("posts"),eq("burst"),eq("comments"),eq("PUT"),any(),isNull()),times(1));
   pending.setException(new RuntimeException("offline"));Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,java.util.concurrent.TimeUnit.SECONDS);
   CommentsEngine.editComment("posts","burst",c,"retry");
   d.verify(()->D1Client.request(eq("posts"),eq("burst"),eq("comments"),eq("PUT"),any(),isNull()),times(2));
   d.verify(()->D1Client.request(eq("posts"),eq("burst"),anyString(),eq("GET"),any(),any()),never());
  }
 }
 @Test public void failedReactionRunsRollbackAndChangedActorCannotRunIt()throws Exception{
  for(boolean switchActor:new boolean[]{false,true})try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> d=client()){
   String actor="comment-rollback-"+switchActor;fb.when(Fb::uid).thenReturn(actor);TaskCompletionSource<JSONObject> pending=new TaskCompletionSource<>();
   d.when(()->D1Client.request(eq("reels"),eq("rollback"),eq("comments"),eq("PUT"),any(),isNull())).thenReturn(pending.getTask());
   Models.Comment c=new Models.Comment();c.id="c1";AtomicInteger rollback=new AtomicInteger();
   CommentsEngine.updateCommentReaction("reels","rollback",c,actor,"love",rollback::incrementAndGet);
   if(switchActor)fb.when(Fb::uid).thenReturn("other-comment-account");
   pending.setException(new RuntimeException("offline"));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(switchActor?0:1,rollback.get());
  }
 }
 @Test public void pauseCancelsLatePageAndResumeFetchesOnlyWhenNoInitialPage()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> d=client()){
   fb.when(Fb::uid).thenReturn("comment-pause-actor");TaskCompletionSource<JSONObject> pending=new TaskCompletionSource<>();
   d.when(()->D1Client.request("reels","paused","comments","GET",null,"?limit=10")).thenReturn(pending.getTask());AtomicInteger renders=new AtomicInteger();
   CommentsEngine.Handle h=CommentsEngine.watch("reels","paused","reel_paused",(rows,pin)->renders.incrementAndGet());h.pause();int before=renders.get();
   for(int i=0;i<100;i++)h.loadMore();pending.setResult(new JSONObject().put("comments",new JSONArray()).put("next",JSONObject.NULL));Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertEquals(before,renders.get());assertFalse(h.hasInitialPage());h.resume();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(h.hasInitialPage());
   d.verify(()->D1Client.request("reels","paused","comments","GET",null,"?limit=10"),times(2));
   h.pause();h.resume();Shadows.shadowOf(Looper.getMainLooper()).idle();d.verify(()->D1Client.request("reels","paused","comments","GET",null,"?limit=10"),times(2));h.remove();
  }
 }
 @Test public void removeReleasesStaticHandleOwnership()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> d=client()){
   fb.when(Fb::uid).thenReturn("comment-dispose-actor");d.when(()->D1Client.request("posts","disposed","comments","GET",null,"?limit=10")).thenReturn(new TaskCompletionSource<JSONObject>().getTask());
   CommentsEngine.Handle h=CommentsEngine.watch("posts","disposed","disposed",(rows,pin)->{});
   java.lang.reflect.Field f=CommentsEngine.class.getDeclaredField("D1_HANDLES");f.setAccessible(true);assertTrue(((List<?>)f.get(null)).contains(h));h.remove();assertFalse(((List<?>)f.get(null)).contains(h));
  }
 }
}
