package com.friendbook.social.d1.documents;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Coalesces only global chat-list hints; open messages and calls stay immediate. */
final class ChatPushRefresh {
    static final long BADGE_WINDOW_MS = 500L;
    interface Scheduler { boolean post(Runnable task, long delayMs); }
    private final PendingPushActors pending = new PendingPushActors();
    private final Supplier<String> actor;
    private final Consumer<String> refresh;
    private final Scheduler scheduler;

    ChatPushRefresh(Supplier<String> actor, Consumer<String> refresh, Scheduler scheduler) {
        this.actor = actor;
        this.refresh = refresh;
        this.scheduler = scheduler;
    }

    void hint(String chatId) {
        if (chatId == null || !chatId.matches("[A-Za-z0-9_-]{1,128}")) return;
        refresh.accept("chats/" + chatId + "/messages");
        final String uid = actor.get();
        if (!pending.begin(uid)) return;
        Runnable flush = () -> {
            // Clear first so an event during the D1 read triggers a follow-up.
            pending.finish(uid);
            if (uid.equals(actor.get())) refresh.accept("chats");
        };
        if (!scheduler.post(flush, BADGE_WINDOW_MS)) flush.run();
    }
}
