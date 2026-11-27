package com.friendbook.social.reels;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReelPreloadPolicyTest {
    @Test public void onlyVisibleImmediateNextCachedFileCanBePrepared() {
        assertTrue(ReelPreloadPolicy.canPrepare(true,4,5,false,false,true,true));
        assertFalse(ReelPreloadPolicy.canPrepare(false,4,5,false,false,true,true));
        assertFalse(ReelPreloadPolicy.canPrepare(true,-1,0,false,false,true,true));
        assertFalse(ReelPreloadPolicy.canPrepare(true,4,4,false,false,true,true));
        assertFalse(ReelPreloadPolicy.canPrepare(true,4,6,false,false,true,true));
        assertFalse(ReelPreloadPolicy.canPrepare(true,4,5,true,false,true,true));
        assertFalse(ReelPreloadPolicy.canPrepare(true,4,5,false,true,true,true));
        assertFalse(ReelPreloadPolicy.canPrepare(true,4,5,false,false,false,true));
        assertFalse(ReelPreloadPolicy.canPrepare(true,4,5,false,false,true,false));
    }
}
