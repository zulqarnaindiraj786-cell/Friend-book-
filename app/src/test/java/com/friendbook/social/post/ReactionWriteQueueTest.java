package com.friendbook.social.post;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReactionWriteQueueTest {
    @Test public void fiveQuickTapsStayOrderedAndOtherPostDoesNotWait() {
        ReactionWriteQueue post = new ReactionWriteQueue();
        post.initialize(null);
        post.add("like",false,true);
        ReactionWriteQueue.Target first = post.begin();
        assertEquals("like",first.type);
        for(int i=0;i<4;i++)post.add("like",false,true);
        assertEquals("like",post.desired());
        assertNull(post.begin());
        ReactionWriteQueue other = new ReactionWriteQueue();
        other.initialize("like");
        other.add("like",false,true);
        assertNull(other.begin().type);
        post.complete(first);
        for(String expected : new String[]{null,"like",null,"like"}) {
            ReactionWriteQueue.Target target=post.begin();
            assertEquals(expected,target.type);
            assertEquals("like",post.desired());
            post.complete(target);
        }
        assertFalse(post.hasPending());
        assertNull(post.begin());
    }

    @Test public void tapsBeforeInitialReadUseTheRealStartingState() {
        ReactionWriteQueue post = new ReactionWriteQueue();
        for(int i=0;i<5;i++)post.add("like",false,true);
        assertNull(post.begin());
        post.initialize("love");
        assertNull(post.desired());
        for(String expected : new String[]{null,"like",null,"like",null}) {
            ReactionWriteQueue.Target target=post.begin();
            assertEquals(expected,target.type);
            post.complete(target);
        }
        assertFalse(post.hasPending());
    }
}
