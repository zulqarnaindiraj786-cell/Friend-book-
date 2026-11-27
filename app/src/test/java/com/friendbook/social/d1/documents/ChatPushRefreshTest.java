package com.friendbook.social.d1.documents;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ChatPushRefreshTest {
    @Test public void messageHintsStayImmediateAndBadgeBurstHasFixedWindow() {
        List<String> paths = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        ChatPushRefresh refresh = new ChatPushRefresh(() -> "alice", paths::add, (task, delay) -> {
            assertEquals(500L, delay); tasks.add(task); return true;
        });
        refresh.hint("room1"); refresh.hint("room2"); refresh.hint("room1");
        assertEquals(java.util.Arrays.asList("chats/room1/messages", "chats/room2/messages", "chats/room1/messages"), paths);
        assertEquals(1, tasks.size()); // later hints do not reset the deadline
        tasks.remove(0).run();
        assertEquals("chats", paths.get(3));
        refresh.hint("room1");
        assertEquals(1, tasks.size());
        tasks.remove(0).run();
        assertEquals("chats", paths.get(5));
    }

    @Test public void accountSwitchIsGuardedAndNewAccountHasOwnWindow() {
        AtomicReference<String> actor = new AtomicReference<>("alice");
        List<String> paths = new ArrayList<>(); List<Runnable> tasks = new ArrayList<>();
        ChatPushRefresh refresh = new ChatPushRefresh(actor::get, paths::add, (task, delay) -> { tasks.add(task); return true; });
        refresh.hint("a"); actor.set("bob"); refresh.hint("b");
        assertEquals(2, tasks.size());
        tasks.get(0).run(); assertFalse(paths.contains("chats"));
        tasks.get(1).run(); assertEquals("chats", paths.get(2));
    }

    @Test public void eventDuringBadgeRefreshSchedulesFollowUp() {
        List<Runnable> tasks = new ArrayList<>(); List<String> paths = new ArrayList<>();
        ChatPushRefresh[] ref = new ChatPushRefresh[1];
        ref[0] = new ChatPushRefresh(() -> "alice", path -> {
            paths.add(path);
            if (path.equals("chats") && paths.stream().filter("chats"::equals).count() == 1) ref[0].hint("room");
        }, (task, delay) -> { tasks.add(task); return true; });
        ref[0].hint("room"); tasks.remove(0).run();
        assertEquals(1, tasks.size()); tasks.remove(0).run();
        assertEquals(2, paths.stream().filter("chats"::equals).count());
    }

    @Test public void invalidIdsAndRejectedSchedulingAreSafe() {
        List<String> paths = new ArrayList<>();
        ChatPushRefresh refresh = new ChatPushRefresh(() -> "alice", paths::add, (task, delay) -> false);
        refresh.hint(null); refresh.hint("../bad"); assertTrue(paths.isEmpty());
        refresh.hint("room"); refresh.hint("room");
        assertEquals(java.util.Arrays.asList("chats/room/messages", "chats", "chats/room/messages", "chats"), paths);
    }
}
