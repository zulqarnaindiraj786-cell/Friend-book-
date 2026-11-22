package com.friendbook.social.d1.documents;

import org.junit.Test;
import java.util.Arrays;
import java.util.LinkedHashSet;
import static org.junit.Assert.*;

public class CommittedInvalidationsTest {
    @Test public void groupsDocumentsByCollectionAfterOneAtomicCommit() {
        CommittedInvalidations batch = new CommittedInvalidations();
        for (int i = 0; i < 32; i++) batch.add("notifications/" + i);
        batch.add("notifications/0");
        batch.add("chats/room");
        batch.add("chats/room/messages/one");
        batch.add("chats/room/messages/two");
        batch.add(null);
        batch.add("");
        assertEquals(35, batch.documents().size());
        assertEquals(new LinkedHashSet<>(Arrays.asList("notifications", "chats", "chats/room/messages")), batch.collections());
        assertTrue(batch.documents().contains("notifications/31"));
        assertTrue(batch.documents().contains("chats/room/messages/two"));
    }

    @Test public void separateCommitsAreNotCoalescedOrLost() {
        CommittedInvalidations first = new CommittedInvalidations();
        CommittedInvalidations second = new CommittedInvalidations();
        first.add("notifications/one");
        second.add("notifications/one");
        assertEquals(1, first.collections().size());
        assertEquals(1, second.collections().size());
        try {
            first.collections().clear();
            fail("callers cannot mutate the plan");
        } catch (UnsupportedOperationException expected) { }
    }
}
