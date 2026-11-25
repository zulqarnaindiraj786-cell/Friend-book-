package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class StoryAudienceTest {
    @Test public void publicStrangersAreExcluded(){
        assertFalse(StoryAudience.visible("me","other","Public",false,false,false,false));
        assertTrue(StoryAudience.visible("me","other","Public",false,true,false,false));
        assertTrue(StoryAudience.visible("me","other","Public",true,false,false,false));
    }
    @Test public void followingDoesNotBypassPrivacyOrSafety(){
        assertFalse(StoryAudience.visible("me","other","Friends",false,true,false,false));
        assertTrue(StoryAudience.visible("me","other","Friends",true,true,false,false));
        assertFalse(StoryAudience.visible("me","other","Only me",true,true,false,false));
        assertFalse(StoryAudience.visible("me","other","Public",true,true,true,false));
        assertFalse(StoryAudience.visible("me","other","Public",true,true,false,true));
    }
    @Test public void ownPrivateStoryIsVisibleButAnonymousViewerIsNot(){
        assertTrue(StoryAudience.visible("me","me","Only me",false,false,false,false));
        assertFalse(StoryAudience.visible(null,"other","Public",true,true,false,false));
        assertFalse(StoryAudience.visible("me",null,"Public",true,true,false,false));
    }
    @Test public void membershipIsReadOnlyWhenItCanAffectVisibility(){
        for(String actor:new String[]{null,"me"}) for(String author:new String[]{null,"me","other"})
            for(String privacy:new String[]{null,"Public","Friends","friends","Only me"})
                for(boolean following:new boolean[]{false,true}) for(boolean blocked:new boolean[]{false,true})
                    for(boolean muted:new boolean[]{false,true}) {
                        boolean without=StoryAudience.visible(actor,author,privacy,false,following,blocked,muted);
                        boolean with=StoryAudience.visible(actor,author,privacy,true,following,blocked,muted);
                        assertEquals(without!=with,StoryAudience.needsFriendship(actor,author,privacy,following,blocked,muted));
                    }
        assertFalse(StoryAudience.needsFriendship("me","other","Public",true,false,false));
        assertTrue(StoryAudience.needsFriendship("me","other","Friends",true,false,false));
        assertFalse(StoryAudience.needsFriendship("me","other","Only me",false,false,false));
        assertFalse(StoryAudience.needsFriendship("me","other","Friends",false,true,false));
    }
}
