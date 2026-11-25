package com.friendbook.social.post;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReactionWriteQueueTest {
    @Test public void fiveQuickTapsCollapseUnsentStatesAndOtherPostDoesNotWait() {
        ReactionWriteQueue post=new ReactionWriteQueue();post.initialize(null);
        post.add("like",false,true);ReactionWriteQueue.Target first=post.begin();
        assertEquals("like",first.type);
        for(int i=0;i<4;i++)post.add("like",false,true);
        assertEquals("like",post.desired());assertNull(post.begin());
        ReactionWriteQueue other=new ReactionWriteQueue();other.initialize("like");
        other.add("like",false,true);assertNull(other.begin().type);
        post.complete(first);assertFalse(post.hasPending());assertNull(post.begin());
    }
    @Test public void finalUnlikeIsSentAfterActiveLikeNotLost() {
        ReactionWriteQueue post=new ReactionWriteQueue();post.initialize(null);
        post.add("like",false,true);ReactionWriteQueue.Target first=post.begin();
        for(int i=0;i<99;i++)post.add("like",false,true);
        assertNull(post.desired());post.complete(first);
        ReactionWriteQueue.Target last=post.begin();assertNotNull(last);assertNull(last.type);
        post.complete(last);assertFalse(post.hasPending());
    }
    @Test public void tapsBeforeInitialReadUseRealStartingStateWithoutIntermediateWrites() {
        ReactionWriteQueue post=new ReactionWriteQueue();
        for(int i=0;i<5;i++)post.add("like",false,true);
        assertNull(post.begin());post.initialize("love");assertNull(post.desired());
        ReactionWriteQueue.Target target=post.begin();assertNotNull(target);assertNull(target.type);
        post.complete(target);assertFalse(post.hasPending());
        ReactionWriteQueue unchanged=new ReactionWriteQueue();
        unchanged.add("like",false,true);unchanged.add("like",false,true);
        unchanged.initialize(null);assertNull(unchanged.begin());
    }
    @Test public void differentTypesKeepOnlyLatestTargetAndStaleAckDoesNotReleaseActiveWrite() {
        ReactionWriteQueue post=new ReactionWriteQueue();post.initialize(null);
        post.add("like",false,false);ReactionWriteQueue.Target first=post.begin();
        post.add("love",false,false);post.add("wow",false,false);
        assertEquals("wow",post.desired());post.complete(first);
        ReactionWriteQueue.Target next=post.begin();assertEquals("wow",next.type);
        post.complete(first);assertNull(post.begin());post.complete(next);assertFalse(post.hasPending());
        post.add("like",true,false);assertNull(post.begin());assertEquals("wow",post.desired());
    }
    @Test public void fiveThousandIntentBurstsMatchSequentialUserIntentWithAtMostTwoWrites() {
        java.util.Random random=new java.util.Random(9876);
        String[] types={null,"like","love","wow"};
        for(int sample=0;sample<5000;sample++){
            String server=types[random.nextInt(types.length)],expected=server;
            ReactionWriteQueue queue=new ReactionWriteQueue();queue.initialize(server);
            ReactionWriteQueue.Target active=null;int writes=0;
            for(int i=0,n=1+random.nextInt(100);i<n;i++){
                String requested=types[1+random.nextInt(3)];boolean onlyAdd=random.nextBoolean(),toggle=random.nextBoolean();
                if(!(onlyAdd&&expected!=null))expected=ReactionWriteQueue.nextReaction(expected,requested,toggle);
                queue.add(requested,onlyAdd,toggle);
                if(i==0){active=queue.begin();if(active!=null)writes++;}
            }
            if(active!=null){server=active.type;queue.complete(active);}
            ReactionWriteQueue.Target last=queue.begin();
            if(last!=null){writes++;server=last.type;queue.complete(last);}
            assertEquals(expected,queue.desired());assertEquals(expected,server);
            assertFalse(queue.hasPending());assertTrue(writes<=2);
        }
    }
}
