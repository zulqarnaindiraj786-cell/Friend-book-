package com.friendbook.social.core;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The distinction this class makes - "the backend refused us" vs "there is nothing here" - is
 * the difference between an empty list and a believable outage. At ~200 daily users on the
 * Spark plan every read fails once the daily quota is spent, and without this the app silently
 * showed blank feeds, blank chats and blank profiles, which reads as data loss.
 *
 * <p>Tested through codes rather than FirebaseFirestoreException instances: constructing that
 * exception pulls in Firestore internals that are not present on a plain JVM.
 */
public class QuotaGuardTest {

    @After public void reset() { QuotaGuard.resetForTest(); }

    @Test public void exhaustedQuotaCountsAsARefusal() {
        assertTrue(QuotaGuard.noteRefusalCode("RESOURCE_EXHAUSTED"));
        assertTrue(QuotaGuard.degraded());
    }

    @Test public void unreachableBackendCountsAsARefusal() {
        assertTrue(QuotaGuard.noteRefusalCode("UNAVAILABLE"));
        assertTrue(QuotaGuard.degraded());
    }

    @Test public void deadlineAndAbortedAreRefusals() {
        assertTrue(QuotaGuard.isRefusalCode("DEADLINE_EXCEEDED"));
        assertTrue(QuotaGuard.isRefusalCode("ABORTED"));
        assertTrue(QuotaGuard.isRefusalCode("INTERNAL"));
    }

    @Test public void aMissingDocumentIsNotARefusal() {
        // NOT_FOUND means the data really is absent. Showing an outage message would be a lie
        // and would hide genuine emptiness behind a retry prompt.
        assertFalse(QuotaGuard.noteRefusalCode("NOT_FOUND"));
        assertFalse(QuotaGuard.degraded());
    }

    @Test public void permissionDeniedIsNotARefusal() {
        assertFalse(QuotaGuard.noteRefusalCode("PERMISSION_DENIED"));
        assertFalse(QuotaGuard.degraded());
    }

    @Test public void unknownAndNullCodesAreNotRefusals() {
        assertFalse(QuotaGuard.isRefusalCode(null));
        assertFalse(QuotaGuard.isRefusalCode(""));
        assertFalse(QuotaGuard.isRefusalCode("SOMETHING_ELSE"));
        assertFalse(QuotaGuard.degraded());
    }

    @Test public void aSuccessClearsTheDegradedState() {
        QuotaGuard.noteRefusalCode("RESOURCE_EXHAUSTED");
        assertTrue(QuotaGuard.shouldSuppressNetworkWork());
        QuotaGuard.noteSuccess();
        assertFalse("recovery must not need an app restart", QuotaGuard.degraded());
        assertFalse(QuotaGuard.shouldSuppressNetworkWork());
    }

    @Test public void theUserMessageNeverLeaksSdkVocabulary() {
        String msg = QuotaGuard.userMessage(null);
        assertNotNull(msg);
        assertFalse(msg.contains("RESOURCE_EXHAUSTED"));
        assertFalse(msg.contains("Firestore"));
        assertFalse(msg.contains("Firebase"));
    }

    @Test public void theDegradedMessageSaysTheDataIsSafe() {
        QuotaGuard.noteRefusalCode("RESOURCE_EXHAUSTED");
        String msg = QuotaGuard.userMessage(null);
        // The whole point: an outage must not read as data loss.
        assertTrue("should reassure about data safety, got: " + msg, msg.contains("safe"));
    }

    @Test public void theReasonIsRecordedForDiagnostics() {
        QuotaGuard.noteRefusalCode("RESOURCE_EXHAUSTED");
        assertEquals("RESOURCE_EXHAUSTED", QuotaGuard.lastReason());
    }
}
