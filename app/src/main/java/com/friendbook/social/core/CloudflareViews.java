package com.friendbook.social.core;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import com.friendbook.social.BuildConfig;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Real view counters served by the Cloudflare Worker on `/v1/views`.
 *
 * Firebase is used for AUTHENTICATION ONLY here: the Worker verifies the Firebase ID token and owns
 * the counter. A view count is never written to Firestore by this client.
 *
 * The Worker counts in its own memory plus one Workers KV aggregate key and writes D1 at most once
 * per hour per resource, so counting or reading a view costs ZERO D1 row reads. Nothing changes for
 * this client: it still POSTs one real play and reads real totals, and every number it shows comes
 * from the Worker — never a local guess, and never lower than one the user already saw.
 *
 * Counting rule (product decision): ONE view per user per resource, forever. One second of real
 * watching is enough to count it; watching the whole video, looping it or coming back next month
 * adds nothing. The Worker de-dupes durably per (resource, uid) with a write-only check, so the rule
 * costs zero D1 row reads, and every stored number still comes from a real play event.
 *
 * The Home Feed calls none of this: it shows no view number and issues no views request, so loading
 * or scrolling the feed costs no D1 read, no D1 write, no KV read, no KV write and no HTTP call.
 * Requests are fire-and-forget on a low-priority background thread: they never block scrolling and
 * a failure is silent (no toast, no retry loop, no synthesized fallback number).
 */
public final class CloudflareViews {

    private CloudflareViews() {}

    public static final String POSTS = "posts";
    public static final String REELS = "reels";

    private static final String BASE =
            "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/views";
    private static final long READ_CACHE_TTL_MS = 60_000L;
    private static final int MAX_BATCH_IDS = 50;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cf-views");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * In-flight guard only: never POST the same resource twice at the very same moment. Re-watches
     * are refused by the Worker itself — one view per user per resource, forever.
     */
    private static final Set<String> POSTING = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> IN_FLIGHT = Collections.synchronizedSet(new HashSet<String>());
    private static final LruCache<String, Integer> COUNTS = new LruCache<>(512);
    private static final LruCache<String, Long> COUNTS_AT = new LruCache<>(512);

    /** Views are stored in Cloudflare, so this client is always on for signed-in users. */
    public static boolean enabled() {
        return BuildConfig.CLOUDFLARE_VIEWS_ENABLED;
    }

    public static boolean validCollection(String collection) {
        return POSTS.equals(collection) || REELS.equals(collection);
    }

    private static boolean validId(String id) {
        // A comma is rejected too: it separates ids in the batched `ids` query parameter.
        return id != null && !id.isEmpty() && id.length() <= 128 && id.indexOf('/') < 0
                && id.indexOf(',') < 0 && !"..".equals(id) && !".".equals(id);
    }

    private static String resource(String collection, String id) {
        return collection + "/" + id;
    }

    /** Last Cloudflare total seen this session, or null when nothing has been read yet. */
    public static Integer cached(String collection, String id) {
        if (!validCollection(collection) || !validId(id)) return null;
        return COUNTS.get(resource(collection, id));
    }

    /**
     * Caches a total read from the Worker. View counts only ever grow, so a resource this Worker
     * isolate has not counted yet (it reports 0) can never drag a number the user already saw down.
     */
    private static void remember(String collection, String id, int views) {
        if (views < 0) return;
        String key = resource(collection, id);
        Integer known = COUNTS.get(key);
        COUNTS.put(key, known != null ? Math.max(known.intValue(), views) : views);
        COUNTS_AT.put(key, SystemClock.elapsedRealtime());
    }

    private interface TokenWork {
        void run(String token);
    }

    /**
     * Hands a fresh Firebase ID token to `work` on the background thread. When no token is
     * available (signed out, account switch, saturated queue) `onUnavailable` runs instead, so
     * callers can release their in-flight/session guards — nothing is ever faked or retried.
     */
    private static void withToken(final TokenWork work, final Runnable onUnavailable) {
        if (work == null) return;
        try {
            final FirebaseUser user = Fb.auth().getCurrentUser();
            final String actor = Fb.uid();
            if (user == null || actor == null || actor.isEmpty()) {
                if (onUnavailable != null) onUnavailable.run();
                return;
            }
            user.getIdToken(false)
                    .addOnSuccessListener(res -> {
                        final String token = res != null ? res.getToken() : null;
                        if (token == null || token.isEmpty()) {
                            if (onUnavailable != null) onUnavailable.run();
                            return;
                        }
                        try {
                            IO.execute(() -> {
                                try {
                                    // Never count or read views for a different account.
                                    if (!actor.equals(Fb.uid())) {
                                        if (onUnavailable != null) MAIN.post(onUnavailable);
                                        return;
                                    }
                                    work.run(token);
                                } catch (Throwable ignored) { }
                            });
                        } catch (Throwable rejected) {
                            if (onUnavailable != null) onUnavailable.run();
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (onUnavailable != null) onUnavailable.run();
                    });
        } catch (Throwable ignored) {
            if (onUnavailable != null) onUnavailable.run();
        }
    }

    /** Counts one real view in Cloudflare (one second of watching = one view). */
    public static void recordView(String collection, String id) {
        recordView(collection, id, null);
    }

    /**
     * Counts one real view in Cloudflare and reports the authoritative total back on the main
     * thread. The callback is skipped when the Worker is unreachable — the count is never guessed.
     *
     * Calling this again for the same resource does NOT count again: the Worker keeps one view per
     * user per resource, and `ReelsFragment` does not even re-send a Reel it already sent in this
     * app session. Two calls racing on the same millisecond are collapsed by the in-flight guard, so
     * no number can ever be invented or double counted.
     */
    public static void recordView(final String collection, final String id, final Consumer<Integer> onCounted) {
        if (!enabled() || !validCollection(collection) || !validId(id)) return;
        final String key = resource(collection, id);
        if (!POSTING.add(key)) {
            final Integer known = COUNTS.get(key);
            if (known != null && onCounted != null) MAIN.post(() -> onCounted.accept(known));
            return;
        }
        withToken(token -> {
            try {
                URL url = new URL(BASE + "/" + Uri.encode(collection) + "/" + Uri.encode(id));
                JSONObject data = D1HttpTransport.send(url, "POST", token, null);
                int views = data != null ? data.optInt("views", -1) : -1;
                remember(collection, id, views);
                if (views >= 0 && onCounted != null) {
                    final int total = views;
                    MAIN.post(() -> onCounted.accept(total));
                }
            } catch (Throwable ignored) {
                // Offline / Worker busy: this view is simply not counted, never faked and never
                // retried in a loop. The next real watch posts again.
            } finally {
                POSTING.remove(key);
            }
        }, () -> POSTING.remove(key));
    }

    /**
     * Reads the real Cloudflare totals for a page of ids in ONE request (max 50 ids). Cached for
     * 60s so scrolling never repeats a read; the callback always runs on the main thread and always
     * receives a map (possibly holding only cached values when the Worker is unreachable).
     */
    public static void fetchViews(final String collection, List<String> ids,
                                  final Consumer<Map<String, Integer>> callback) {
        if (callback == null) return;
        if (!enabled() || !validCollection(collection) || ids == null || ids.isEmpty()) {
            MAIN.post(() -> callback.accept(new HashMap<String, Integer>()));
            return;
        }
        final Map<String, Integer> known = new HashMap<>();
        final List<String> wanted = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        long now = SystemClock.elapsedRealtime();
        for (String id : ids) {
            if (!validId(id) || !seen.add(id)) continue;
            String key = resource(collection, id);
            Integer cachedCount = COUNTS.get(key);
            Long cachedAt = COUNTS_AT.get(key);
            if (cachedCount != null && cachedAt != null && (now - cachedAt) < READ_CACHE_TTL_MS) {
                known.put(id, cachedCount);
            } else if (wanted.size() < MAX_BATCH_IDS) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            MAIN.post(() -> callback.accept(known));
            return;
        }
        final StringBuilder joined = new StringBuilder();
        for (String id : wanted) {
            if (joined.length() > 0) joined.append(',');
            joined.append(Uri.encode(id));
        }
        final String flightKey = collection + "|" + joined;
        if (!IN_FLIGHT.add(flightKey)) {
            MAIN.post(() -> callback.accept(known));
            return;
        }
        withToken(token -> {
            Map<String, Integer> merged = new HashMap<>(known);
            try {
                URL url = new URL(BASE + "?collection=" + Uri.encode(collection) + "&ids=" + joined);
                JSONObject data = D1HttpTransport.send(url, "GET", token, null);
                JSONObject views = data != null ? data.optJSONObject("views") : null;
                if (views != null) {
                    Iterator<String> keys = views.keys();
                    while (keys.hasNext()) {
                        String id = keys.next();
                        if (!validId(id)) continue;
                        int value = views.optInt(id, -1);
                        if (value < 0) continue;
                        remember(collection, id, value);
                        merged.put(id, value);
                    }
                }
            } catch (Throwable ignored) {
                // Unreachable Worker: hand back whatever is already known. No number is invented.
            } finally {
                IN_FLIGHT.remove(flightKey);
            }
            final Map<String, Integer> result = merged;
            MAIN.post(() -> callback.accept(result));
        }, () -> {
            IN_FLIGHT.remove(flightKey);
            MAIN.post(() -> callback.accept(known));
        });
    }
}
