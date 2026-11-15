package com.friendbook.social.push;

import org.junit.Test;
import static org.junit.Assert.*;

public class PushRetryPolicyTest {
    @Test public void registrationRecoversBeyondFormerRetryLimit() {
        assertTrue(PushRetryPolicy.shouldRetry(true, 503, 50));
        assertTrue(PushRetryPolicy.shouldRetry(true, 0, 50));
        assertTrue(PushRetryPolicy.shouldRetry(true, 429, 50));
    }
    @Test public void staleSessionAndTimeoutCanRecoverAutomatically() {
        assertTrue(PushRetryPolicy.shouldRetry(true, 401, 10));
        assertTrue(PushRetryPolicy.shouldRetry(true, 408, 10));
    }
    @Test public void malformedRequestIsNotAnInfiniteRegistrationLoop() {
        assertFalse(PushRetryPolicy.shouldRetry(true, 400, 0));
        assertFalse(PushRetryPolicy.shouldRetry(true, 403, 0));
    }
    @Test public void successNeverRetries() {
        assertFalse(PushRetryPolicy.shouldRetry(true, 200, 0));
    }
    @Test public void eventRetriesRemainBounded() {
        assertTrue(PushRetryPolicy.shouldRetry(false, 503, 7));
        assertFalse(PushRetryPolicy.shouldRetry(false, 503, 8));
    }
    @Test public void ambiguousFirestoreReadFailureRetriesButNotForever() {
        assertTrue(PushRetryPolicy.shouldRetry(false, 400, 0, "INVALID_OR_UNAVAILABLE_EVENT"));
        assertFalse(PushRetryPolicy.shouldRetry(false, 400, 8, "INVALID_OR_UNAVAILABLE_EVENT"));
        assertFalse(PushRetryPolicy.shouldRetry(false, 400, 0, "INVALID_TARGET"));
        assertFalse(PushRetryPolicy.shouldRetry(false, 403, 0, "Forbidden event"));
    }
}
