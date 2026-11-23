package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Behavioural tests: populated/empty cache, explicit refresh, invalidation and eviction. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28, application=android.app.Application.class, manifest=org.robolectric.annotation.Config.NONE)
public class OnDemandQueryCacheTest {
    static class FakeQuery extends Query {
        boolean fresh=true, missing=false;
        int networkReads;
        long checkedMaxAge;
        final QuerySnapshot cached=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(true,false));
        final QuerySnapshot server=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false));
        FakeQuery(){super(null,"lives");}
        @Override public boolean hasFreshLocalPage(long age){checkedMaxAge=age;return fresh && age>0;}
        @Override public Task<QuerySnapshot> get(Source source){
            if(source==Source.CACHE) return missing?Tasks.forException(new Exception("evicted")):Tasks.forResult(cached);
            networkReads++;return Tasks.forResult(server);
        }
    }
    @Test public void emptyCacheIsAHitWithZeroNetworkReads() throws Exception {
        FakeQuery q=new FakeQuery();
        assertSame(q.cached,q.getCachedOrFetch(false).getResult());
        assertEquals(0,q.networkReads);
    }
    @Test public void explicitRefreshBypassesCache() throws Exception {
        FakeQuery q=new FakeQuery();
        assertSame(q.server,q.getCachedOrFetch(true).getResult());
        assertEquals(1,q.networkReads);
    }
    @Test public void invalidationAndEvictionFetchExactlyOnce() throws Exception {
        FakeQuery q=new FakeQuery();q.fresh=false;
        q.getCachedOrFetch(false).getResult();
        assertEquals(1,q.networkReads);
        q=new FakeQuery();q.missing=true;
        q.getCachedOrFetch(false).getResult();
        assertEquals(1,q.networkReads);
    }
    @Test public void shortSearchWindowIsRespectedAndRepeatedResultsNeedNoNetwork() {
        FakeQuery q=new FakeQuery();
        for(int edit=0;edit<100;edit++) assertSame(q.cached,q.getCachedOrFetch(false,300000L).getResult());
        assertEquals(300000L,q.checkedMaxAge);
        assertEquals(0,q.networkReads);
        q.fresh=false;
        assertSame(q.server,q.getCachedOrFetch(false,300000L).getResult());
        assertEquals(1,q.networkReads);
    }
    @Test public void zeroReuseWindowAndExplicitSearchRefreshFetch() {
        FakeQuery q=new FakeQuery();
        assertSame(q.server,q.getCachedOrFetch(false,0L).getResult());
        assertSame(q.server,q.getCachedOrFetch(true,300000L).getResult());
        assertEquals(2,q.networkReads);
    }

}
