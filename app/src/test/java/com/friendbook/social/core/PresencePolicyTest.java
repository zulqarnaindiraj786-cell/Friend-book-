package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class PresencePolicyTest {
    private static final long NOW = 2_000_000_000_000L;
    @Test public void onlineRequiresActiveAndStrictlyLessThanFiveMinutes() {
        assertTrue(PresencePolicy.isFreshOnline(true, NOW - 299_999, NOW));
        assertFalse(PresencePolicy.isFreshOnline(true, NOW - 300_000, NOW));
        assertFalse(PresencePolicy.isFreshOnline(false, NOW, NOW));
        assertFalse(PresencePolicy.isFreshOnline(true, 0, NOW));
        assertFalse(PresencePolicy.isFreshOnline(true, NOW + 60_000, NOW));
    }
    @Test public void rtdbOfflineOverridesEvenNewerFirestoreOnline() {
        assertFalse(PresencePolicy.resolve(true, true, false, NOW - 1000, true, true, NOW, NOW));
    }
    @Test public void staleRtdbOnlineCannotBeResurrectedByFirestore() {
        assertFalse(PresencePolicy.resolve(true, true, true, NOW - 300_000, true, true, NOW, NOW));
    }
    @Test public void cacheCannotProveOnlineAndPrivacyHidesAll() {
        assertFalse(PresencePolicy.resolve(true, false, false, 0, false, true, NOW, NOW));
        assertTrue(PresencePolicy.resolve(true, false, false, 0, true, true, NOW, NOW));
        assertFalse(PresencePolicy.resolve(false, true, true, NOW, true, true, NOW, NOW));
    }
    @Test public void freshOtherDeviceIsNotHiddenByFirestoreOffline() {
        assertTrue(PresencePolicy.resolve(true, true, true, NOW - 10_000, true, false, NOW, NOW));
    }
}
