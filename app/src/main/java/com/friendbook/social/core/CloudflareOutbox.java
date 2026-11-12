package com.friendbook.social.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A small durable outbox for Cloudflare writes.
 *
 * Since this build writes to Cloudflare only, a post written on a train must not disappear because
 * the request failed. Every write that does not come back `true` is parked here (JSON in prefs, per
 * account) and retried in order the next time the app is in the foreground. The queue is deliberately
 * tiny - writes only, newest state wins per document - so it never grows without bound.
 *
 * Nothing here talks to Firebase: the outbox is the app's own storage, and replay goes through
 * {@link CloudflareDocs#write} which authenticates with the Firebase ID token.
 */
public final class CloudflareOutbox {

    private CloudflareOutbox() {}

    private static final String PREFS = "cf_outbox_v1";
    private static final int MAX_ENTRIES = 200;
    private static final Object LOCK = new Object();

    /** One write waiting to reach Cloudflare. */
    public static final class Entry {
        public final String collection;
        public final String id;
        public final JSONObject fields;
        Entry(String collection, String id, JSONObject fields) {
            this.collection = collection;
            this.id = id;
            this.fields = fields;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Account-scoped key: one user's pending writes can never be replayed as another user. */
    private static String key(Context context) {
        String uid = Fb.uid();
        return (uid == null || uid.isEmpty()) ? "anonymous" : uid;
    }

    /** Parks a write. A later write for the same document replaces the earlier one (newest wins). */
    public static void enqueue(Context context, String collection, String id, JSONObject fields) {
        if (context == null || collection == null || id == null || id.isEmpty() || fields == null) return;
        synchronized (LOCK) {
            try {
                List<Entry> entries = read(context);
                entries.removeIf(e -> e.collection.equals(collection) && e.id.equals(id));
                entries.add(new Entry(collection, id, fields));
                while (entries.size() > MAX_ENTRIES) entries.remove(0);
                write(context, entries);
            } catch (Throwable ignored) {
                // A write that cannot be parked is still not a crash: the caller already showed it.
            }
        }
    }

    /** How many writes are waiting (used by the "will sync" message). */
    public static int size(Context context) {
        synchronized (LOCK) {
            return read(context).size();
        }
    }

    /**
     * Replays the queue in order on a background thread. Entries that still fail stay queued; the
     * replay stops at the first failure so ordering is preserved instead of being scrambled.
     */
    public static void flush(Context context) {
        if (context == null || !CloudflareDocs.enabled()) return;
        synchronized (LOCK) {
            final List<Entry> entries = read(context);
            if (entries.isEmpty()) return;
            for (Entry entry : entries) {
                final boolean[] done = {false};
                final boolean[] failed = {false};
                CloudflareDocs.write(entry.collection, entry.id, toMap(entry.fields),
                        () -> done[0] = true,
                        reason -> failed[0] = true);
                // write() is async; wait for the outcome without blocking the main thread.
                long deadline = System.currentTimeMillis() + 20_000L;
                while (!done[0] && !failed[0] && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(60L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                if (!done[0]) return; // keep the rest for the next attempt
                synchronized (LOCK) {
                    List<Entry> remaining = read(context);
                    remaining.removeIf(e -> e.collection.equals(entry.collection) && e.id.equals(entry.id));
                    write(context, remaining);
                }
            }
        }
    }

    private static List<Entry> read(Context context) {
        List<Entry> out = new ArrayList<>();
        try {
            String raw = prefs(context).getString(key(context), "");
            if (raw == null || raw.isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String collection = item.optString("c", "");
                String id = item.optString("id", "");
                JSONObject fields = item.optJSONObject("f");
                if (collection.isEmpty() || id.isEmpty() || fields == null) continue;
                out.add(new Entry(collection, id, fields));
            }
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
        return out;
    }

    private static void write(Context context, List<Entry> entries) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry entry : entries) {
                JSONObject item = new JSONObject();
                item.put("c", entry.collection);
                item.put("id", entry.id);
                item.put("f", entry.fields);
                arr.put(item);
            }
            prefs(context).edit().putString(key(context), arr.toString()).apply();
        } catch (Throwable ignored) {
            // Nothing to do: the queue is best-effort durability, not a database.
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> toMap(JSONObject json) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            map.put(k, json.opt(k));
        }
        return map;
    }
}
