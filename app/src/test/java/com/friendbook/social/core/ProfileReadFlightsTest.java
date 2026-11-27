package com.friendbook.social.core;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class ProfileReadFlightsTest {
    @Test public void overlappingReadersShareWholeAsyncLookupButNotAccountsOrAuthors() {
        ProfileReadFlights<List<String>,String> reads=new ProfileReadFlights<>();
        TaskCompletionSource<String> pending=new TaskCompletionSource<>();
        AtomicInteger calls=new AtomicInteger();List<String> key=Arrays.asList("alice","author");
        Task<String> first=reads.read(key,()->{calls.incrementAndGet();return pending.getTask();});
        for(int i=0;i<100;i++) assertSame(first,reads.read(key,()->{calls.incrementAndGet();return Tasks.forResult("duplicate");}));
        assertEquals(1,calls.get());
        assertEquals("bob",reads.read(Arrays.asList("bob","author"),()->Tasks.forResult("bob")).getResult());
        assertEquals("other",reads.read(Arrays.asList("alice","other"),()->Tasks.forResult("other")).getResult());
        pending.setResult("profile");assertEquals("profile",first.getResult());
        assertEquals("next",reads.read(key,()->Tasks.forResult("next")).getResult());
    }
    @Test public void MissingFailuresAndSynchronousFactoryFailuresAreNotCached() {
        ProfileReadFlights<String,String> reads=new ProfileReadFlights<>();
        assertNull(reads.read("key",()->Tasks.forResult(null)).getResult());
        assertFalse(reads.read("key",()->Tasks.forException(new java.io.IOException("offline"))).isSuccessful());
        assertFalse(reads.read("key",()->{throw new java.io.IOException("factory");}).isSuccessful());
        assertEquals("retry",reads.read("key",()->Tasks.forResult("retry")).getResult());
    }
    @Test public void completionCallbackCanStartANewReadInsteadOfReusingCompletedFlight() {
        ProfileReadFlights<String,String> reads=new ProfileReadFlights<>();
        TaskCompletionSource<String> pending=new TaskCompletionSource<>();
        Task<String> first=reads.read("key",pending::getTask);
        String[] next={null};
        first.addOnSuccessListener(Runnable::run,value->next[0]=reads.read("key",()->Tasks.forResult("new")).getResult());
        pending.setResult("old");assertEquals("new",next[0]);
    }
}
