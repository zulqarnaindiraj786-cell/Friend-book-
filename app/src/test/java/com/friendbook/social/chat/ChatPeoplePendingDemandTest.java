package com.friendbook.social.chat;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.*;
import java.time.Duration;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class ChatPeoplePendingDemandTest {
 @Test public void initialPeopleCacheTimeoutUnlocksWithoutNetworkRetry()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("pending-people-actor");Query q=mock(Query.class);when(q.limit(10)).thenReturn(q);
   TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();when(q.get(Source.CACHE)).thenReturn(pending.getTask());
   int[] errors={0};CursorPager pager=new CursorPager(q,10,"chat-people:test",(docs,more,busy,error)->{if(!error.isEmpty())errors[0]++;});
   for(int i=0;i<100;i++)pager.startOnDemand();assertTrue(pager.isLoading());verify(q,times(1)).get(Source.CACHE);
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));assertFalse(pager.isLoading());assertEquals(1,errors[0]);
   pending.setException(new java.io.IOException("late"));Shadows.shadowOf(Looper.getMainLooper()).idle();
   verify(q,never()).get(Source.DEFAULT);verify(q,never()).get(Source.SERVER);pager.close();
  }
 }
}
