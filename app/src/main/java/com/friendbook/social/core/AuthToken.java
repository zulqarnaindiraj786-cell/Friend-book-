package com.friendbook.social.core;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One cached Firebase ID token for the whole app.
 *
 * <p><b>Why.</b> Every D1 call asked the SDK for an ID token before it could start its HTTP
 * request. The SDK answers from memory when it can, but each answer is still a Task delivered on
 * the main thread, so a screen that makes four calls queued four Tasks before one byte was sent -
 * and a chat send queued behind a feed refresh. Caching the token removes that hop entirely from
 * every call after the first.
 *
 * <p>A token is a signed JWT with a one-hour life, so caching it for a little under that is safe by
 * construction. The cache is keyed on the user id: signing in as somebody else must never inherit
 * the previous account's token, which is the one way a cache like this becomes a security bug.
 *
 * <p>The public surface is a String, deliberately. A cached hit is handed back directly on the
 * calling thread; only a real refresh touches the SDK.
 */
public final class AuthToken {

    /** Refresh this long before the real expiry, so a request never leaves with a dying token. */
    private static final long SAFETY_MS = 5L * 60_000L;

    /** Assumed life when the SDK does not report an expiry: Firebase's hour, less safety. */
    private static final long DEFAULT_LIFE_MS = 55L * 60_000L;

    private static String uid;
    private static String token;
    private static long expiresAtMs;

    private AuthToken() {
    }

    /** The cached token for this user, or null when it is absent, stale, or for another account. */
    public static synchronized String cached(FirebaseUser user) {
        if (user == null || token == null) return null;
        String id = user.getUid();
        if (id == null || !id.equals(uid)) return null;
        if (System.currentTimeMillis() >= expiresAtMs) return null;
        return token;
    }

    /**
     * Delivers a token to {@code onToken}, or runs {@code onFailure}.
     *
     * <p>A cached hit is delivered on the calling thread, synchronously, which is what makes the
     * common case free. A miss goes to the SDK and comes back on the main thread.
     */
    public static void with(FirebaseUser user, Consumer<String> onToken, Runnable onFailure) {
        if (user == null || onToken == null) {
            if (onFailure != null) onFailure.run();
            return;
        }
        String hit = cached(user);
        if (hit != null) {
            onToken.accept(hit);
            return;
        }
        final String requestUid = user.getUid();
        user.getIdToken(false)
                .addOnSuccessListener(result -> {
                    String fresh = result == null ? null : result.getToken();
                    if (fresh == null || fresh.isEmpty()) {
                        if (onFailure != null) onFailure.run();
                        return;
                    }
                    store(requestUid, fresh, result.getExpirationTimestamp());
                    onToken.accept(fresh);
                })
                .addOnFailureListener(error -> {
                    // A failed refresh still leaves the last known token usable until it expires:
                    // the worker checks the signature, not the age.
                    String last = cached(user);
                    if (last != null) onToken.accept(last);
                    else if (onFailure != null) onFailure.run();
                });
    }

    /** Convenience form for callers with nothing to do when no token can be had. */
    public static void with(FirebaseUser user, Consumer<String> onToken) {
        with(user, onToken, null);
    }

    /** Convenience form using the currently signed-in FirebaseUser. */
    public static void with(Consumer<String> onToken, Runnable onFailure) {
        FirebaseUser user = null;
        try { user = Fb.auth().getCurrentUser(); } catch (Throwable ignored) { }
        with(user, onToken, onFailure);
    }

    /** Blocking form, for code that already runs on a background thread. */
    public static String await(FirebaseUser user, long timeoutMs) throws Exception {
        if (user == null) return null;
        String hit = cached(user);
        if (hit != null) return hit;
        Task<GetTokenResult> task = user.getIdToken(false);
        GetTokenResult result = com.google.android.gms.tasks.Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS);
        if (result == null) return null;
        String fresh = result.getToken();
        if (fresh != null && !fresh.isEmpty()) store(user.getUid(), fresh, result.getExpirationTimestamp());
        return fresh;
    }

    /** Drops the cache. Called when the signed-in account changes or signs out. */
    public static synchronized void clear() {
        uid = null;
        token = null;
        expiresAtMs = 0L;
    }

    private static synchronized void store(String requestUid, String fresh, long expiresAt) {
        token = fresh;
        uid = requestUid;
        long life = DEFAULT_LIFE_MS;
        if (expiresAt > 0) {
            life = expiresAt - System.currentTimeMillis() - SAFETY_MS;
            if (life < 60_000L) life = DEFAULT_LIFE_MS;
        }
        expiresAtMs = System.currentTimeMillis() + life;
    }
}
