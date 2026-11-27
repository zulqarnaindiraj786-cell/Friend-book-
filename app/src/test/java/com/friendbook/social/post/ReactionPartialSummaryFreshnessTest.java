package com.friendbook.social.post;

import android.os.Looper;
import com.friendbook.social.core.D1Client;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class ReactionPartialSummaryFreshnessTest {
 private static JSONObject full(int comments, String type) throws Exception {
  JSONArray types=new JSONArray();
  if(type!=null)types.put(new JSONObject().put("type",type).put("count",1).put("mine",1));
  return new JSONObject().put("owner","owner").put("comments",comments).put("preview",new JSONArray()).put("types",types);
 }
 private static JSONArray types(String type)throws Exception{return full(0,type).getJSONArray("types");}
 private static Models.Post post(String id){Models.Post post=new Models.Post();post.id=id;return post;}
 @Test public void partialDetailTotalsDoNotMakeAnAbsentSummaryFresh()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Client> client=mockStatic(D1Client.class,call->"key".equals(call.getMethod().getName())?call.callRealMethod():RETURNS_DEFAULTS.answer(call))){
   fb.when(Fb::uid).thenReturn("partial-empty-actor");
   D1Reactions.acceptDetailTotals("partial-empty",types("like"),android.os.SystemClock.elapsedRealtime());
   client.when(()->D1Client.request("posts","partial-empty","reactions","GET",null,null)).thenReturn(Tasks.forResult(full(7,"like")));
   Models.Post post=post("partial-empty");
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   client.verify(()->D1Client.request("posts","partial-empty","reactions","GET",null,null),times(1));
   assertEquals(7,post.comments);assertEquals(1,post.likes);
  }
 }
 @Test public void orphanedTimestampCannotMakePartialSummaryFresh()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Client> client=mockStatic(D1Client.class,call->"key".equals(call.getMethod().getName())?call.callRealMethod():RETURNS_DEFAULTS.answer(call))){
   fb.when(Fb::uid).thenReturn("partial-evicted-actor");
   D1Reactions.cached("posts","partial-evicted"); // initialize account scope
   java.lang.reflect.Field field=D1Reactions.class.getDeclaredField("FETCHED_AT");field.setAccessible(true);
   android.util.LruCache<String,Long> ages=(android.util.LruCache<String,Long>)field.get(null);
   ages.put("posts/partial-evicted",android.os.SystemClock.elapsedRealtime());
   D1Reactions.acceptDetailTotals("partial-evicted",types("like"),android.os.SystemClock.elapsedRealtime());
   client.when(()->D1Client.request("posts","partial-evicted","reactions","GET",null,null)).thenReturn(Tasks.forResult(full(8,"like")));
   Models.Post post=post("partial-evicted");D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(8,post.comments);
   client.verify(()->D1Client.request("posts","partial-evicted","reactions","GET",null,null),times(1));
  }
 }
 @Test public void partialDetailTotalsPreserveExistingSummaryAge()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Client> client=mockStatic(D1Client.class,call->"key".equals(call.getMethod().getName())?call.callRealMethod():RETURNS_DEFAULTS.answer(call))){
   fb.when(Fb::uid).thenReturn("partial-age-actor");
   client.when(()->D1Client.request("posts","partial-age","reactions","GET",null,null)).thenReturn(Tasks.forResult(full(3,null)),Tasks.forResult(full(4,"love")));
   Models.Post post=post("partial-age");
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(110,TimeUnit.SECONDS);
   D1Reactions.acceptDetailTotals("partial-age",types("love"),android.os.SystemClock.elapsedRealtime());
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   client.verify(()->D1Client.request("posts","partial-age","reactions","GET",null,null),times(1));
   assertEquals(3,post.comments);assertEquals(1,post.likes);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(299,TimeUnit.SECONDS);
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   client.verify(()->D1Client.request("posts","partial-age","reactions","GET",null,null),times(1));assertEquals(3,post.comments);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS);
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   client.verify(()->D1Client.request("posts","partial-age","reactions","GET",null,null),times(2));assertEquals(4,post.comments);
  }
 }
 @Test public void olderInFlightSummaryCannotOverwritePartialDetailResponse()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Client> client=mockStatic(D1Client.class,call->"key".equals(call.getMethod().getName())?call.callRealMethod():RETURNS_DEFAULTS.answer(call))){
   fb.when(Fb::uid).thenReturn("partial-flight-actor");
   TaskCompletionSource<JSONObject> pending=new TaskCompletionSource<>();
   client.when(()->D1Client.request("posts","partial-flight","reactions","GET",null,null)).thenReturn(pending.getTask(),Tasks.forResult(full(5,"love")));
   Models.Post post=post("partial-flight");D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});
   D1Reactions.acceptDetailTotals("partial-flight",types("love"),android.os.SystemClock.elapsedRealtime());
   pending.setResult(full(2,"like"));Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertEquals("love",D1Reactions.mine("posts","partial-flight"));
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS);
   client.verify(()->D1Client.request("posts","partial-flight","reactions","GET",null,null),times(2));
   assertEquals(5,post.comments);assertEquals("love",D1Reactions.mine("posts","partial-flight"));
  }
 }
}
