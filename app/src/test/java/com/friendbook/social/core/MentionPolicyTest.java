package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class MentionPolicyTest {
    @Test public void selectedIdentityLinksExactLabel() {
        assertTrue(MentionPolicy.valid("Hi @Ali Khan!",3,12,"@Ali Khan","alice-uid"));
    }
    @Test public void changedOrShiftedTextNeverLinksWrongIdentity() {
        assertFalse(MentionPolicy.valid("Hi @Bob Khan!",3,12,"@Ali Khan","alice-uid"));
        assertFalse(MentionPolicy.valid("X Hi @Ali Khan!",3,12,"@Ali Khan","alice-uid"));
    }
    @Test public void invalidRangesAndPathsAreRejected() {
        assertFalse(MentionPolicy.valid("@Ali",-1,4,"@Ali","uid"));
        assertFalse(MentionPolicy.valid("@Ali",0,99,"@Ali","uid"));
        assertFalse(MentionPolicy.valid("@Ali",0,4,"@Ali","users/uid"));
        assertFalse(MentionPolicy.valid("@Ali",0,4,"@Ali",""));
        assertFalse(MentionPolicy.valid("@Ali",0,4,"@Ali",null));
    }
    @Test public void unicodeAndDuplicateNamesUseExplicitUidAndRanges() {
        String text="👋 @علی @علی";
        assertTrue(MentionPolicy.valid(text,3,7,"@علی","first-uid"));
        assertTrue(MentionPolicy.valid(text,8,12,"@علی","second-uid"));
        assertFalse(MentionPolicy.valid(text,2,6,"@علی","first-uid"));
    }
}
