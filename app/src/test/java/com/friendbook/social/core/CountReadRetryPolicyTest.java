package com.friendbook.social.core;

import com.friendbook.social.d1.documents.D1DocumentStoreException;
import org.junit.Test;
import static org.junit.Assert.*;

public class CountReadRetryPolicyTest {
    private static Exception failure(D1DocumentStoreException.Code code) {
        return new D1DocumentStoreException(code,"test");
    }
    @Test public void outageStopsAfterThreeAttemptsInsteadOfRetryingForever() {
        Exception offline=failure(D1DocumentStoreException.Code.UNAVAILABLE);
        assertEquals(1000L,CountReadRetryPolicy.delayAfterFailure(1,offline));
        assertEquals(2000L,CountReadRetryPolicy.delayAfterFailure(2,offline));
        for(int attempts=3;attempts<10000;attempts++)
            assertEquals(-1L,CountReadRetryPolicy.delayAfterFailure(attempts,offline));
    }
    @Test public void quotaAuthPermissionAndMalformedQueriesNeverRetry() {
        for(D1DocumentStoreException.Code code:D1DocumentStoreException.Code.values()) {
            boolean retryable=code==D1DocumentStoreException.Code.UNAVAILABLE
                    || code==D1DocumentStoreException.Code.DEADLINE_EXCEEDED
                    || code==D1DocumentStoreException.Code.ABORTED
                    || code==D1DocumentStoreException.Code.INTERNAL;
            assertEquals(code.toString(),retryable,CountReadRetryPolicy.delayAfterFailure(1,failure(code))>=0);
        }
    }
    @Test public void ioFailureCanRecoverButUnknownProgrammingFailureDoesNotLoop() {
        assertEquals(1000L,CountReadRetryPolicy.delayAfterFailure(1,new java.io.IOException()));
        assertEquals(-1L,CountReadRetryPolicy.delayAfterFailure(1,new IllegalStateException()));
        assertEquals(-1L,CountReadRetryPolicy.delayAfterFailure(0,new java.io.IOException()));
    }
}
