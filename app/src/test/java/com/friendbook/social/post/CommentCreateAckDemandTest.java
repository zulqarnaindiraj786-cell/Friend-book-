package com.friendbook.social.post;

import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class CommentCreateAckDemandTest {
 private static JSONObject row(String id,String author,long at)throws JSONException {
  return new JSONObject().put("id",id).put("authorId",author).put("content",id).put("createdAt",at);
 }
 private static JSONObject summary()throws JSONException {
  return new JSONObject().put("owner","owner").put("comments",1)
   .put("types",new JSONArray("[{\"type\":\"like\",\"count\":3,\"mine\":1}]"))
   .put("preview",new JSONArray().put(row("old","other",1000)));
 }
 private static JSONObject ack(String id,boolean created,boolean visible)throws JSONException {
  return new JSONObject().put("comment",row(id,"comment-create-actor",2000)).put("created",created).put("visibleToActor",visible);
 }
 @Test public void actualNewCommentUsesAckWithoutSummaryReadOrRenewingAge()throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Drafts> drafts=mockStatic(D1Drafts.class);MockedStatic<D1Client> client=mockStatic(D1Client.class,CALLS_REAL_METHODS)){
   fb.when(Fb::uid).thenReturn("comment-create-actor");fb.when(Fb::myName).thenReturn("Actor");fb.when(Fb::myPhoto).thenReturn("");
   client.when(()->D1Client.enabled("posts")).thenReturn(true);
   client.when(()->D1Client.request("posts","create-post","reactions","GET",null,null)).thenReturn(Tasks.forResult(summary()));
   drafts.when(()->D1Drafts.keep(nullable(android.content.Context.class),anyString(),anyString(),anyString(),any(JSONObject.class))).thenReturn(true);
   TaskCompletionSource<JSONObject> pending=new TaskCompletionSource<>();
   client.when(()->D1Client.request(eq("posts"),eq("create-post"),eq("comments"),eq("POST"),any(JSONObject.class),isNull())).thenReturn(pending.getTask());
   Models.Post post=new Models.Post();post.id="create-post";
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   java.lang.reflect.Field field=D1Reactions.class.getDeclaredField("FETCHED_AT");field.setAccessible(true);
   android.util.LruCache<String,Long> ages=(android.util.LruCache<String,Long>)field.get(null);Long originalAge=ages.get("posts/create-post");
   client.clearInvocations();boolean[] saved={false};
   Models.Comment local=CommentsEngine.sendComment("posts","create-post","create-post","hello","","",(ok,error)->saved[0]=ok);
   assertNotNull(local);pending.setResult(ack(local.id,true,true));Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertTrue(saved[0]);assertEquals(2,post.comments);assertEquals(3,post.likes);assertEquals(local.id,post.inlineComments.get(0).id);
   assertEquals(originalAge,ages.get("posts/create-post"));
   client.verify(()->D1Client.request("posts","create-post","reactions","GET",null,null),never());
   // A concurrent comment edit changes the snapshot while the POST is pending.
   // The same actual send path must reject that baseline and refresh once.
   TaskCompletionSource<JSONObject> next=new TaskCompletionSource<>();
   client.when(()->D1Client.request(eq("posts"),eq("create-post"),eq("comments"),eq("POST"),any(JSONObject.class),isNull())).thenReturn(next.getTask());
   client.clearInvocations();
   Models.Comment second=CommentsEngine.sendComment("posts","create-post","create-post","again","","",(ok,error)->{});
   D1Reactions.commentChanged("posts","create-post",new JSONObject().put("comment",row("old","other",1000)),"edit");
   next.setResult(ack(second.id,true,true));Shadows.shadowOf(Looper.getMainLooper()).idle();
   client.verify(()->D1Client.request("posts","create-post","reactions","GET",null,null),times(1));fb.verify(Fb::db,never());
  }
 }
 @Test public void duplicateInvisibleMalformedOrConcurrentAcksNeverIncrementTheBaseline()throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Client> client=mockStatic(D1Client.class,CALLS_REAL_METHODS)){
   fb.when(Fb::uid).thenReturn("comment-create-actor");
   client.when(()->D1Client.request("posts","baseline-post","reactions","GET",null,null)).thenReturn(Tasks.forResult(summary()));
   Models.Post post=new Models.Post();post.id="baseline-post";D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   D1Reactions.CommentBaseline before=D1Reactions.commentBaseline("posts","baseline-post");assertNotNull(before);
   assertFalse(D1Reactions.commentCreated("posts","baseline-post","new",ack("new",false,true),before));
   assertFalse(D1Reactions.commentCreated("posts","baseline-post","new",ack("new",true,false),before));
   assertFalse(D1Reactions.commentCreated("posts","baseline-post","new",new JSONObject().put("comment",row("new","comment-create-actor",2000)),before));
   assertFalse(D1Reactions.commentCreated("posts","baseline-post","new",ack("other",true,true),before));
   D1Reactions.commentChanged("posts","baseline-post",new JSONObject().put("comment",row("old","other",1000)),"edit");
   assertFalse(D1Reactions.commentCreated("posts","baseline-post","new",ack("new",true,true),before));
   assertEquals(1,post.comments);
   before=D1Reactions.commentBaseline("posts","baseline-post");
   fb.when(Fb::uid).thenReturn("different-actor");
   assertFalse(D1Reactions.commentCreated("posts","baseline-post","new",ack("new",true,true),before));
  }
 }
}
