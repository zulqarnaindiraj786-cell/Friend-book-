package com.friendbook.social.reels;

import static org.junit.Assert.*;
import org.junit.Test;

public class LegacyReactionReadGateTest {
    @Test public void overlappingBindsShareOneRead() {
        int started=0;
        for(int i=0;i<100;i++)
            if(LegacyReactionReadGate.begin("actor-overlap","post-overlap",1000L)!=null) started++;
        assertEquals(1,started);
    }

    @Test public void accountAndDocumentScopesAreIndependent() {
        assertNotNull(LegacyReactionReadGate.begin("actor-a","post-same",2000L));
        assertNotNull(LegacyReactionReadGate.begin("actor-b","post-same",2000L));
        assertNotNull(LegacyReactionReadGate.begin("actor-a","post-other",2000L));
    }

    @Test public void emptyLegacyResultHasShortRetryWindow() {
        Object token=LegacyReactionReadGate.begin("actor-empty","post-empty",3000L);
        assertNotNull(token);
        LegacyReactionReadGate.success("actor-empty","post-empty",token,true,3000L);
        assertNull(LegacyReactionReadGate.begin("actor-empty","post-empty",3001L));
        assertNotNull(LegacyReactionReadGate.begin("actor-empty","post-empty",18000L));
    }

    @Test public void nonEmptySuccessAllowsFreshReconciliation() {
        Object token=LegacyReactionReadGate.begin("actor-nonempty","post-nonempty",3500L);
        assertNotNull(token);
        LegacyReactionReadGate.success("actor-nonempty","post-nonempty",token,false,3500L);
        assertNotNull(LegacyReactionReadGate.begin("actor-nonempty","post-nonempty",3501L));
    }

    @Test public void failuresAreRetryableImmediately() {
        Object token=LegacyReactionReadGate.begin("actor-fail","post-fail",4000L);
        assertNotNull(token);
        LegacyReactionReadGate.failure("actor-fail","post-fail",token);
        assertNotNull(LegacyReactionReadGate.begin("actor-fail","post-fail",4001L));
    }

    @Test public void staleCallbackCannotFinishReplacementFlight() {
        Object old=LegacyReactionReadGate.begin("actor-timeout","post-timeout",0L);
        Object current=LegacyReactionReadGate.begin("actor-timeout","post-timeout",LegacyReactionReadGate.FLIGHT_TIMEOUT_MS);
        assertNotNull(current);
        LegacyReactionReadGate.success("actor-timeout","post-timeout",old,true,21000L);
        assertNull(LegacyReactionReadGate.begin("actor-timeout","post-timeout",21001L));
        LegacyReactionReadGate.failure("actor-timeout","post-timeout",current);
        assertNotNull(LegacyReactionReadGate.begin("actor-timeout","post-timeout",21002L));
    }

    @Test public void invalidActorOrDocumentCannotStartOrFinishReads() {
        assertNull(LegacyReactionReadGate.begin(null,"post",1L));
        assertNull(LegacyReactionReadGate.begin("actor","",1L));
        LegacyReactionReadGate.success(null,"post",new Object(),true,2L);
        LegacyReactionReadGate.failure("actor",null,new Object());
    }
}
