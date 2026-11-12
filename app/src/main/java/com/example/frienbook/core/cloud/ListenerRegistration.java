package com.example.frienbook.core.cloud;

/**
 * A handle on a live listener. Screens keep one in a field and call remove() in onStop/onDestroy,
 * exactly as they did before; after remove() the poller stops and nothing else is delivered.
 */
public final class ListenerRegistration {

    private final Watcher watcher;
    private boolean removed;

    ListenerRegistration(Watcher watcher) {
        this.watcher = watcher;
    }

    public void remove() {
        if (removed) return;
        removed = true;
        if (watcher != null) watcher.stop();
    }
}
