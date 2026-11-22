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
    @Test public void liveDiscoveryBacksOffButActiveRoomsStayResponsive() {
        PollBudget discovery=new PollBudget();
        assertFalse(discovery.due("lives",true,9_999));
        assertTrue(discovery.due("lives",true,10_000));
        discovery.success(false);
        assertFalse(discovery.due("lives",true,19_999));
        assertTrue(discovery.due("lives",true,20_000));
        discovery.success(false);
        assertFalse(discovery.due("lives",true,19_999));
        assertTrue(discovery.due("lives",true,20_000));
        discovery.success(false);
        assertTrue(discovery.due("lives",true,20_000));
        assertFalse(discovery.due("lives",false,600_000));
        discovery.success(true);
        assertFalse(discovery.due("lives",true,5_000));
        assertTrue(discovery.due("lives",true,10_000));
        PollBudget room=new PollBudget();
        room.success(false);room.success(false);room.success(false);
        assertTrue(room.due("lives/room",true,5_000));
        assertTrue(room.due("lives/room/chat",true,5_000));
    }
    @Test public void activeLiveSignalingBacksOffAndStopsInBackground() {
        PollBudget budget=new PollBudget();
        assertTrue(budget.due("lives/room",true,5_000));
        budget.failure();
        assertFalse(budget.due("lives/room",true,29_999));
        assertTrue(budget.due("lives/room",true,30_000));
        assertFalse(budget.due("lives/room",false,600_000));
    }
    @Test public void chatMessagesNeverPollEvenInForeground() {
        PollBudget budget = new PollBudget();
        assertFalse(budget.due("chats/c1/messages", true, 600_000));
        budget.failure();
        assertFalse(budget.due("chats/c1/messages", true, 600_000));
    }
}
