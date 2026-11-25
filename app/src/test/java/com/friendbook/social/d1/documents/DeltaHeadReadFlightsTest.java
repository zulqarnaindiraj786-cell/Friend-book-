package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class DeltaHeadReadFlightsTest {
    private QuerySnapshot cached(Query query,String content,String summary) {
        Map<String,Object> data=new HashMap<>();data.put("createdAt",100L);data.put("content",content);
        if(summary!=null)data.put("__feedEngagement",summary);
        return new QuerySnapshot(Collections.singletonList(new QueryDocumentSnapshot(null,"posts/p","p",data,new SnapshotMetadata(true,false),1,2)),null,new SnapshotMetadata(true,false));
    }
    @Test public void serializedHeadPreservesEngagementAndTimesWithoutMutatingInput() throws Exception {
        Query query=new Query(null,"posts").withFeedEngagement();
        QuerySnapshot cache=cached(query,"body","{\"types\":[],\"comments\":3}");
        JSONObject row=query.cachedHeadRows(cache).getJSONObject(0);
        assertEquals("posts/p",row.getString("path"));
        assertEquals(1,row.getLong("createTime"));assertEquals(2,row.getLong("updateTime"));
        assertEquals(3,row.getJSONObject("engagement").getInt("comments"));
        assertFalse(row.getJSONObject("data").has("__feedEngagement"));
        assertNotNull(cache.getDocuments().get(0).getString("__feedEngagement"));
    }
    @Test public void sameCursorWithChangedCachedDataOrEngagementCannotJoin() throws Exception {
        Query head=new Query(null,"posts").withFeedEngagement().limit(20);
        Query delta=head.endBefore(100L,"p").limitToLast(20);
        String original=Query.deltaFlightKey("head",delta,head.cachedHeadRows(cached(head,"old","{\"comments\":1}")));
        assertNotEquals(original,Query.deltaFlightKey("head",delta,head.cachedHeadRows(cached(head,"new","{\"comments\":1}"))));
        assertNotEquals(original,Query.deltaFlightKey("head",delta,head.cachedHeadRows(cached(head,"old","{\"comments\":2}"))));
        assertNotEquals(original,Query.deltaFlightKey("other-head",delta,head.cachedHeadRows(cached(head,"old","{\"comments\":1}"))));
    }
    @Test public void hundredOverlappingExactDeltaMergesScheduleOneOperation() throws Exception {
        Query head=new Query(null,"posts").withFeedEngagement().limit(20);
        Query delta=head.endBefore(100L,"p").limitToLast(20);
        String key=Query.deltaFlightKey("head",delta,head.cachedHeadRows(cached(head,"body",null)));
        DocumentReadFlights<String> reads=new DocumentReadFlights<>();
        TaskCompletionSource<String> pending=new TaskCompletionSource<>();AtomicInteger scheduled=new AtomicInteger();
        Task<String> first=reads.read("actor",key,true,()->{scheduled.incrementAndGet();return pending.getTask();});
        for(int i=0;i<100;i++)assertSame(first,reads.read("actor",key,true,()->{scheduled.incrementAndGet();return Tasks.forResult("bad");}));
        assertEquals(1,scheduled.get());reads.invalidateAll();
        assertEquals("new",reads.read("actor",key,true,()->Tasks.forResult("new")).getResult());
        pending.setResult("old");
    }
}
