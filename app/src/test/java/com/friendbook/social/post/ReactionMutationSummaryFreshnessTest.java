package com.friendbook.social.post;

import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class ReactionMutationSummaryFreshnessTest {
 private MockedStatic<D1Client> client(){return mockStatic(D1Client.class,call->{String n=call.getMethod().getName();return ("key".equals(n)||"object".equals(n))?call.callRealMethod():RETURNS_DEFAULTS.answer(call);});}
 private JSONObject full(int count)throws Exception{return new JSONObject().put("comments",count).put("preview",new JSONArray()).put("owner","").put("types",new JSONArray());}
 private JSONObject ack()throws Exception{return new JSONObject().put("reactionOnly",true).put("types",new JSONArray().put(new JSONObject().put("type","like").put("count",1).put("mine",1)));}
 private Models.Post post(String id){Models.Post p=new Models.Post();p.id=id;return p;}
 @Test public void reactionOnlyWriteDoesNotRenewTheOldComments()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);MockedStatic<D1Client> d=client()){
   fb.when(Fb::uid).thenReturn("mutation-age-actor");
   d.when(()->D1Client.request("posts","mutation-age","reactions","GET",null,null)).thenReturn(Tasks.forResult(full(3)),Tasks.forResult(full(4)));
   d.when(()->D1Client.requestInteractive(eq("posts"),eq("mutation-age"),eq("reactions"),eq("PUT"),any(JSONObject.class))).thenReturn(Tasks.forResult(ack()));
   Models.Post p=post("mutation-age");D1Reactions.loadPost(RuntimeEnvironment.getApplication(),p,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(110,TimeUnit.SECONDS);
   D1Reactions.tapLike(RuntimeEnvironment.getApplication(),"posts","mutation-age",()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),p,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   d.verify(()->D1Client.request("posts","mutation-age","reactions","GET",null,null),times(1));assertEquals(3,p.comments);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(299,TimeUnit.SECONDS);
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),p,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   d.verify(()->D1Client.request("posts","mutation-age","reactions","GET",null,null),times(1));assertEquals(3,p.comments);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS);
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),p,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   d.verify(()->D1Client.request("posts","mutation-age","reactions","GET",null,null),times(2));assertEquals(4,p.comments);
  }
 }
 @Test public void staleInitialReadCannotOverwriteReactionWriteAndInvalidationDoesNotForceCommentScan()throws Exception{
  for(boolean invalidate:new boolean[]{false,true})try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);MockedStatic<D1Client> d=client()){
   fb.when(Fb::uid).thenReturn("mutation-read-actor-"+invalidate);String id="mutation-read-"+invalidate;
   TaskCompletionSource<JSONObject> pending=new TaskCompletionSource<>();
   d.when(()->D1Client.requestInteractive("posts",id,"reactions","GET",null)).thenReturn(pending.getTask());
   d.when(()->D1Client.requestInteractive(eq("posts"),eq(id),eq("reactions"),eq("PUT"),any(JSONObject.class))).thenReturn(Tasks.forResult(ack()));
   d.when(()->D1Client.request("posts",id,"reactions","GET",null,null)).thenReturn(Tasks.forResult(full(9)));
   D1Reactions.tapLike(RuntimeEnvironment.getApplication(),"posts",id,()->{});
   if(invalidate)D1Reactions.invalidate("posts",id);
   pending.setResult(full(3));Shadows.shadowOf(Looper.getMainLooper()).idle();
   Models.Post p=post(id);D1Reactions.loadPost(RuntimeEnvironment.getApplication(),p,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   d.verify(()->D1Client.request("posts",id,"reactions","GET",null,null),never());
   assertEquals(3,p.comments);
   assertEquals("like",D1Reactions.mine("posts",id));
  }
 }
}
