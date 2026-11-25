package com.friendbook.social.live;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class LiveViewerEventsTest {
    @Test public void historicalJoinLeaveDoesNotWriteAgainOnSnapshotReplay() {
        Set<String> viewers = new HashSet<>(), packets = new HashSet<>();
        int writes = 0;
        for (int snapshot = 0; snapshot < 1000; snapshot++) {
            if (LiveViewerEvents.apply(viewers, "viewer", true, packets.add("join1"))) writes++;
            if (LiveViewerEvents.apply(viewers, "viewer", false, packets.add("leave1"))) writes++;
        }
        assertEquals(2, writes); // Ungated old code produces 2,000 changes.
        assertTrue(viewers.isEmpty());
        assertTrue(LiveViewerEvents.apply(viewers, "viewer", true, packets.add("join2")));
        assertTrue(viewers.contains("viewer"));
        assertFalse(LiveViewerEvents.apply(viewers, "viewer", true, packets.add("join2")));
        assertTrue(LiveViewerEvents.apply(viewers, "viewer", false, packets.add("leave2")));
    }

    @Test public void interleavedViewersReplayWithoutChangingCurrentMembership() {
        Set<String> viewers = new HashSet<>(), packets = new HashSet<>();
        for (int snapshot = 0; snapshot < 20; snapshot++) {
            LiveViewerEvents.apply(viewers, "alice", true, packets.add("a1"));
            LiveViewerEvents.apply(viewers, "bob", true, packets.add("b1"));
            LiveViewerEvents.apply(viewers, "alice", false, packets.add("a2"));
            assertEquals(Set.of("bob"), viewers);
        }
        assertTrue(LiveViewerEvents.apply(viewers, "alice", true, packets.add("a3")));
        assertEquals(Set.of("alice", "bob"), viewers);
    }

    @Test public void aSnapshotPublishesOnlyItsFinalChangedMembership() {
        Set<String> viewers = new HashSet<>(), packets = new HashSet<>();
        int writes = 0;
        for (int snapshot = 0; snapshot < 1000; snapshot++) {
            Set<String> before = new HashSet<>(viewers);
            for (int i = 0; i < 100; i++)
                LiveViewerEvents.apply(viewers, "viewer" + i, true, packets.add("join" + i));
            if (!before.equals(viewers)) writes++;
        }
        assertEquals(1, writes);
        assertEquals(100, viewers.size());
        Set<String> before = new HashSet<>(viewers);
        LiveViewerEvents.apply(viewers, "transient", true, packets.add("transient-join"));
        LiveViewerEvents.apply(viewers, "transient", false, packets.add("transient-leave"));
        assertEquals(before, viewers); // No intermediate membership needs persisting.
    }

    @Test public void duplicateStateAndInvalidActorsNeverProduceWrites() {
        Set<String> viewers = new HashSet<>();
        assertFalse(LiveViewerEvents.apply(viewers, null, true, true));
        assertFalse(LiveViewerEvents.apply(viewers, "", true, true));
        assertFalse(LiveViewerEvents.apply(viewers, "viewer", false, true));
        assertTrue(LiveViewerEvents.apply(viewers, "viewer", true, true));
        assertFalse(LiveViewerEvents.apply(viewers, "viewer", true, true));
        assertFalse(LiveViewerEvents.apply(viewers, "viewer", false, false));
        assertTrue(viewers.contains("viewer"));
    }
}
