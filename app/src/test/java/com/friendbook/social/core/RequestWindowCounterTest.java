package com.friendbook.social.core;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestWindowCounterTest {
    @Test public void optInAndExactWindowBoundaries() {
        RequestWindowCounter counter = new RequestWindowCounter();
        counter.record(100, "GET", "/v1/firestore/document", "users/private-id");
        assertTrue(counter.finish().isEmpty());
        assertTrue(counter.begin(100));
        counter.record(99, "GET", "/v1/firestore/document", "users/private-id");
        counter.record(100, "GET", "/v1/firestore/document", "users/private-id");
        counter.record(60_099, "GET", "/v1/firestore/document", "posts/private-post");
        counter.record(60_100, "GET", "/v1/firestore/document", "posts/private-post");
        Map<String,Integer> report=counter.finish();
        assertEquals(Integer.valueOf(1), report.get("document_read_users"));
        assertEquals(Integer.valueOf(1), report.get("document_read_posts"));
        assertEquals(2, report.size());
        counter.record(101, "GET", "/v1/firestore/document", "users/ignored");
        assertTrue(counter.finish().isEmpty());
    }

    @Test public void recreationDoesNotResetAndNextExplicitWindowIsIndependent() {
        RequestWindowCounter counter = new RequestWindowCounter();
        assertTrue(counter.begin(0));
        counter.record(1,"POST","/v1/firestore/query",null);
        assertFalse(counter.begin(20));
        assertEquals(Integer.valueOf(1),counter.finish().get("document_query"));
        assertTrue(counter.begin(30));
        assertTrue(counter.finish().isEmpty());
    }

    @Test public void unknownRoutesAndResourcesCannotCreateUnboundedOrIdentifyingKeys() {
        RequestWindowCounter counter = new RequestWindowCounter();
        counter.begin(0);
        for(int i=0;i<1000;i++) {
            counter.record(i,"GET","/secret/"+i,"secret/"+i);
            counter.record(i,"GET","/v1/firestore/document","private-root-"+i+"/id");
        }
        counter.record(1001,"PATCH","/v1/firestore/document","users/secret");
        counter.record(1001,"GET","/v1/firestore/count",null);
        counter.record(1001,"POST","/v1/firestore/batch",null);
        Map<String,Integer> report=counter.finish();
        assertEquals(5,report.size());
        assertEquals(Integer.valueOf(1000),report.get("other_api"));
        assertEquals(Integer.valueOf(1000),report.get("document_read_other"));
        assertFalse(report.toString().contains("secret"));
    }

    @Test public void concurrentAttemptsAreCountedWithoutLoss() throws Exception {
        RequestWindowCounter counter = new RequestWindowCounter();counter.begin(0);
        Thread[] threads=new Thread[8];
        for(int i=0;i<threads.length;i++) {
            threads[i]=new Thread(()->{for(int n=0;n<1000;n++)counter.record(n,"GET","/v1/firestore/document","users/id");});
            threads[i].start();
        }
        for(Thread thread:threads)thread.join();
        assertEquals(Integer.valueOf(8000),counter.finish().get("document_read_users"));
    }
}
