package com.friendbook.social.chat;

import com.friendbook.social.models.Models;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MessageRenderStateTest {
    private Models.Message row(String id, String sender) {
        Models.Message m = new Models.Message();
        m.id = id; m.senderId = sender; m.type = "text"; m.text = id; m.createdAt = 1000;
        return m;
    }
    @Test public void unrelatedReceiptDoesNotRebindAllRows() {
        Models.Message a = row("a", "peer"), b = row("b", "me"), c = row("c", "peer");
        List<Models.Message> list = Arrays.asList(a, b, c);
        List<List<Object>> before = MessageRenderState.keys(list, "me");
        b.isDelivered = true;
        List<List<Object>> after = MessageRenderState.keys(list, "me");
        assertEquals(before.get(0), after.get(0));
        assertNotEquals(before.get(1), after.get(1));
        assertEquals(before.get(2), after.get(2));
    }
    @Test public void appendInvalidatesPreviousGroupingAndLastMineMarker() {
        Models.Message a = row("a", "me");
        List<List<Object>> before = MessageRenderState.keys(Collections.singletonList(a), "me");
        List<List<Object>> after = MessageRenderState.keys(Arrays.asList(a, row("b", "me")), "me");
        assertNotEquals(before.get(0), after.get(0));
    }
    @Test public void movingSeenMarkerInvalidatesOldAndNewRows() {
        Models.Message a = row("a", "me"), b = row("b", "me"); a.isSeen = true;
        List<Models.Message> list = Arrays.asList(a, b);
        List<List<Object>> before = MessageRenderState.keys(list, "me");
        b.isSeen = true;
        List<List<Object>> after = MessageRenderState.keys(list, "me");
        assertNotEquals(before.get(0), after.get(0)); assertNotEquals(before.get(1), after.get(1));
    }
    @Test public void reactionMutationCannotModifyOldRenderKey() {
        Models.Message a = row("a", "peer");
        List<List<Object>> before = MessageRenderState.keys(Collections.singletonList(a), "me");
        a.reactions.put("me", "love");
        assertNotEquals(before, MessageRenderState.keys(Collections.singletonList(a), "me"));
    }
}
