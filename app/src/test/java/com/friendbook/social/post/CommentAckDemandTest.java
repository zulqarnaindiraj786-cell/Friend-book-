package com.friendbook.social.post;

import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.Tasks;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class CommentAckDemandTest {
 @Test public void actualCommentEditReactionAndPinReuseAckWithoutSummaryGetButRemoveStillRefreshes() throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);
      MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<D1Client> client=mockStatic(D1Client.class,call->{
       String name=call.getMethod().getName();
       if("enabled".equals(name))return true;
       return ("key".equals(name)||"object".equals(name)||"maps".equals(name)||"map".equals(name))?call.callRealMethod():RETURNS_DEFAULTS.answer(call);
      })){
   fb.when(Fb::uid).thenReturn("comment-ack-actor");
   JSONObject row=new JSONObject("{\"id\":\"c1\",\"authorId\":\"comment-ack-actor\",\"content\":\"before\",\"createdAt\":1000}");
   JSONObject summary=new JSONObject().put("owner","owner").put("comments",1)
       .put("types",new JSONArray("[{\"type\":\"like\",\"count\":3,\"mine\":1}]"))
       .put("preview",new JSONArray().put(row));
   client.when(()->D1Client.request("posts","ack-post","reactions","GET",null,null)).thenReturn(Tasks.forResult(summary));
   client.when(()->D1Client.request(eq("posts"),eq("ack-post"),eq("comments"),eq("PUT"),any(JSONObject.class),isNull()))
       .thenAnswer(call->{JSONObject body=call.getArgument(4);String op=body.getString("op");
        if("pin".equals(op))return Tasks.forResult(new JSONObject().put("pinned","c1"));
        if("remove".equals(op))return Tasks.forResult(new JSONObject().put("removed","c1"));
        return Tasks.forResult(new JSONObject().put("comment",new JSONObject(row.toString()).put("content","after").put("likes",2)));
       });
   Models.Post post=new Models.Post();post.id="ack-post";
   D1Reactions.loadPost(RuntimeEnvironment.getApplication(),post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
   java.lang.reflect.Field field=D1Reactions.class.getDeclaredField("FETCHED_AT");field.setAccessible(true);
   android.util.LruCache<String,Long> ages=(android.util.LruCache<String,Long>)field.get(null);
   Long originalAge=ages.get("posts/ack-post");assertNotNull(originalAge);
   client.clearInvocations();
   Models.Comment comment=new Models.Comment();comment.id="c1";comment.authorId="comment-ack-actor";
   for(int i=0;i<30;i++){
    CommentsEngine.editComment("posts","ack-post",comment,"after");
    CommentsEngine.updateCommentReaction("posts","ack-post",comment,"comment-ack-actor","love");
    CommentsEngine.pinComment("posts","ack-post","c1",true);
   }
   Shadows.shadowOf(Looper.getMainLooper()).idle();
   client.verify(()->D1Client.request("posts","ack-post","reactions","GET",null,null),never());
   assertEquals("after",post.inlineComments.get(0).content);assertEquals(1,post.comments);assertEquals(3,post.likes);
   assertEquals(originalAge,ages.get("posts/ack-post"));
   CommentsEngine.deleteComment("posts","ack-post",comment);Shadows.shadowOf(Looper.getMainLooper()).idle();
   client.verify(()->D1Client.request("posts","ack-post","reactions","GET",null,null),times(1));
   fb.verify(Fb::db,never());
  }
 }
}
