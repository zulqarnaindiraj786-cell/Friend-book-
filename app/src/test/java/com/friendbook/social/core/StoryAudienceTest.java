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
}
