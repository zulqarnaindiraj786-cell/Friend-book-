package com.friendbook.social.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AdRequestBudgetTest {
    @Test public void threeOpeningRequestsAndOneReplacementPerImpression() {
        AdRequestBudget budget = new AdRequestBudget();
        assertTrue(budget.tryRequest(true));
        assertTrue(budget.tryRequest(false));
        assertTrue(budget.tryRequest(true));
        for (int i = 0; i < 100; i++) {
            assertFalse(budget.tryRequest(true));
            assertFalse(budget.tryRequest(false));
        }
        budget.onImpression(false);
        assertFalse(budget.tryRequest(true));
        assertTrue(budget.tryRequest(false));
        assertFalse(budget.tryRequest(false));
    }
}
