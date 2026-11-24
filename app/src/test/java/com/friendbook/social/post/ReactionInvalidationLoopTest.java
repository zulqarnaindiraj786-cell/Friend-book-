package com.friendbook.social.post;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class ReactionInvalidationLoopTest {
 @Test public void invalidationDuringEveryResponseCannotCreateUnboundedImmediateRefetch() throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Client> client=mockStatic(D1Client.class,call->"key".equals(call.getMethod().getName())?call.callRealMethod():RETURNS_DEFAULTS.answer(call))){
   fb.when(Fb::uid).thenReturn("reaction-conflict-actor");List<TaskCompletionSource<JSONObject>> calls=new ArrayList<>();
   client.when(()->D1Client.request("posts","loop","reactions","GET",null,null)).thenAnswer(c->{TaskCompletionSource<JSONObject> t=new TaskCompletionSource<>();calls.add(t);return t.getTask();});
   Models.Post post=new Models.Post();post.id="loop";
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});
   for(int attempt=0;attempt<3;attempt++){
    assertEquals(attempt+1,calls.size());
    for(int change=0;change<100;change++)D1Reactions.invalidate("posts","loop");
    assertEquals(attempt+1,calls.size());
    calls.get(attempt).setResult(new JSONObject().put("types",new JSONArray()));
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(1999,TimeUnit.MILLISECONDS);assertEquals(attempt+1,calls.size());
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.MILLISECONDS);
   }
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(60,TimeUnit.SECONDS);assertEquals(3,calls.size());
  }
 }
 @Test public void stableRetryStillPublishesFreshCounters() throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Client> client=mockStatic(D1Client.class,call->"key".equals(call.getMethod().getName())?call.callRealMethod():RETURNS_DEFAULTS.answer(call))){
   fb.when(Fb::uid).thenReturn("reaction-recovery-actor");List<TaskCompletionSource<JSONObject>> calls=new ArrayList<>();
   client.when(()->D1Client.request("posts","recover","reactions","GET",null,null)).thenAnswer(c->{TaskCompletionSource<JSONObject> t=new TaskCompletionSource<>();calls.add(t);return t.getTask();});
   Models.Post post=new Models.Post();post.id="recover";
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});
   D1Reactions.invalidate("posts","recover");
   calls.get(0).setResult(new JSONObject().put("types",new JSONArray()).put("comments",999));
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertNotEquals(999,post.comments);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS);assertEquals(2,calls.size());
   calls.get(1).setResult(new JSONObject().put("types",new JSONArray()).put("comments",7));
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(7,post.comments);
   assertEquals(7,D1Reactions.cached("posts","recover").getInt("comments"));
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(60,TimeUnit.SECONDS);assertEquals(2,calls.size());
  }
 }
}
