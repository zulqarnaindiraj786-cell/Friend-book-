package com.friendbook.social.d1.documents;

import org.junit.Test;
import static org.junit.Assert.*;

public class QueryCachePolicyTest {
    @Test public void inboxAndMessagesInvalidateInTheirActualNamespace() {
        assertEquals("d1_chat_query_cache_v2",QueryCachePolicy.preferencesFor("chats"));
        assertEquals("d1_chat_query_cache_v2",QueryCachePolicy.preferencesFor("chats/room/messages"));
        for(String parent:new String[]{"notifications","posts","lives","stories","friends","groups","chats-other"})
            assertEquals("d1_query_cache_v2",QueryCachePolicy.preferencesFor(parent));
    }
    @Test public void cacheHitAfterUnrelatedOpButNotAfterCollectionInvalidation() {
        assertTrue(QueryCachePolicy.isFresh(100,0,200,Long.MAX_VALUE));
        assertFalse(QueryCachePolicy.isFresh(100,100,200,Long.MAX_VALUE));
        assertFalse(QueryCachePolicy.isFresh(100,150,200,Long.MAX_VALUE));
        assertTrue(QueryCachePolicy.isFresh(160,150,200,Long.MAX_VALUE));
    }
    @Test public void missingFutureAndExpiredCacheNeverHideBehindCacheMode() {
        assertFalse(QueryCachePolicy.isFresh(0,0,200,Long.MAX_VALUE));
        assertFalse(QueryCachePolicy.isFresh(201,0,200,Long.MAX_VALUE));
        assertFalse(QueryCachePolicy.isFresh(100,0,200,100));
    }
}
