package com.friendbook.social.d1.documents;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Same query body for listeners, cache keys, and one-shot reads. No provider calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=android.app.Application.class)
public class QuerySerializationTest {
    @Test public void listenerRetainsAllPaginationBounds() throws Exception {
        Query base = new Query(null, "posts").orderBy("createdAt", Query.Direction.DESCENDING).limit(20);
        Query bounded = base.startAfter(100L, "a").startAt(101L, "b")
                .endBefore(90L, "c").endAt(80L, "d");
        JSONObject request = D1DocumentStore.toQueryJson(bounded);
        assertEquals(bounded.toQueryBody().toString(), request.toString());
        assertEquals(100L, request.getJSONArray("startAfter").getLong(0));
        assertEquals("a", request.getJSONArray("startAfter").getString(1));
        assertEquals(101L, request.getJSONArray("startAt").getLong(0));
        assertEquals(90L, request.getJSONArray("endBefore").getLong(0));
        assertEquals(80L, request.getJSONArray("endAt").getLong(0));
        assertNotEquals(D1DocumentStore.toQueryJson(base).toString(), request.toString());
        assertNotEquals(D1DocumentStore.toQueryJson(base.startAfter(100L, "a")).toString(),
                D1DocumentStore.toQueryJson(base.startAfter(100L, "b")).toString());
    }
}
