package com.friendbook.social.post;

import android.os.Looper;
import com.friendbook.social.core.*;
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
public class CommentConflictLoopTest {
 private void drain(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
 private void advance(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms,TimeUnit.MILLISECONDS);}
 private void mutate(CommentsEngine.Handle handle)throws Exception{
  java.lang.reflect.Field revision=handle.getClass().getDeclaredField("revision");revision.setAccessible(true);revision.setLong(handle,revision.getLong(handle)+1);
 }
 @Test public void repeatedMutationConflictsStopAtThreeRequestsWithTwoSecondGaps() throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> client=mockStatic(D1Client.class)){
   fb.when(Fb::uid).thenReturn("conflict-actor");client.when(()->D1Client.enabled("posts")).thenReturn(true);
   List<TaskCompletionSource<JSONObject>> calls=new ArrayList<>();
   client.when(()->D1Client.request(eq("posts"),eq("conflict-post"),eq("comments"),eq("GET"),isNull(),anyString()))
    .thenAnswer(c->{TaskCompletionSource<JSONObject> t=new TaskCompletionSource<>();calls.add(t);return t.getTask();});
   CommentsEngine.Handle h=CommentsEngine.watch("posts","conflict-post","conflict-post",(rows,pinned)->{});
   for(int attempt=0;attempt<3;attempt++){
    assertEquals(attempt+1,calls.size());mutate(h);calls.get(attempt).setResult(new JSONObject().put("comments",new JSONArray()));drain();
    advance(1999);assertEquals(attempt+1,calls.size());advance(1);
   }
   advance(60_000);assertEquals(3,calls.size());assertFalse(h.isLoading());assertTrue(h.error.contains("Tap to retry"));h.remove();
  }
 }
 @Test public void closedScreenCancelsScheduledConflictRead() throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<D1Client> client=mockStatic(D1Client.class)){
   fb.when(Fb::uid).thenReturn("closed-actor");client.when(()->D1Client.enabled("posts")).thenReturn(true);
   TaskCompletionSource<JSONObject> pending=new TaskCompletionSource<>();
   client.when(()->D1Client.request(eq("posts"),eq("closed-post"),eq("comments"),eq("GET"),isNull(),anyString())).thenReturn(pending.getTask());
   CommentsEngine.Handle h=CommentsEngine.watch("posts","closed-post","closed-post",(rows,pinned)->{});
   mutate(h);pending.setResult(new JSONObject().put("comments",new JSONArray()));drain();h.remove();advance(60_000);
   client.verify(()->D1Client.request(eq("posts"),eq("closed-post"),eq("comments"),eq("GET"),isNull(),anyString()),times(1));
  }
 }
}
