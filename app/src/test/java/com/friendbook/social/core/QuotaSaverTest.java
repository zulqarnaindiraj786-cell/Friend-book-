package com.friendbook.social.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuotaSaverTest {
    @Test public void automaticPagesUseConservativeDefaultAndManualRefreshIsUnchanged(){
        assertTrue(QuotaSaver.enabled());
        assertEquals(15*60_000L,QuotaSaver.automaticPageMaxAge(60_000L));
        QuotaSaver.setEnabled(false);
        assertEquals(60_000L,QuotaSaver.automaticPageMaxAge(60_000L));
        QuotaSaver.setEnabled(true);
        assertEquals(15*60_000L,QuotaSaver.automaticPageMaxAge(60_000L));
        assertEquals(15*60_000L,QuotaSaver.automaticPageMaxAge(5*60_000L));
        assertEquals(20*60_000L,QuotaSaver.automaticPageMaxAge(20*60_000L));
        assertEquals(0L,QuotaSaver.automaticPageMaxAge(0L));
        assertEquals(Long.MAX_VALUE,QuotaSaver.automaticPageMaxAge(Long.MAX_VALUE));
        QuotaSaver.setEnabled(true);
    }
}
