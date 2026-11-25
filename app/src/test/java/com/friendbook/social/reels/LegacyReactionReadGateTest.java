package com.friendbook.social.reels;

import static org.junit.Assert.*;
import org.junit.Test;

public class LegacyReactionReadGateTest {
    @Test public void overlappingBindsShareOneRead() {
        int started=0;
        for(int i=0;i<100;i++) if(LegacyReactionReadGate.begin("actor-overlap","post-overlap",1000L)) started++;
        assertEquals(1,started);
    }

    @Test public void accountAndDocumentScopesAreIndependent() {
        assertTrue(LegacyReactionReadGate.begin("actor-a","post-same",2000L));
        assertTrue(LegacyReactionReadGate.begin("actor-b","post-same",2000L));
        assertTrue(LegacyReactionReadGate.begin("actor-a","post-other",2000L));
    }

    @Test public void emptyLegacyResultHasShortRetryWindow() {
        assertTrue(LegacyReactionReadGate.begin("actor-empty","post-empty",3000L));
        LegacyReactionReadGate.success("actor-empty","post-empty",true,3000L);
        assertFalse(LegacyReactionReadGate.begin("actor-empty","post-empty",3001L));
        assertTrue(LegacyReactionReadGate.begin("actor-empty","post-empty",18000L));
    }

    @Test public void nonEmptySuccessAllowsFreshModelToReconcileAgain() {
        assertTrue(LegacyReactionReadGate.begin("actor-nonempty","post-nonempty",3500L));
        LegacyReactionReadGate.success("actor-nonempty","post-nonempty",false,3500L);
        assertTrue(LegacyReactionReadGate.begin("actor-nonempty","post-nonempty",3501L));
    }

    @Test public void failuresAreRetryableImmediately() {
        assertTrue(LegacyReactionReadGate.begin("actor-fail","post-fail",4000L));
        LegacyReactionReadGate.failure("actor-fail","post-fail");
        assertTrue(LegacyReactionReadGate.begin("actor-fail","post-fail",4001L));
    }

    @Test public void abandonedFlightCanRecoverAfterTimeout() {
        assertTrue(LegacyReactionReadGate.begin("actor-timeout","post-timeout",5000L));
        assertFalse(LegacyReactionReadGate.begin("actor-timeout","post-timeout",5001L));
        assertTrue(LegacyReactionReadGate.begin("actor-timeout","post-timeout",
                5000L+LegacyReactionReadGate.FLIGHT_TIMEOUT_MS));
    }
}
