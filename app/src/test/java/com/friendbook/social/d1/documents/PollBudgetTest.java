package com.friendbook.social.d1.documents;

import org.junit.Test;
import static org.junit.Assert.*;

public class PollBudgetTest {
    @Test public void idleReadsSlowDownButLiveChatRemainsFast() {
        PollBudget budget = new PollBudget();
        for (int i = 0; i < 10; i++) budget.success(false);
        assertFalse(budget.due("notifications", true, 119_999));
        assertTrue(budget.due("notifications", true, 120_000));
        assertTrue(budget.due("chats", true, 15_000));
        assertFalse(budget.due("chats", false, 19_999));
        assertTrue(budget.due("chats", false, 20_000));
        assertTrue(budget.due("chats/room/messages", true, 2_000));
        budget.success(true);
        assertTrue(budget.due("notifications", true, 15_000));
    }

    @Test public void visibleInboxRefreshIsFasterWithoutIncreasingBackgroundReads() {
        PollBudget budget = new PollBudget();
        assertTrue(budget.due("friend_requests", true, 15_000, true));
        budget.success(false);
        assertFalse(budget.due("notifications", true, 29_999, true));
        assertTrue(budget.due("notifications", true, 30_000, true));
        assertFalse(budget.due("notifications", false, 300_000, true));
    }

    @Test public void errorsDoNotKeepHammeringQuota() {
        PollBudget budget = new PollBudget();
        budget.failure();
        assertFalse(budget.due("chats/room/messages", true, 29_999));
        assertTrue(budget.due("chats/room/messages", true, 30_000));
        for (int i = 0; i < 20; i++) budget.failure();
        assertTrue(budget.due("chats/room/messages", true, 300_000));
        assertFalse(budget.due("chats/room/messages", false, 600_000));
        budget.success(true);
        assertTrue(budget.due("chats/room/messages", true, 2_000));
    }
}
