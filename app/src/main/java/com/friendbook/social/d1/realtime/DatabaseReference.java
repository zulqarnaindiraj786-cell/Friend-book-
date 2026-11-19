package com.friendbook.social.d1.realtime;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

/** Minimal shim for DatabaseReference. */
public class DatabaseReference {
    private final String path;

    DatabaseReference(String path) { this.path = path == null ? "" : path; }

    public DatabaseReference child(String pathString) {
        return new DatabaseReference(path + "/" + pathString);
    }

    private static <T> Task<T> unavailable() {
        return Tasks.forException(new IllegalStateException("Realtime media transport unavailable"));
    }

    // This compatibility class does not connect to a realtime service. Never
    // acknowledge writes or return an empty snapshot as though they succeeded.
    public Task<Void> setValue(Object value) { return unavailable(); }
    public Task<Void> updateChildren(java.util.Map<String, Object> update) { return unavailable(); }
    public Task<Void> removeValue() { return unavailable(); }
    public OnDisconnect onDisconnect() { return new OnDisconnect(); }
    public ValueEventListener addValueEventListener(ValueEventListener listener) {
        if (listener != null) new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> listener.onCancelled(new DatabaseError()));
        return listener;
    }
    public void removeEventListener(ValueEventListener listener) { }
    public Task<DataSnapshot> get() { return unavailable(); }

    public static class OnDisconnect {
        public Task<Void> removeValue() { return unavailable(); }
        public Task<Void> setValue(Object value) { return unavailable(); }
    }
}
