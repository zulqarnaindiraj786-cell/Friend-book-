package com.friendbook.social.push;
import org.junit.Test;
import static org.junit.Assert.*;

public class PushRefreshBurstGateTest {
    @Test public void exactTransportDuplicateHasFixedShortWindowNotPermanentSuppression() {
        PushRefreshBurstGate gate=new PushRefreshBurstGate();
        assertTrue(gate.allow("viewer","transport-one",0));
        for(int i=1;i<1000;i++) assertFalse(gate.allow("viewer","transport-one",i));
        assertTrue(gate.allow("viewer","transport-one",1000));
        assertTrue(gate.allow("viewer","different-transport",1001));
    }
    @Test public void unknownIdsAndAccountSwitchesNeverShareDuplicateState() {
        PushRefreshBurstGate gate=new PushRefreshBurstGate();
        for(int i=0;i<3;i++) {assertTrue(gate.allow("alice",null,0));assertTrue(gate.allow("alice","",0));}
        assertTrue(gate.allow("alice","same",0));
        assertTrue(gate.allow("bob","same",1));
        assertTrue(gate.allow("alice","same",2));
        assertFalse(gate.allow(null,"same",3));
        assertTrue(gate.allow("alice","same",4));
    }
    @Test public void evictionOrClockResetAllowsHintInsteadOfLosingUpdate() {
        PushRefreshBurstGate gate=new PushRefreshBurstGate();
        assertTrue(gate.allow("viewer","old",10));
        assertTrue(gate.allow("viewer","old",9));
        for(int i=0;i<256;i++)assertTrue(gate.allow("viewer","new"+i,10));
        assertTrue(gate.allow("viewer","old",11));
    }
}
