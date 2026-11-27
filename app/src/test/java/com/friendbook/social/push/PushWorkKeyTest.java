package com.friendbook.social.push;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class PushWorkKeyTest {
    private JSONObject message(String chat,String id,String target)throws Exception {
        return new JSONObject().put("action","send").put("target_user_id",target).put("title","preview")
            .put("data",new JSONObject().put("type","message").put("chatId",chat).put("messageId",id));
    }
    @Test public void samePersistedMessageIgnoresPreviewAndJsonFieldOrder()throws Exception {
        JSONObject first=message("chat","message","recipient");
        JSONObject second=new JSONObject().put("body","different preview").put("title","updated sender name")
            .put("data",new JSONObject().put("messageId","message").put("chatId","chat").put("type","message"))
            .put("target_user_id","recipient").put("action","send");
        assertEquals(PushWorkKey.of("actor",first),PushWorkKey.of("actor",second));
    }
    @Test public void identitySeparatesActorsRecipientsChatsAndMessages()throws Exception {
        String key=PushWorkKey.of("actor",message("chat","message","recipient"));
        assertNotEquals(key,PushWorkKey.of("other",message("chat","message","recipient")));
        assertNotEquals(key,PushWorkKey.of("actor",message("chat","message","other")));
        assertNotEquals(key,PushWorkKey.of("actor",message("other","message","recipient")));
        assertNotEquals(key,PushWorkKey.of("actor",message("chat","other","recipient")));
        assertNotEquals(PushWorkKey.of("a_b",message("c","d","e")),PushWorkKey.of("a",message("b_c","d","e")));
    }
    @Test public void notificationIdentityIsStableButDistinctFromMessageAndOtherEvents()throws Exception {
        JSONObject first=new JSONObject().put("action","send").put("target_user_id","recipient")
            .put("data",new JSONObject().put("notificationId","n1").put("type","like"));
        JSONObject second=new JSONObject(first.toString()).put("title","different");
        second.getJSONObject("data").put("type","comment");
        assertEquals(PushWorkKey.of("actor",first),PushWorkKey.of("actor",second));
        second.getJSONObject("data").put("notificationId","n2");
        assertNotEquals(PushWorkKey.of("actor",first),PushWorkKey.of("actor",second));
        assertNotEquals(PushWorkKey.of("actor",first),PushWorkKey.of("actor",message("chat","n1","recipient")));
    }
    @Test public void registrationAndUnknownActionsKeepPreviousSemantics()throws Exception {
        JSONObject register=new JSONObject().put("action","register");
        assertEquals("register_actor",PushWorkKey.of("actor",register));
        JSONObject unknown=new JSONObject().put("action","self_test").put("device_id","one");
        assertEquals("actor_"+unknown.toString(),PushWorkKey.of("actor",unknown));
        JSONObject malformed=message("bad/path","message","recipient");
        assertEquals("actor_"+malformed.toString(),PushWorkKey.of("actor",malformed));
    }
}
