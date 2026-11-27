package com.friendbook.social.core;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=android.app.Application.class)
public class D1ClientReactionBodyTest {
    @Test public void stripsClientOnlyNotifyHintWithoutMutatingCallerBody() throws Exception {
        JSONObject original = new JSONObject().put("type", "like").put("notify", true);
        JSONObject wireBody = D1Client.withoutNotify(original);

        assertEquals("like", wireBody.getString("type"));
        assertFalse(wireBody.has("notify"));
        assertTrue("request helper must not mutate the retry intent", original.getBoolean("notify"));
    }

    @Test public void preservesClearReactionTypeAndRemovesNotifyHint() throws Exception {
        JSONObject wireBody = D1Client.withoutNotify(new JSONObject().put("type", JSONObject.NULL).put("notify", false));

        assertTrue(wireBody.isNull("type"));
        assertFalse(wireBody.has("notify"));
    }
}
