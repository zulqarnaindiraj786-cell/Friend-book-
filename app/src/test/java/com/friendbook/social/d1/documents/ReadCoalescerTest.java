package com.friendbook.social.d1.documents;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ReadCoalescerTest {
    @Test public void sharesOnlyConcurrentMatchingKeysAndRemovesFailures() throws Exception {
        ReadCoalescer reads = new ReadCoalescer();
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        FutureTask<String> first = new FutureTask<>(() -> reads.read("alice/posts", () -> {
            running.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("wait expired");
            return "alice";
        }));
        new Thread(first).start();
        try {
            assertTrue(running.await(5, TimeUnit.SECONDS));
            FutureTask<String> second = new FutureTask<>(() -> reads.read("alice/posts", () -> "duplicate"));
            Thread waiter = new Thread(second);
            waiter.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (waiter.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield();
            assertEquals(Thread.State.WAITING, waiter.getState());
            assertEquals("bob", reads.read("bob/posts", () -> "bob"));
            release.countDown();
            assertEquals("alice", first.get(5, TimeUnit.SECONDS));
            assertEquals("alice", second.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        try {
            reads.read("alice/posts", () -> { throw new IOException("offline"); });
            fail("Expected read failure");
        } catch (IOException expected) { assertEquals("offline", expected.getMessage()); }
        assertEquals("fresh", reads.read("alice/posts", () -> "fresh"));
    }
}
