package com.friendbook.social.post;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.*;
import java.io.IOException;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.Assert.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class D1RoutingTest {
 private static JSONObject comment(){return D1Client.object("id","c1","authorId","u1","authorName","User","content","hello","createdAt",1000,"parentId","");}
 @Test public void commentsRouteAllActionsToD1WithoutTouchingFirestore()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> d1=mockStatic(D1Client.class,CALLS_REAL_METHODS);MockedStatic<D1Drafts> drafts=mockStatic(D1Drafts.class)){
   drafts.when(()->D1Drafts.keep(nullable(android.content.Context.class),anyString(),anyString(),anyString(),any(JSONObject.class))).thenReturn(true);
   fb.when(Fb::uid).thenReturn("u1");fb.when(Fb::myName).thenReturn("User");fb.when(Fb::myPhoto).thenReturn("");
   d1.when(()->D1Client.enabled("posts")).thenReturn(true);
   List<String> methods=new ArrayList<>();List<JSONObject> bodies=new ArrayList<>();
   d1.when(()->D1Client.request(eq("posts"),eq("p1"),eq("comments"),anyString(),nullable(JSONObject.class),nullable(String.class))).thenAnswer(inv->{
    String method=inv.getArgument(3);methods.add(method);JSONObject body=inv.getArgument(4);if(body!=null)bodies.add(body);
    return Tasks.forResult("GET".equals(method)?D1Client.object("comments",new JSONArray().put(comment()),"pinned","","next",null):D1Client.object("comment",comment()));
   });
   List<Models.Comment> visible=new ArrayList<>();CommentsEngine.Handle h=CommentsEngine.watch("posts","p1","p1",(all,pin)->{visible.clear();visible.addAll(all);});
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,visible.size());assertFalse(h.hasMore());
   Models.Comment c=visible.get(0);CommentsEngine.sendComment("posts","p1","p1","new","","",(ok,error)->assertTrue(ok));
   CommentsEngine.editComment("posts","p1",c,"changed");CommentsEngine.updateCommentReaction("posts","p1",c,"u1","love");
   CommentsEngine.pinComment("posts","p1","c1",true);CommentsEngine.deleteComment("posts","p1",c);
   Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertEquals(Arrays.asList("GET","POST","PUT","PUT","PUT","PUT"),methods);
   assertEquals("edit",bodies.get(1).getString("op"));assertEquals("react",bodies.get(2).getString("op"));
   assertEquals("pin",bodies.get(3).getString("op"));assertEquals("remove",bodies.get(4).getString("op"));
   h.remove();fb.verify(Fb::db,never());
  }
 }
 @Test public void failedD1SendDoesNotFallbackAndClosedOrChangedActorCannotRender(){
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> d1=mockStatic(D1Client.class,CALLS_REAL_METHODS);MockedStatic<D1Drafts> drafts=mockStatic(D1Drafts.class)){
   drafts.when(()->D1Drafts.keep(nullable(android.content.Context.class),anyString(),anyString(),anyString(),any(JSONObject.class))).thenReturn(true);
   fb.when(Fb::uid).thenReturn("u1");fb.when(Fb::myName).thenReturn("User");fb.when(Fb::myPhoto).thenReturn("");d1.when(()->D1Client.enabled("posts")).thenReturn(true);
   TaskCompletionSource<JSONObject> page=new TaskCompletionSource<>();
   d1.when(()->D1Client.request(eq("posts"),eq("p1"),eq("comments"),eq("GET"),isNull(),eq("?limit=10"))).thenReturn(page.getTask());
   d1.when(()->D1Client.request(eq("posts"),eq("p1"),eq("comments"),eq("POST"),any(JSONObject.class),isNull())).thenReturn(Tasks.forException(new IOException("offline")));
   int[] renders={0};CommentsEngine.Handle h=CommentsEngine.watch("posts","p1","p1",(all,pin)->renders[0]++);
   boolean[] failed={false};CommentsEngine.sendComment("posts","p1","p1","retry me","","",(ok,error)->failed[0]=!ok);
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(failed[0]);int before=renders[0];h.remove();
   fb.when(Fb::uid).thenReturn("different");page.setResult(D1Client.object("comments",new JSONArray().put(comment()),"next",null));
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(before,renders[0]);fb.verify(Fb::db,never());
  }
 }
}
