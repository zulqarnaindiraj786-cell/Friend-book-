package com.friendbook.social.d1.documents;

import org.junit.Test;
import static org.junit.Assert.*;

public class PollBudgetTest {
    @Test public void ordinaryListenersNeverPoll() {
        PollBudget budget = new PollBudget();
        for (String path : new String[]{"chats", "chats/room/messages", "notifications", "friend_requests", "posts"}) {
            assertFalse(budget.due(path,true,600_000,true));
            assertFalse(budget.due(path,false,600_000,true));
        }
    }
    @Test public void activeLiveSignalingBacksOffAndStopsInBackground() {
        PollBudget budget=new PollBudget();
        assertTrue(budget.due("lives/room",true,5_000));
        budget.failure();
        assertFalse(budget.due("lives/room",true,29_999));
        assertTrue(budget.due("lives/room",true,30_000));
        assertFalse(budget.due("lives/room",false,600_000));
    }
    @Test public void quietChatFallsBackLessOftenAndNewActivityRestoresFastChecks() {
        PollBudget budget = new PollBudget();
        assertFalse(budget.chatFallbackDue(2_000, true));
        assertTrue(budget.chatFallbackDue(5_000, true));
        for (int i = 0; i < 3; i++) budget.success(false);
        assertFalse(budget.chatFallbackDue(19_999, true));
        assertTrue(budget.chatFallbackDue(20_000, true));
        assertFalse(budget.chatFallbackDue(39_999, false));
        assertTrue(budget.chatFallbackDue(40_000, false));
        budget.success(true);
        assertFalse(budget.chatFallbackDue(2_000, true));
        assertTrue(budget.chatFallbackDue(5_000, true));
        assertTrue(budget.chatFallbackDue(10_000, false));
    }
}
