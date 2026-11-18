package com.google.firebase.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import java.util.Map;

public class OnDisconnect {
    public OnDisconnect() {}

    @NonNull
    public Task<Void> setValue(@Nullable Object value) {
        return Tasks.forResult(null);
    }

    @NonNull
    public Task<Void> updateChildren(@NonNull Map<String, Object> update) {
        return Tasks.forResult(null);
    }

    @NonNull
    public Task<Void> removeValue() {
        return Tasks.forResult(null);
    }

    @NonNull
    public Task<Void> cancel() {
        return Tasks.forResult(null);
    }
}
