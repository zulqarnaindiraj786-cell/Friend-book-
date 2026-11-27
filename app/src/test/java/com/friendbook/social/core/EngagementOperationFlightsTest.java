package com.friendbook.social.core;

import com.google.android.gms.tasks.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class EngagementOperationFlightsTest {
    @Test public void queuedSummaryAndCommentReadersShareBeforeExecutorStarts() throws Exception {
        for(String kind:Arrays.asList("reactions","comments")) {
            ProfileReadFlights<List<String>,String> reads=new ProfileReadFlights<>();
            List<String> key=Arrays.asList("actor","posts","post",kind,"?limit=10","normal");
            TaskCompletionSource<String> queued=new TaskCompletionSource<>();
            AtomicInteger scheduled=new AtomicInteger();
            Task<String> first=reads.read(key,()->{scheduled.incrementAndGet();return queued.getTask();});
            for(int i=0;i<100;i++)assertSame(first,reads.read(key,()->{scheduled.incrementAndGet();return Tasks.forResult("duplicate");}));
            assertEquals(1,scheduled.get());
            Task<JSONObject> left=first.continueWith(Runnable::run,t->new JSONObject(t.getResult()));
            Task<JSONObject> right=first.continueWith(Runnable::run,t->new JSONObject(t.getResult()));
            queued.setResult("{\"comments\":1}");
            left.getResult().put("comments",999);
            assertEquals(1,right.getResult().getInt("comments"));
            assertEquals("fresh",reads.read(key,()->Tasks.forResult("fresh")).getResult());
        }
    }
    @Test public void mutationDetachAndLateCompletionProtectReplacement() {
        ProfileReadFlights<String,String> reads=new ProfileReadFlights<>();
        TaskCompletionSource<String> old=new TaskCompletionSource<>(),next=new TaskCompletionSource<>();
        reads.read("resource",old::getTask);reads.invalidateAll();
        Task<String> replacement=reads.read("resource",next::getTask);
        old.setResult("old");
        assertSame(replacement,reads.read("resource",()->Tasks.forResult("bad")));
        next.setResult("new");assertEquals("new",replacement.getResult());
    }
    @Test public void resourceMutationDetachesOnlyThatResourceAcrossActorsAndKinds() {
        ProfileReadFlights<List<String>,String> reads=new ProfileReadFlights<>();
        List<String> target=Arrays.asList("viewer","posts","post-1","reactions","","normal");
        List<String> sameResourceOtherActor=Arrays.asList("other","posts","post-1","comments","?limit=10","interactive");
        List<String> unrelated=Arrays.asList("viewer","posts","post-2","reactions","","normal");
        Task<String> oldTarget=reads.read(target,()->new TaskCompletionSource<String>().getTask());
        Task<String> oldOtherActor=reads.read(sameResourceOtherActor,()->new TaskCompletionSource<String>().getTask());
        Task<String> oldUnrelated=reads.read(unrelated,()->new TaskCompletionSource<String>().getTask());
        D1Client.invalidateResourceReads(reads,"posts","post-1");
        Task<String> newTarget=reads.read(target,()->new TaskCompletionSource<String>().getTask());
        assertNotSame(oldTarget,newTarget);
        assertNotSame(oldOtherActor,reads.read(sameResourceOtherActor,()->new TaskCompletionSource<String>().getTask()));
        assertSame(oldUnrelated,reads.read(unrelated,()->Tasks.forResult("must-not-start")));
        D1Client.invalidateResourceReads(reads,"posts","post-2");
        assertNotSame(oldUnrelated,reads.read(unrelated,()->new TaskCompletionSource<String>().getTask()));
    }

    @Test public void accountResourceCursorKindAndPriorityNeverMix() {
        ProfileReadFlights<List<String>,String> reads=new ProfileReadFlights<>();
        List<String> base=Arrays.asList("actor","posts","post","reactions","","normal");
        Task<String> first=reads.read(base,()->new TaskCompletionSource<String>().getTask());
        for(int field=0;field<base.size();field++) {
            List<String> changed=new ArrayList<>(base);changed.set(field,"different");
            assertNotSame(first,reads.read(changed,()->new TaskCompletionSource<String>().getTask()));
        }
    }
}
