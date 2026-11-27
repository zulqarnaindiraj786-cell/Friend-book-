package com.friendbook.social.d1.documents;

import org.junit.Test;
import static org.junit.Assert.*;

public class PendingPushActorsTest {
    @Test public void burstCoalescesButLaterEventsAreNotLost() {
        PendingPushActors pending = new PendingPushActors();
        assertTrue(pending.begin("alice"));
        for (int i = 0; i < 50; i++) assertFalse(pending.begin("alice"));
        // Clear before running the refresh so a push during it gets a new slot.
        pending.finish("alice");
        assertTrue(pending.begin("alice"));
        assertFalse(pending.begin("alice"));
    }
    @Test public void concurrentEventsScheduleOnlyOneRefreshPerAccount() throws Exception {
        PendingPushActors pending = new PendingPushActors();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 24; i++) {
            Thread thread = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if (pending.begin("alice")) winners.incrementAndGet();
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertEquals(1, winners.get());
        pending.finish("alice");
        assertTrue(pending.begin("alice"));
    }
    @Test public void accountSwitchCannotSuppressAnotherAccountsPush() {
        PendingPushActors pending = new PendingPushActors();
        assertFalse(pending.begin(null));
        assertFalse(pending.begin(""));
        assertTrue(pending.begin("alice"));
        assertTrue(pending.begin("bob"));
        pending.finish("alice");
        assertFalse(pending.begin("bob"));
        pending.finish("bob");
        assertTrue(pending.begin("bob"));
    }
}
