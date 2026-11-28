package com.friendbook.social.push;

import android.content.Context;
import org.junit.Test;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class FanoutCheckpointTest {
 @Test public void recipientSelectionIsCappedAndStableAcrossRetries(){
  Context ctx=RuntimeEnvironment.getApplication();ctx.getSharedPreferences("friend_post_fanout_progress",0).edit().clear().commit();
  FanoutCheckpoint first=new FanoutCheckpoint(ctx,"actor","post-cap",10);
  for(int i=0;i<100;i++)assertTrue(first.select("friend"+i));
  assertTrue(first.select("friend0"));
  assertFalse(first.select("friend100"));
  FanoutCheckpoint retry=new FanoutCheckpoint(ctx,"actor","post-cap",20);
  for(int i=0;i<100;i++)assertTrue(retry.select("friend"+i));
  assertFalse(retry.select("friend100"));
  assertFalse(ctx.getSharedPreferences("friend_post_fanout_progress",0).getAll().toString().contains("friend0"));
  retry.clear();
 }
 @Test public void persistedProgressIsPrivateAccountScopedAndExpiresSafely(){
  Context ctx=RuntimeEnvironment.getApplication();ctx.getSharedPreferences("friend_post_fanout_progress",0).edit().clear().commit();
  long now=1_000_000_000L;FanoutCheckpoint first=new FanoutCheckpoint(ctx,"actor-secret","post-secret",now);
  first.markQueued("recipient-secret");assertTrue(first.completed("recipient-secret"));first.flush();
  FanoutCheckpoint resumed=new FanoutCheckpoint(ctx,"actor-secret","post-secret",now+1000);
  assertTrue(resumed.completed("recipient-secret"));assertFalse(resumed.completed("other"));
  assertFalse(new FanoutCheckpoint(ctx,"different-actor","post-secret",now+2000).completed("recipient-secret"));
  assertFalse(ctx.getSharedPreferences("friend_post_fanout_progress",0).getAll().toString().contains("recipient-secret"));
  new FanoutCheckpoint(ctx,"another","post",now+8L*24*60*60*1000);
  assertFalse(new FanoutCheckpoint(ctx,"actor-secret","post-secret",now+8L*24*60*60*1000).completed("recipient-secret"));
  resumed.clear();
 }
}
