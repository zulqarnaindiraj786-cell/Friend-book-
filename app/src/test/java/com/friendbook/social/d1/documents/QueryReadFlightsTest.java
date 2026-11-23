package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class QueryReadFlightsTest {
    @Test public void identicalQueuedPagesShareAnOperationBeforeAnyHttpBegins() {
        DocumentReadFlights<String> flights=new DocumentReadFlights<>();
        for(String collection:new String[]{"posts","users"}) {
            Query query=new Query(null,collection).limit(20);
            if(collection.equals("posts"))query=query.withFeedEngagement();
            String key=D1DocumentStore.queryCacheKey(query,"viewer");
            TaskCompletionSource<String> queued=new TaskCompletionSource<>();
            AtomicInteger scheduled=new AtomicInteger();
            Task<String> first=flights.read("viewer",key,true,()->{scheduled.incrementAndGet();return queued.getTask();});
            for(int i=0;i<100;i++)assertSame(first,flights.read("viewer",key,true,()->{scheduled.incrementAndGet();return Tasks.forResult("duplicate");}));
            assertEquals(1,scheduled.get());
            assertEquals("fresh",flights.read("viewer",key,false,()->Tasks.forResult("fresh")).getResult());
            queued.setResult("page");
            assertEquals("next page read",flights.read("viewer",key,true,()->Tasks.forResult("next page read")).getResult());
        }
    }

    @Test public void exactKeysSeparateCollectionsAccountsFiltersLimitsCursorsAndEngagement() {
        Query base=new Query(null,"posts").orderBy("createdAt",Query.Direction.DESCENDING).limit(20);
        Query[] distinct={base,new Query(null,"users").limit(20),base.limit(10),base.startAfter(100L,"a"),
                base.startAfter(100L,"b"),base.withFeedEngagement(),base.whereEqualTo("authorId","author"),base.matchingPostTag("#tag")};
        Set<String> keys=new HashSet<>();
        for(Query query:distinct)assertTrue(keys.add(D1DocumentStore.queryCacheKey(query,"a")));
        assertTrue(keys.add(D1DocumentStore.queryCacheKey(base,"b")));
    }

    @Test public void pushOrMutationDetachesOldPagesAndLateCompletionCannotEvictNewFlight() {
        DocumentReadFlights<String> flights=new DocumentReadFlights<>();
        TaskCompletionSource<String> old=new TaskCompletionSource<>(), replacement=new TaskCompletionSource<>();
        flights.read("viewer","posts",true,old::getTask);
        flights.read("viewer","users",true,()->new TaskCompletionSource<String>().getTask());
        flights.invalidateAll();
        Task<String> current=flights.read("viewer","posts",true,replacement::getTask);
        assertEquals("new users",flights.read("viewer","users",true,()->Tasks.forResult("new users")).getResult());
        old.setResult("old page");
        assertSame(current,flights.read("viewer","posts",true,()->Tasks.forResult("bad")));
        replacement.setResult("new page");
        assertEquals("new page",current.getResult());
    }
}
