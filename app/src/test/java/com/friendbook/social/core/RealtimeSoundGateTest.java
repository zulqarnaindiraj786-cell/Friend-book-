package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class RealtimeSoundGateTest {
    private static final long NOW=2_000_000_000_000L;
    @Test public void initialCacheAndServerHistoryAreSilent() {
        RealtimeSoundGate gate=new RealtimeSoundGate(NOW);
        assertFalse(gate.observe("cached",NOW,NOW,gate.begin(true))); gate.end(true);
        assertFalse(gate.observe("server-history",NOW,NOW,gate.begin(false))); gate.end(false);
        assertTrue(gate.observe("new",NOW+1000,NOW+1000,gate.begin(false)));
    }
    @Test public void duplicateReceiptAndReconnectNeverReplay() {
        RealtimeSoundGate gate=new RealtimeSoundGate(NOW); gate.end(false);
        assertTrue(gate.observe("message",NOW,NOW,gate.begin(false)));
        assertFalse(gate.observe("message",NOW,NOW,gate.begin(false)));
        assertFalse(gate.observe("message",NOW,NOW,gate.begin(true)));
    }
    @Test public void pendingNewEventsWaitForServerAndFailedWritesStaySilent() {
        RealtimeSoundGate gate=new RealtimeSoundGate(NOW); gate.end(false);
        assertFalse(gate.observe("pending",NOW,NOW,gate.begin(true)));
        assertTrue(gate.observe("pending",NOW,NOW,gate.begin(false)));
        assertFalse(gate.observe("failed-write",NOW,NOW,gate.begin(true)));
    }
    @Test public void oldMissingAndWildlyFutureEventsAreSilent() {
        RealtimeSoundGate gate=new RealtimeSoundGate(NOW); gate.end(false);
        assertFalse(gate.observe("old",NOW-60_000,NOW,gate.begin(false)));
        assertFalse(gate.observe("future",NOW+60_000,NOW,gate.begin(false)));
        assertFalse(gate.observe("unknown",0,NOW,gate.begin(false)));
        assertFalse(gate.observe("",NOW,NOW,gate.begin(false)));
    }
}
