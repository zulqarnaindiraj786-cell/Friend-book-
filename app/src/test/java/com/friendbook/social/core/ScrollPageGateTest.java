package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class ScrollPageGateTest {
    @Test public void onlyApproachingEndCanRequest(){
        ScrollPageGate gate=new ScrollPageGate();
        assertFalse(gate.shouldLoad(0,true,20));assertFalse(gate.shouldLoad(-3,true,20));
        assertFalse(gate.shouldLoad(3,true,0));assertFalse(gate.shouldLoad(3,false,20));
        assertTrue(gate.shouldLoad(3,true,20));assertFalse(gate.shouldLoad(3,true,20));
    }
    @Test public void appendAllowsOneNextPageAndScrollAwayAllowsRetry(){
        ScrollPageGate gate=new ScrollPageGate();assertTrue(gate.shouldLoad(2,true,20));
        assertFalse(gate.shouldLoad(2,true,20));assertTrue(gate.shouldLoad(2,true,40));
        assertFalse(gate.shouldLoad(2,false,40));assertTrue(gate.shouldLoad(2,true,40));
    }
    @Test public void fiveThousandVirtualReadersDoNotCreateScrollRequestStorm(){
        int requests=0;
        for(int user=0;user<5000;user++){
            ScrollPageGate gate=new ScrollPageGate();
            for(int event=0;event<100;event++)if(gate.shouldLoad(2,true,20))requests++;
        }
        assertEquals(5000,requests); // Model only, not actual Firebase traffic/capacity proof.
    }
}
