package com.friendbook.social.chat;

import com.friendbook.social.models.Models;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class PendingMessagesTest {
    private static Models.Message message(String id,String text) {
        Models.Message row=new Models.Message();row.id=id;row.text=text;return row;
    }
    @Test public void staleSnapshotAndHistoryKeepPendingThenServerWins() {
        PendingMessages pending=new PendingMessages();
        pending.add(message("a","sending"));pending.add(message("b","second"));
        Map<String,Models.Message> stale=new HashMap<>();stale.put("old",message("old","history"));
        pending.mergeInto(stale);assertEquals(3,stale.size());assertEquals("sending",stale.get("a").text);
        Map<String,Models.Message> confirmed=new HashMap<>();confirmed.put("a",message("a","confirmed"));
        pending.mergeInto(confirmed);assertEquals("confirmed",confirmed.get("a").text);assertTrue(confirmed.containsKey("b"));
        Map<String,Models.Message> later=new HashMap<>();pending.mergeInto(later);
        assertFalse(later.containsKey("a"));assertTrue(later.containsKey("b"));
        pending.fail("b");later.clear();pending.mergeInto(later);assertTrue(later.isEmpty());
    }
    @Test public void clearPreventsCrossAccountReuse() {
        PendingMessages pending=new PendingMessages();pending.add(message("private","x"));pending.clear();
        Map<String,Models.Message> next=new HashMap<>();pending.mergeInto(next);assertTrue(next.isEmpty());
    }
}
