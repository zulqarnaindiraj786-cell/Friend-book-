package com.friendbook.social.core;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Loopback HTTP only: no Firebase identity, Cloudflare credential or production traffic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class EngagementReadNetworkTest {
    @Test public void identicalConcurrentGetMakesOneHttpRequestWithIndependentJsonResults() throws Exception {
        MockWebServer server=new MockWebServer();
        CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request)throws InterruptedException{
            received.countDown();if(!release.await(5,TimeUnit.SECONDS))return new MockResponse().setResponseCode(503);
            return new MockResponse().setBody("{\"comments\":[{\"id\":\"fixture\"}],\"next\":null}").addHeader("Content-Type","application/json");
        }});server.start();
        EngagementReadCoalescer reads=new EngagementReadCoalescer();URL url=server.url("/comments?limit=10").url();
        java.util.concurrent.Callable<JSONObject> read=()->new JSONObject(reads.execute(true,"alice",url.toString(),
                ()->D1HttpTransport.send(url,"GET","synthetic-token",null).toString()));
        FutureTask<JSONObject> first=new FutureTask<>(read),second=new FutureTask<>(read);
        Thread owner=new Thread(first),waiter=new Thread(second);owner.start();
        try{
            assertTrue(received.await(3,TimeUnit.SECONDS));waiter.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(waiter.getState()!=Thread.State.WAITING&&System.nanoTime()<deadline)Thread.yield();
            assertEquals(Thread.State.WAITING,waiter.getState());release.countDown();
            JSONObject a=first.get(5,TimeUnit.SECONDS),b=second.get(5,TimeUnit.SECONDS);
            assertEquals(1,server.getRequestCount());
            a.getJSONArray("comments").getJSONObject(0).put("id","locally-edited");
            assertEquals("fixture",b.getJSONArray("comments").getJSONObject(0).getString("id"));
            assertEquals("Bearer synthetic-token",server.takeRequest(1,TimeUnit.SECONDS).getHeader("Authorization"));
            // A later explicit refresh is a real new GET, not served from a TTL cache.
            read.call();assertEquals(2,server.getRequestCount());
        }finally{release.countDown();owner.join(5000);waiter.join(5000);server.shutdown();}
    }
}
