package com.friendbook.social.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class BadgeLookupStateTest {
    @Test public void oneFlightFansOutToAllRowsAndRepeatedBindsDoNotMultiplyRequests() {
        BadgeLookupState<Object> state=new BadgeLookupState<>();
        Object first=new Object(),second=new Object();
        BadgeLookupState.Flight<Object> flight=state.bind("viewer","author",first,0);
        assertNotNull(flight);
        for(int i=0;i<100;i++) assertNull(state.bind("viewer","author",first,i));
        assertNull(state.bind("viewer","author",second,100));
        List<Object> views=state.finish("viewer",flight,"blue",101);
        assertEquals(2,views.size());assertTrue(views.contains(first));assertTrue(views.contains(second));
        assertEquals("blue",state.cached("viewer","author"));
        assertNull(state.bind("viewer","author",new Object(),600000));
    }
    @Test public void recycledViewDoesNotReceiveOldBadgeEvenWhenNewTargetIsCachedOrEmpty() {
        for(String target:new String[]{"other",""}) {
            BadgeLookupState<Object> state=new BadgeLookupState<>();Object view=new Object();
            BadgeLookupState.Flight<Object> old=state.bind("viewer","old",view,0);
            state.put("viewer","other","gold");state.bind("viewer",target,view,1);
            assertTrue(state.finish("viewer",old,"blue",2).isEmpty());
        }
    }
    @Test public void failureRetriesOnLaterBindOnlyAndMissingResultIsCached() {
        BadgeLookupState<Object> state=new BadgeLookupState<>();Object view=new Object();
        BadgeLookupState.Flight<Object> first=state.bind("viewer","author",view,0);
        assertTrue(state.finish("viewer",first,null,100).isEmpty());
        assertNull(state.cached("viewer","author"));
        assertNull(state.bind("viewer","author",view,10099));
        BadgeLookupState.Flight<Object> retry=state.bind("viewer","author",view,10100);
        assertNotNull(retry);state.finish("viewer",retry,"none",10101);
        assertNull(state.bind("viewer","author",view,600000));
    }
    @Test public void accountSwitchClearAndExplicitUpdatesFenceOldResults() {
        BadgeLookupState<Object> state=new BadgeLookupState<>();Object view=new Object();
        BadgeLookupState.Flight<Object> alice=state.bind("alice","author",view,0);
        assertNotNull(state.bind("bob","author",view,1));
        assertTrue(state.finish("alice",alice,"blue",2).isEmpty());
        BadgeLookupState.Flight<Object> fresh=state.bind("alice","author",view,3);
        state.put("alice","author","gold");state.finish("alice",fresh,"blue",4);
        assertEquals("gold",state.cached("alice","author"));
        state.clear();assertNull(state.cached("alice","author"));
        assertNull(state.bind(null,"author",view,5));
    }
}
