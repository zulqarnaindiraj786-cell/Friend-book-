package com.friendbook.social.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileListenerReuseTest {
    @Test public void healthyRegistrationSurvivesRepeatedActivityResumes() {
        for(int resume=0;resume<10000;resume++)
            assertFalse(OnDemandLoads.profileListenerNeedsRestart("a","a",true,false));
    }
    @Test public void absentFailedAndChangedAccountRegistrationsRestart() {
        assertTrue(OnDemandLoads.profileListenerNeedsRestart("a",null,false,false));
        assertTrue(OnDemandLoads.profileListenerNeedsRestart("a","a",true,true));
        assertTrue(OnDemandLoads.profileListenerNeedsRestart("b","a",true,false));
    }
    @Test public void signedOutNeverStartsARegistration() {
        assertFalse(OnDemandLoads.profileListenerNeedsRestart(null,"a",true,true));
        assertFalse(OnDemandLoads.profileListenerNeedsRestart("",null,false,true));
    }
}
