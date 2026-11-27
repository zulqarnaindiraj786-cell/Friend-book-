package com.friendbook.social.post;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class ReactionSummaryReuseTest {
 @Test public void repeatBindingsReuseFiveMinuteSummaryButMutationInvalidatesImmediately() throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Client> client=mockStatic(D1Client.class,call->"key".equals(call.getMethod().getName())?call.callRealMethod():RETURNS_DEFAULTS.answer(call))){
   fb.when(Fb::uid).thenReturn("summary-reuse-actor");AtomicInteger calls=new AtomicInteger();
   client.when(()->D1Client.request("posts","reuse","reactions","GET",null,null)).thenAnswer(c->{calls.incrementAndGet();return Tasks.forResult(new JSONObject().put("types",new JSONArray()).put("comments",3));});
   Models.Post post=new Models.Post();post.id="reuse";
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,calls.get());
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(9,TimeUnit.SECONDS);
   for(int bind=0;bind<100;bind++)D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,calls.get());
   D1Reactions.invalidate("posts","reuse");Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,calls.get());
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(299,TimeUnit.SECONDS);
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,calls.get());
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS);
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(3,calls.get());
  }
 }
 @Test public void reactionOnlyResponsePreservesDocumentCommentStateAndCachesForFiveMinutes() throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Client> client=mockStatic(D1Client.class,call->"key".equals(call.getMethod().getName())?call.callRealMethod():RETURNS_DEFAULTS.answer(call))){
   fb.when(Fb::uid).thenReturn("reaction-only-actor");AtomicInteger calls=new AtomicInteger();
   client.when(()->D1Client.request("posts","reaction-only","reactions","GET",null,null)).thenAnswer(c->{
    calls.incrementAndGet();return Tasks.forResult(new JSONObject().put("reactionOnly",true).put("types",new JSONArray()
      .put(new JSONObject().put("type","love").put("count",7).put("mine",1))));
   });
   Models.Post post=new Models.Post();post.id="reaction-only";post.comments=19;
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertEquals(19,post.comments);assertEquals("love",D1Reactions.mine("posts","reaction-only"));assertEquals(1,calls.get());
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,calls.get());
   D1Reactions.invalidate("posts","reaction-only");Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,calls.get());
  }
 }
}
