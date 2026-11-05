package com.friendbook.social.post;
import com.friendbook.social.BuildConfig;
import com.friendbook.social.core.D1Client;
import com.friendbook.social.models.Models;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class D1AndroidTest {
 @Test public void releaseGateIsOffAndDisabledTransportDoesNotInitializeFirebase(){
  assertTrue(BuildConfig.D1_INTERACTIONS_ENABLED);assertTrue(D1Client.enabled("posts"));assertTrue(D1Client.enabled("reels"));assertFalse(D1Client.enabled("unknown"));
  com.google.android.gms.tasks.Task<JSONObject> task=D1Client.request("unknown","p1","comments","POST",D1Client.object("id","one"),null);
  assertTrue(task.isComplete());assertFalse(task.isSuccessful());assertNotNull(task.getException());
 }
 @Test public void dtoPreservesReplyMentionMediaAndActorReaction()throws Exception{
  JSONObject row=D1Client.object("id","c1","authorId","u1","content","hello @friend","createdAt",1234L,"parentId","parent1",
   "imageUrl","https://res.cloudinary.com/demo/image/upload/x.jpg","likes",4,"reactions",D1Client.object("me","love"),
   "mentions",new JSONArray().put(D1Client.object("uid","friend","label","@friend","start",6,"end",13)),"edited",true);
  Models.Comment c=Models.Comment.fromMap("c1",D1Client.map(row));
  assertEquals("u1",c.authorId);assertEquals("parent1",c.parentId);assertEquals(1234L,c.createdAt);assertTrue(c.edited);
  assertEquals(4,c.likes);assertEquals("love",c.reactions.get("me"));assertEquals(1,c.mentions.size());
  assertTrue(c.imageUrl.startsWith("https://res.cloudinary.com/"));
 }
 @Test public void summaryUsesServerCountsAndOnlyOwnReaction()throws Exception{
  JSONObject data=D1Client.object("types",new JSONArray().put(D1Client.object("type","like","count",7,"mine",0))
   .put(D1Client.object("type","love","count",2,"mine",1)));
  assertEquals(9,D1Reactions.total(data));assertEquals("love",D1Reactions.mine(data));
  assertNull(D1Reactions.mine(D1Client.object("types",new JSONArray())));
 }
 @Test public void removingLifecycleObserverStopsLaterCallbacks(){
  int[] events={0};Runnable close=D1Client.observe("posts/p1",()->events[0]++);
  D1Client.changed("posts/p1");assertEquals(1,events[0]);close.run();D1Client.changed("posts/p1");assertEquals(1,events[0]);
 }
 @Test public void unsentDraftsPreserveMediaAndIdAcrossRetriesAndSeparateAccounts(){
  android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
  JSONObject body=D1Client.object("id","stable-c1","text","draft","mediaUrl","https://res.cloudinary.com/demo/image/upload/x.jpg");
  assertTrue(D1Drafts.keep(context,"owner","posts","p1",body));
  assertEquals(1,D1Drafts.list(context,"owner","posts","p1").size());
  assertTrue(D1Drafts.list(context,"different","posts","p1").isEmpty());
  assertEquals("stable-c1",D1Drafts.list(context,"owner","posts","p1").get(0).optString("id"));
  assertTrue(D1Drafts.list(context,"owner","posts","p1").get(0).optString("mediaUrl").startsWith("https://res.cloudinary.com/"));
  D1Drafts.ack(context,"owner","posts","p1","stable-c1");assertTrue(D1Drafts.list(context,"owner","posts","p1").isEmpty());
 }
}
