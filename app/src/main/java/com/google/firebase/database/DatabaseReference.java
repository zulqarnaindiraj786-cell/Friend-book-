package com.google.firebase.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.firebase.firestore.CfWorkerBridge;
import org.json.JSONObject;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseReference {

    private static final class RtdbSub {
        final String path;
        final String key;
        final ValueEventListener listener;
        final boolean singleShot;
        volatile boolean removed;
        volatile boolean deliveredOnce;
        volatile String lastRaw;
        volatile long lastPolledAt;

        RtdbSub(String path, String key, ValueEventListener listener, boolean singleShot) {
            this.path = path;
            this.key = key;
            this.listener = listener;
            this.singleShot = singleShot;
        }
    }

    private static final CopyOnWriteArrayList<RtdbSub> SUBS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean POLLER_STARTED = new AtomicBoolean(false);

    private final String path;

    public DatabaseReference(String path) {
        this.path = clean(path);
    }

    private static String clean(String p) {
        if (p == null) return "";
        String s = p.trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    @NonNull
    public DatabaseReference child(@NonNull String pathString) {
        String c = clean(pathString);
        if (path.isEmpty()) return new DatabaseReference(c);
        if (c.isEmpty()) return this;
        return new DatabaseReference(path + "/" + c);
    }

    @Nullable
    public String getKey() {
        if (path.isEmpty()) return null;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    @NonNull
    public String getPath() {
        return "/" + path;
    }

    @NonNull
    public OnDisconnect onDisconnect() {
        return new OnDisconnect();
    }

    private static Object preprocessServerValues(Object v) {
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.size() == 1 && "timestamp".equals(m.get(".sv"))) {
                return System.currentTimeMillis();
            }
            java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    copy.put(String.valueOf(e.getKey()), preprocessServerValues(e.getValue()));
                }
            }
            return copy;
        }
        return v;
    }

    @NonNull
    public Task<Void> setValue(@Nullable Object value) {
        if (value == null) return removeValue();
        TaskCompletionSource<Void> tcs = new TaskCompletionSource<>();
        final Object resolved = preprocessServerValues(value);
        CfWorkerBridge.IO_POOL.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("value", CfWorkerBridge.toJsonValue(resolved));
                CfWorkerBridge.requestJson("PUT", "/v1/rtdb/" + path, body);
                notifyLocalChange(path);
                CfWorkerBridge.postMain(() -> tcs.trySetResult(null));
            } catch (Exception e) {
                CfWorkerBridge.postMain(() -> tcs.trySetException(e));
            }
        });
        return tcs.getTask();
    }

    @NonNull
    public Task<Void> updateChildren(@NonNull Map<String, Object> update) {
        TaskCompletionSource<Void> tcs = new TaskCompletionSource<>();
        final Object resolved = preprocessServerValues(update);
        CfWorkerBridge.IO_POOL.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("value", CfWorkerBridge.toJsonValue(resolved));
                CfWorkerBridge.requestJson("PATCH", "/v1/rtdb/" + path, body);
                notifyLocalChange(path);
                CfWorkerBridge.postMain(() -> tcs.trySetResult(null));
            } catch (Exception e) {
                CfWorkerBridge.postMain(() -> tcs.trySetException(e));
            }
        });
        return tcs.getTask();
    }

    @NonNull
    public Task<Void> removeValue() {
        TaskCompletionSource<Void> tcs = new TaskCompletionSource<>();
        CfWorkerBridge.IO_POOL.execute(() -> {
            try {
                CfWorkerBridge.requestJson("DELETE", "/v1/rtdb/" + path, null);
                notifyLocalChange(path);
                CfWorkerBridge.postMain(() -> tcs.trySetResult(null));
            } catch (Exception e) {
                CfWorkerBridge.postMain(() -> tcs.trySetException(e));
            }
        });
        return tcs.getTask();
    }

    @NonNull
    public ValueEventListener addValueEventListener(@NonNull ValueEventListener listener) {
        if (".info/serverTimeOffset".equals(path)) {
            CfWorkerBridge.postMain(() -> listener.onDataChange(new DataSnapshot("serverTimeOffset", 0L)));
            return listener;
        }
        if (".info/connected".equals(path)) {
            CfWorkerBridge.postMain(() -> listener.onDataChange(new DataSnapshot("connected", Boolean.TRUE)));
            return listener;
        }
        ensurePoller();
        RtdbSub sub = new RtdbSub(path, getKey(), listener, false);
        SUBS.add(sub);
        CfWorkerBridge.IO_POOL.execute(() -> pollSub(sub, true));
        return listener;
    }

    public void addListenerForSingleValueEvent(@NonNull ValueEventListener listener) {
        if (".info/serverTimeOffset".equals(path)) {
            CfWorkerBridge.postMain(() -> listener.onDataChange(new DataSnapshot("serverTimeOffset", 0L)));
            return;
        }
        if (".info/connected".equals(path)) {
            CfWorkerBridge.postMain(() -> listener.onDataChange(new DataSnapshot("connected", Boolean.TRUE)));
            return;
        }
        RtdbSub sub = new RtdbSub(path, getKey(), listener, true);
        CfWorkerBridge.IO_POOL.execute(() -> pollSub(sub, true));
    }

    public void removeEventListener(@NonNull ValueEventListener listener) {
        for (RtdbSub sub : SUBS) {
            if (sub.listener == listener) {
                sub.removed = true;
                SUBS.remove(sub);
            }
        }
    }

    private static void notifyLocalChange(String changedPath) {
        for (RtdbSub sub : SUBS) {
            if (sub.removed) continue;
            if (changedPath.startsWith(sub.path) || sub.path.startsWith(changedPath)) {
                CfWorkerBridge.IO_POOL.execute(() -> pollSub(sub, false));
            }
        }
    }

    private static void pollSub(RtdbSub sub, boolean force) {
        if (sub.removed) return;
        sub.lastPolledAt = System.currentTimeMillis();
        try {
            JSONObject res = CfWorkerBridge.requestJson("GET", "/v1/rtdb/" + sub.path, null);
            Object rawVal = res.has("value") ? res.get("value") : JSONObject.NULL;
            String fp = String.valueOf(rawVal);
            if (!force && sub.deliveredOnce && Objects.equals(fp, sub.lastRaw)) {
                return;
            }
            sub.lastRaw = fp;
            sub.deliveredOnce = true;
            Object plain = CfWorkerBridge.fromJsonValue(rawVal);
            DataSnapshot snap = new DataSnapshot(sub.key, plain);
            if (sub.singleShot) sub.removed = true;
            CfWorkerBridge.postMain(() -> {
                if (!sub.removed || sub.singleShot) {
                    sub.listener.onDataChange(snap);
                }
            });
        } catch (Throwable ignored) {}
    }

    private static long intervalFor(String path) {
        if (path.startsWith("call_streams") || path.startsWith("live_streams") || path.startsWith("pk_streams")) {
            return 700L;
        }
        return 3_000L;
    }

    private static void ensurePoller() {
        if (!POLLER_STARTED.compareAndSet(false, true)) return;
        CfWorkerBridge.SCHEDULER.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            for (RtdbSub sub : SUBS) {
                if (sub.removed) {
                    SUBS.remove(sub);
                    continue;
                }
                if (now - sub.lastPolledAt >= intervalFor(sub.path)) {
                    CfWorkerBridge.IO_POOL.execute(() -> pollSub(sub, false));
                }
            }
        }, 1_000L, 650L, TimeUnit.MILLISECONDS);
    }
}
