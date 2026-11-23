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
    /** Live discovery and an open room are push/on-demand only: no interval. */
    @Test public void liveRoomsNeverPoll() {
        PollBudget room = new PollBudget();
        for (String path : new String[]{"lives", "lives/room", "lives/room/chat"}) {
            assertFalse(path, room.due(path, true, 5_000));
            assertFalse(path, room.due(path, true, 600_000));
            room.success(false);
            assertFalse(path, room.due(path, true, 600_000));
        }
    }

    /** A failure may not re-arm any timer either. */
    @Test public void failuresNeverArmLivePolling() {
        PollBudget budget = new PollBudget();
        budget.failure();
        assertFalse(budget.due("lives/room", true, 30_000));
        assertFalse(budget.due("lives/room", true, 600_000));
        assertFalse(budget.due("lives/room", false, 600_000));
    }

    @Test public void chatMessagesNeverPollEvenInForeground() {
        PollBudget budget = new PollBudget();
        assertFalse(budget.due("chats/c1/messages", true, 600_000));
        budget.failure();
        assertFalse(budget.due("chats/c1/messages", true, 600_000));
    }
}
