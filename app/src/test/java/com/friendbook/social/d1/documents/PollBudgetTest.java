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
}
