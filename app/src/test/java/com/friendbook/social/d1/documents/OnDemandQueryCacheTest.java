package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Behavioural tests: populated/empty cache, explicit refresh, invalidation and eviction. */
public class OnDemandQueryCacheTest {
    static class FakeQuery extends Query {
        boolean fresh=true, missing=false;
        int networkReads;
        final QuerySnapshot cached=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(true,false));
        final QuerySnapshot server=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false));
        FakeQuery(){super(null,"lives");}
        @Override public boolean hasFreshLocalPage(long age){return fresh;}
        @Override public Task<QuerySnapshot> get(Source source){
            if(source==Source.CACHE) return missing?Tasks.forException(new Exception("evicted")):Tasks.forResult(cached);
            networkReads++;return Tasks.forResult(server);
        }
    }
    @Test public void emptyCacheIsAHitWithZeroNetworkReads() throws Exception {
        FakeQuery q=new FakeQuery();
        assertSame(q.cached,Tasks.await(q.getCachedOrFetch(false),2,TimeUnit.SECONDS));
        assertEquals(0,q.networkReads);
    }
    @Test public void explicitRefreshBypassesCache() throws Exception {
        FakeQuery q=new FakeQuery();
        assertSame(q.server,Tasks.await(q.getCachedOrFetch(true),2,TimeUnit.SECONDS));
        assertEquals(1,q.networkReads);
    }
    @Test public void invalidationAndEvictionFetchExactlyOnce() throws Exception {
        FakeQuery q=new FakeQuery();q.fresh=false;
        Tasks.await(q.getCachedOrFetch(false),2,TimeUnit.SECONDS);
        assertEquals(1,q.networkReads);
        q=new FakeQuery();q.missing=true;
        Tasks.await(q.getCachedOrFetch(false),2,TimeUnit.SECONDS);
        assertEquals(1,q.networkReads);
    }
}
