package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=android.app.Application.class)
public class DocumentReadFlightsTest {
    @Test public void hundredOverlappingReadsStartOneOperationButDistinctPathsAndAccountsStaySeparate() {
        DocumentReadFlights<String> reads = new DocumentReadFlights<>();
        TaskCompletionSource<String> pending = new TaskCompletionSource<>();
        AtomicInteger starts = new AtomicInteger();
        Task<String> first = reads.read("alice", "posts/1", true, () -> {starts.incrementAndGet(); return pending.getTask();});
        for (int i=0; i<100; i++) assertSame(first, reads.read("alice", "posts/1", true,
                () -> {starts.incrementAndGet(); return Tasks.forResult("duplicate");}));
        assertEquals(1, starts.get());
        assertEquals("other", reads.read("alice", "posts/2", true, () -> Tasks.forResult("other")).getResult());
        assertEquals("bob", reads.read("bob", "posts/1", true, () -> Tasks.forResult("bob")).getResult());
        pending.setResult("post");
        assertEquals("post", first.getResult());
        assertEquals("next", reads.read("alice", "posts/1", true, () -> Tasks.forResult("next")).getResult());
    }

    @Test public void explicitServerReadsNeitherJoinNorReplaceDefaultFlight() {
        DocumentReadFlights<String> reads = new DocumentReadFlights<>();
        TaskCompletionSource<String> pending = new TaskCompletionSource<>();
        Task<String> first = reads.read("a", "p", true, pending::getTask);
        for (int i=0; i<2; i++) assertEquals("fresh", reads.read("a", "p", false, () -> Tasks.forResult("fresh")).getResult());
        assertSame(first, reads.read("a", "p", true, () -> Tasks.forResult("bad")));
        pending.setResult("original");
    }

    @Test public void invalidationDetachesOldFlightAndOldCompletionCannotEvictReplacement() {
        DocumentReadFlights<String> reads = new DocumentReadFlights<>();
        TaskCompletionSource<String> old = new TaskCompletionSource<>(), replacement = new TaskCompletionSource<>();
        Task<String> before = reads.read("a", "p", true, old::getTask);
        reads.invalidate("a", "p");
        Task<String> after = reads.read("a", "p", true, replacement::getTask);
        assertNotSame(before, after);
        old.setResult("before mutation");
        assertSame(after, reads.read("a", "p", true, () -> Tasks.forResult("bad")));
        replacement.setResult("after mutation");
        assertEquals("before mutation", before.getResult());
        assertEquals("after mutation", after.getResult());
    }

    @Test public void failuresMissesAndFactoryRejectionsAreNeverRetained() {
        DocumentReadFlights<String> reads = new DocumentReadFlights<>();
        assertNull(reads.read("a", "p", true, () -> Tasks.forResult(null)).getResult());
        assertFalse(reads.read("a", "p", true, () -> Tasks.forException(new java.io.IOException("offline"))).isSuccessful());
        assertFalse(reads.read("a", "p", true, () -> {throw new java.util.concurrent.RejectedExecutionException();}).isSuccessful());
        TaskCompletionSource<String> cancelled = new TaskCompletionSource<>();
        Task<String> result = reads.read("a", "p", true, cancelled::getTask);
        cancelled.setException(new java.io.IOException("cancelled"));
        assertFalse(result.isSuccessful());
        assertEquals("retry", reads.read("a", "p", true, () -> Tasks.forResult("retry")).getResult());
    }

    @Test public void callbacksCanImmediatelyStartNewRead() {
        DocumentReadFlights<String> reads = new DocumentReadFlights<>();
        TaskCompletionSource<String> pending = new TaskCompletionSource<>();
        Task<String> first = reads.read("a", "p", true, pending::getTask);
        String[] next = {null};
        first.addOnSuccessListener(Runnable::run, value -> next[0] =
                reads.read("a", "p", true, () -> Tasks.forResult("new")).getResult());
        pending.setResult("old");
        assertEquals("new", next[0]);
    }
}
