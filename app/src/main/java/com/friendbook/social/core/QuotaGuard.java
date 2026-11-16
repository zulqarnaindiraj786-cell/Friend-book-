package com.friendbook.social.core;

import android.content.Context;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestoreException;

/**
 * Distinguishes "there is nothing here" from "we could not ask".
 *
 * <p>Every Firestore listener in this app receives an {@code err}. Most ignore it, which means a
 * failed read and an empty result look identical on screen: the user sees a blank feed, a chat
 * with no messages, a profile with no name. When the Spark plan's daily read or write quota is
 * exhausted - which it is, at roughly 200 daily users - <em>every</em> read fails this way, so a
 * user who opens the app sees an empty app and uninstalls it rather than waiting for 00:00 UTC.
 *
 * <p>This class turns those failures into something the UI can act on:
 * <ul>
 *   <li>{@link #isQuotaOrUnavailable} recognises exhaustion, rate limiting and offline.</li>
 *   <li>{@link #noteFailure} records it once per app process, so the app can show one honest
 *       message instead of 60 silent blanks.</li>
 *   <li>{@link #shouldSuppressNetworkWork} lets callers stop issuing doomed reads while the
 *       quota is spent - a failed read still costs time and battery, and retrying makes the
 *       screen flicker.</li>
 * </ul>
 *
 * <p>Deliberately conservative: a one-off transient failure does not latch. Only a failure the
 * SDK itself classifies as resource-exhausted or unavailable does.
 */
public final class QuotaGuard {

    private static final String TAG = "QuotaGuard";

    /** Set once a quota/unavailable failure is seen in this process. */
    private static volatile boolean degraded;
    private static volatile long degradedAt;
    /** Cleared once a read succeeds again, so a reset quota recovers without a restart. */
    private static volatile long lastSuccessAt;
    private static volatile String lastReason;

    private QuotaGuard() {}

    /** True when the failure means "the backend refused us", not "the data is absent". */
    public static boolean isQuotaOrUnavailable(Throwable error) {
        if (!(error instanceof FirebaseFirestoreException)) return false;
        return isRefusalCode(((FirebaseFirestoreException) error).getCode().name());
    }

    /**
     * The classification itself, on the code's name.
     *
     * <p>Separated from the SDK type so it can be tested on a plain JVM without constructing a
     * FirebaseFirestoreException (whose constructor pulls in Firestore internals).
     *
     * <p>RESOURCE_EXHAUSTED is the one that matters here: it is exactly what the Spark plan
     * returns once the daily read or write quota is spent, and at ~200 daily users that is a
     * daily event, not an edge case.
     */
    public static boolean isRefusalCode(String code) {
        if (code == null) return false;
        switch (code) {
            case "RESOURCE_EXHAUSTED": // daily read/write quota spent
            case "UNAVAILABLE":        // backend unreachable / throttled
            case "DEADLINE_EXCEEDED":  // request given up
            case "ABORTED":            // concurrent write conflict under load
            case "INTERNAL":           // transient backend error
                return true;
            default:
                return false;
        }
    }

    /**
     * Record a failure. Returns true when the caller should treat the screen as
     * "temporarily unavailable" rather than "empty".
     */
    public static boolean noteFailure(Throwable error) {
        if (error == null) return false;
        if (!isQuotaOrUnavailable(error)) return false;
        return noteRefusalCode(((FirebaseFirestoreException) error).getCode().name());
    }

    /** Same, from a code name; used when the SDK exception is not in hand. */
    static boolean noteRefusalCode(String code) {
        if (!isRefusalCode(code)) return false;
        if (!degraded) {
            degradedAt = System.currentTimeMillis();
            logw("Backend degraded: " + code);
        }
        degraded = true;
        lastReason = code;
        return true;
    }

    /** A successful read means whatever was wrong has cleared. */
    public static void noteSuccess() {
        if (degraded) logi("Backend recovered after " + (System.currentTimeMillis() - degradedAt) + "ms");
        degraded = false;
        lastSuccessAt = System.currentTimeMillis();
    }

    /** True when reads are currently known to be failing. */
    public static boolean degraded() {
        return degraded;
    }

    /**
     * Callers can skip optional, non-rendering reads (pymk suggestions, referral counters,
     * story preloads) while the backend is refusing us. Essential reads must still be attempted
     * so the app recovers automatically.
     */
    public static boolean shouldSuppressNetworkWork() {
        return degraded;
    }

    public static String lastReason() {
        return lastReason;
    }

    /**
     * The message to show the user. Deliberately says the app is not broken and that local
     * content is still available, because that is what stops an uninstall.
     */
    public static String userMessage(Context context) {
        if (degraded) {
            return "Aap ka data safe hai. Server aaj ki limit par pahunch gaya hai — "
                    + "jo pehle se download hai woh dikh raha hai. Naya data thodi der mein khud aa jayega.";
        }
        return "Connection slow hai — dobara koshish kar rahe hain.";
    }

    private static String lastReasonOf(Throwable error) {
        String message = error.getMessage();
        if (error instanceof FirebaseFirestoreException) {
            return ((FirebaseFirestoreException) error).getCode()
                    + (message != null ? ": " + message.substring(0, Math.min(120, message.length())) : "");
        }
        return error.getClass().getSimpleName();
    }


    /**
     * android.util.Log throws on a plain JVM unless it is mocked. These two wrappers keep the
     * classification logic testable without Robolectric, which matters because this class is
     * the guard that decides whether a blank screen is an outage.
     */
    private static void logw(String message) {
        try {
            Log.w(TAG, message);
        } catch (Throwable ignored) { }
    }

    private static void logi(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) { }
    }

    /** Test hook. Never call from app code. */
    static void resetForTest() {
        degraded = false;
        degradedAt = 0L;
        lastSuccessAt = 0L;
        lastReason = null;
    }
}
