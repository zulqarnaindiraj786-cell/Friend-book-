package com.friendbook.social.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdSocialReadGateTest {
    @Test public void settledOrDeniedCreativeDoesNotRefetchOnRebindButExplicitOpenCan() {
        AdSocialReadGate gate=new AdSocialReadGate();
        AdSocialReadGate.Attempt first=gate.begin("a","ad",0,false);
        assertNotNull(first);
        for(int i=0;i<100;i++)assertNull(gate.begin("a","ad",i,false));
        assertTrue(gate.finish("a",first,true,100));
        for(int i=0;i<100;i++)assertNull(gate.begin("a","ad",100_000+i,false));
        AdSocialReadGate.Attempt explicit=gate.begin("a","ad",200_000,true);
        assertNotNull(explicit);
        assertNull(gate.begin("a","ad",200_001,true));
    }

    @Test public void transientFailureAllowsOneDemandRetryAfterCooldownNotEveryBind() {
        AdSocialReadGate gate=new AdSocialReadGate();
        AdSocialReadGate.Attempt first=gate.begin("a","ad",0,false);
        gate.finish("a",first,false,100);
        int starts=0;
        for(int i=0;i<30_000;i++)if(gate.begin("a","ad",100+i,false)!=null)starts++;
        assertEquals(0,starts);
        assertNotNull(gate.begin("a","ad",30_100,false));
    }

    @Test public void explicitUserOpenBypassesFailureCooldown() {
        AdSocialReadGate gate=new AdSocialReadGate();
        AdSocialReadGate.Attempt first=gate.begin("a","ad",0,false);
        gate.finish("a",first,false,0);
        assertNotNull(gate.begin("a","ad",1,true));
    }

    @Test public void accountsAndLateCallbacksCannotPoisonEachOther() {
        AdSocialReadGate gate=new AdSocialReadGate();
        AdSocialReadGate.Attempt alice=gate.begin("alice","ad",0,false);
        AdSocialReadGate.Attempt bob=gate.begin("bob","ad",0,false);
        assertFalse(gate.finish("bob",alice,true,1));
        assertTrue(gate.current("bob",bob));
        AdSocialReadGate.Attempt aliceAgain=gate.begin("alice","ad",2,false);
        assertFalse(gate.current("alice",alice));
        assertFalse(gate.finish("alice",bob,true,3));
        assertTrue(gate.finish("alice",aliceAgain,true,4));
    }

    @Test public void signOutAndDistinctCreativesRemainIndependent() {
        AdSocialReadGate gate=new AdSocialReadGate();
        assertNull(gate.begin(null,"ad",0,false));
        assertNull(gate.begin("","ad",0,true));
        assertNotNull(gate.begin("a","ad1",0,false));
        assertNotNull(gate.begin("a","ad2",0,false));
        assertFalse(gate.scope("a"));
        assertTrue(gate.scope("b"));
    }
}
