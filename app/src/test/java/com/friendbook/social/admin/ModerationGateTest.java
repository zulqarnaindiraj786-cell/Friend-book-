package com.friendbook.social.admin;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModerationGateTest {
    @Test public void duplicateActionsWaitForCompletionButOtherItemsAndAccountsProceed(){
        ModerationGate gate=new ModerationGate();
        assertFalse(gate.begin(null,"reports","r"));
        assertTrue(gate.begin("a","reports","r"));
        for(int i=0;i<1000;i++)assertFalse(gate.begin("a","reports","r"));
        assertTrue(gate.begin("a","reports","other"));
        assertTrue(gate.begin("b","reports","r"));
        assertTrue(gate.begin("a","feedback","r"));
        gate.end("a","reports","r");
        assertFalse(gate.pending("a","reports","r"));
        assertTrue(gate.pending("b","reports","r"));
        assertTrue(gate.begin("a","reports","r"));
    }
}
