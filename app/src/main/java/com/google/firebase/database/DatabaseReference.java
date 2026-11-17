package com.google.firebase.database;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

/** Minimal shim for DatabaseReference. */
public class DatabaseReference {
    private final String path;

    DatabaseReference(String path) { this.path = path == null ? "" : path; }

    public DatabaseReference child(String pathString) {
        return new DatabaseReference(path + "/" + pathString);
    }

    public Task<Void> setValue(Object value) { return Tasks.forResult(null); }
    public Task<Void> removeValue() { return Tasks.forResult(null); }
    public OnDisconnect onDisconnect() { return new OnDisconnect(); }
    public ValueEventListener addValueEventListener(ValueEventListener listener) { return listener; }
    public void removeEventListener(ValueEventListener listener) { }
    public Task<DataSnapshot> get() { return Tasks.forResult(new DataSnapshot()); }

    public static class OnDisconnect {
        public Task<Void> removeValue() { return Tasks.forResult(null); }
        public Task<Void> setValue(Object value) { return Tasks.forResult(null); }
    }
}
