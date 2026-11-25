package com.friendbook.social.reels;

import static org.junit.Assert.*;
import org.junit.Test;

public class LegacyReactionReadGateTest {
    // JVM-only regression coverage; this quota change does not authorize release packaging.
    @Test public void overlappingBindsShareOneRead() {
        int started=0;
        for(int i=0;i<100;i++) if(LegacyReactionReadGate.begin("actor-overlap","post-overlap",1000L)!=null) started++;
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

    @Test public void nonEmptySuccessAllowsFreshModelToReconcileAgain() {
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

    @Test public void abandonedFlightCanRecoverAfterTimeout() {
        assertNotNull(LegacyReactionReadGate.begin("actor-timeout","post-timeout",5000L));
        assertNull(LegacyReactionReadGate.begin("actor-timeout","post-timeout",5001L));
        assertNotNull(LegacyReactionReadGate.begin("actor-timeout","post-timeout",
                5000L+LegacyReactionReadGate.FLIGHT_TIMEOUT_MS));
    }

    @Test public void lateCallbackCannotClearReplacementFlight() {
        Object old=LegacyReactionReadGate.begin("actor-late","post-late",6000L);
        Object replacement=LegacyReactionReadGate.begin("actor-late","post-late",
                6000L+LegacyReactionReadGate.FLIGHT_TIMEOUT_MS);
        assertNotNull(old);assertNotNull(replacement);assertNotSame(old,replacement);
        LegacyReactionReadGate.failure("actor-late","post-late",old);
        assertNull(LegacyReactionReadGate.begin("actor-late","post-late",
                6001L+LegacyReactionReadGate.FLIGHT_TIMEOUT_MS));
    }
}
