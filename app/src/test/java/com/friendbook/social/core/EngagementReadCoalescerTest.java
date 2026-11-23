package com.friendbook.social.core;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class EngagementReadCoalescerTest {
    private static void waiting(Thread thread) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(thread.getState()!=Thread.State.WAITING && System.nanoTime()<deadline)Thread.yield();
        assertEquals(Thread.State.WAITING,thread.getState());
    }
    @Test public void sharesOnlyOverlappingReadsNotSequentialReadsAccountsOrCursors() throws Exception {
        EngagementReadCoalescer reads=new EngagementReadCoalescer();
        AtomicInteger calls=new AtomicInteger();
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        FutureTask<String> first=new FutureTask<>(()->reads.execute(true,"alice","comments?limit=10",()->{
            calls.incrementAndGet();started.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("timeout");return "fresh";
        }));
        FutureTask<String> second=new FutureTask<>(()->reads.execute(true,"alice","comments?limit=10",()->{calls.incrementAndGet();return "duplicate";}));
        Thread owner=new Thread(first),waiter=new Thread(second);owner.start();
        try{
            assertTrue(started.await(3,TimeUnit.SECONDS));waiter.start();waiting(waiter);
            assertEquals("bob",reads.execute(true,"bob","comments?limit=10",()->"bob"));
            assertEquals("older",reads.execute(true,"alice","comments?limit=10&before=x",()->"older"));
            release.countDown();assertEquals("fresh",first.get(5,TimeUnit.SECONDS));assertEquals("fresh",second.get(5,TimeUnit.SECONDS));
            assertEquals(1,calls.get());
            assertEquals("next",reads.execute(true,"alice","comments?limit=10",()->{calls.incrementAndGet();return "next";}));
            assertEquals(2,calls.get());
        }finally{release.countDown();owner.join(5000);waiter.join(5000);}
    }
    @Test public void failuresAreNotCachedAndWritesAlwaysExecute() throws Exception {
        EngagementReadCoalescer reads=new EngagementReadCoalescer();AtomicInteger writes=new AtomicInteger();
        try{reads.execute(true,"alice","same",()->{throw new IOException("offline");});fail();}
        catch(IOException expected){assertEquals("offline",expected.getMessage());}
        assertEquals("recovered",reads.execute(true,"alice","same",()->"recovered"));
        for(int i=0;i<2;i++)reads.execute(false,"alice","same",()->{writes.incrementAndGet();return "ok";});
        assertEquals(2,writes.get());
    }
    @Test public void mutationFencesBothSuccessAndAmbiguousFailure() throws Exception {
        for(boolean failed:new boolean[]{false,true}){
            EngagementReadCoalescer reads=new EngagementReadCoalescer();
            CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
            FutureTask<String> old=new FutureTask<>(()->reads.execute(true,"alice","same",()->{
                started.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("timeout");return "old";
            }));Thread owner=new Thread(old);owner.start();
            try{
                assertTrue(started.await(3,TimeUnit.SECONDS));
                try{reads.execute(false,"alice","mutation",()->{if(failed)throw new IOException("ambiguous write");return "written";});}
                catch(IOException expected){assertTrue(failed);}
                assertEquals("new",reads.execute(true,"alice","same",()->"new"));
                release.countDown();assertEquals("old",old.get(5,TimeUnit.SECONDS));
            }finally{release.countDown();owner.join(5000);}
        }
    }
}
