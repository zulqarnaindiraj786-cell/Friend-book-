package com.friendbook.social.post;

import org.junit.Test;
import static org.junit.Assert.*;

public class CommentAuthorLookupGateTest {
    @Test public void repeatedBindsAndMissingProfileOnlyFetchOnce() {
        CommentAuthorLookupGate gate=new CommentAuthorLookupGate();
        long ticket=gate.begin("viewer","author",0);
        assertTrue(ticket>=0);
        for(int i=0;i<100;i++) assertEquals(-1,gate.begin("viewer","author",i));
        assertTrue(gate.finish("viewer","author",ticket,true,100));
        assertEquals(-1,gate.begin("viewer","author",600000));
        assertTrue(gate.begin("viewer","other-author",600000)>=0);
    }
    @Test public void failuresRetryOnlyOnLaterBindAfterCooldownNotOnTimer() {
        CommentAuthorLookupGate gate=new CommentAuthorLookupGate();
        long ticket=gate.begin("viewer","author",0);
        assertTrue(gate.finish("viewer","author",ticket,false,100));
        assertEquals(-1,gate.begin("viewer","author",10099));
        assertTrue(gate.begin("viewer","author",10100)>=0);
        assertEquals(-1,gate.begin("viewer","author",10101));
    }
    @Test public void accountSwitchRejectsOldCallbacksAndSeparatesResults() {
        CommentAuthorLookupGate gate=new CommentAuthorLookupGate();
        long first=gate.begin("alice","author",0);
        long second=gate.begin("bob","author",1);
        assertFalse(gate.finish("alice","author",first,true,2));
        long third=gate.begin("alice","author",3);
        assertFalse(gate.finish("alice","author",first,true,4));
        assertFalse(gate.finish("bob","author",second,true,4));
        assertTrue(gate.finish("alice","author",third,true,4));
        assertEquals(-1,gate.begin(null,"author",5));
        assertEquals(-1,gate.begin("alice","",6));
    }
}
