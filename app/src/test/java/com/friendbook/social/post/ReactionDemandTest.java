package com.friendbook.social.post;

import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.*;
import java.util.*;
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
public class ReactionDemandTest {
    @Test public void actualTapPathIgnoresDoubleTapsForOneSecondThenAllowsNextAction() throws Exception {
        for(int taps:new int[]{100,101}) {
            try(MockedStatic<Fb> fb=mockStatic(Fb.class);
                MockedStatic<FastCache> cache=mockStatic(FastCache.class);
                MockedStatic<Notify> notify=mockStatic(Notify.class);
                MockedStatic<D1Client> client=mockStatic(D1Client.class,call->{
                    String name=call.getMethod().getName();
                    return ("key".equals(name)||"object".equals(name)||"maps".equals(name)||"map".equals(name))
                        ?call.callRealMethod():RETURNS_DEFAULTS.answer(call);
                })) {
                fb.when(Fb::uid).thenReturn("demand-actor-"+taps);
                JSONObject initial=new JSONObject("{\"owner\":\"owner\",\"types\":[],\"comments\":0,\"preview\":[]}");
                client.when(()->D1Client.request("posts","demand","reactions","GET",null,null))
                    .thenReturn(Tasks.forResult(initial));
                List<String> targets=new ArrayList<>();TaskCompletionSource<JSONObject> first=new TaskCompletionSource<>();
                client.when(()->D1Client.requestInteractive(eq("posts"),eq("demand"),eq("reactions"),eq("PUT"),any(JSONObject.class)))
                    .thenAnswer(call->{
                        JSONObject body=call.getArgument(4);targets.add(body.isNull("type")?null:body.getString("type"));
                        if(targets.size()==1)return first.getTask();
                        return Tasks.forResult(new JSONObject("{\"owner\":\"owner\",\"reactionOnly\":true,\"types\":[]}"));
                    });
                Models.Post post=new Models.Post();post.id="demand";
                android.content.Context context=RuntimeEnvironment.getApplication();
                D1Reactions.loadPost(context,post,()->{});Shadows.shadowOf(Looper.getMainLooper()).idle();
                client.clearInvocations();
                for(int i=0;i<taps;i++)D1Reactions.tapLike(context,"posts","demand",()->{});
                assertEquals(Collections.singletonList("like"),targets);
                first.setResult(new JSONObject("{\"owner\":\"owner\",\"reactionOnly\":true,\"types\":[{\"type\":\"like\",\"count\":1,\"mine\":1}]}"));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(Collections.singletonList("like"),targets);
                assertEquals("like",D1Reactions.mine("posts","demand"));
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,java.util.concurrent.TimeUnit.SECONDS);
                D1Reactions.tapLike(context,"posts","demand",()->{});
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(Arrays.asList("like",null),targets);
                assertNull(D1Reactions.mine("posts","demand"));
                client.verify(()->D1Client.request("posts","demand","reactions","GET",null,null),never());
            }
        }
    }
}
