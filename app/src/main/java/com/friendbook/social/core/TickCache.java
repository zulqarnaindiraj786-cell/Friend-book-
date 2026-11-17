package com.friendbook.social.core;

import android.widget.ImageView;

import com.friendbook.social.models.Models;
import com.google.firebase.firestore.Source;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ⚡ Zero-lag session cache of uid → tickLevel.
 * Reads RAM cache (`FastCache` / `CACHE`) in 0ms, then local Firestore disk cache (`Source.CACHE`),
 * touching network only once per unknown user.
 */
public final class TickCache {
    private TickCache() { }

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    public static void attach(ImageView iv, String uid) {
        if (iv == null) return;
        if (uid == null || uid.isEmpty()) {
            Icons.setTickBadge(iv, "none", false);
            return;
        }
        String level = CACHE.get(uid);
        if (level == null) {
            Models.User u = FastCache.getUser(uid);
            if (u != null) {
                level = u.tickLevel == null ? "none" : u.tickLevel;
                CACHE.put(uid, level);
            }
        }
        if (level != null) {
            Icons.setTickBadge(iv, level, false);
            return;
        }
        iv.setTag(uid);
        Icons.setTickBadge(iv, "none", false);
        // One D1 read of the user's tick level. This went to the Firestore disk cache first, which
        // held nothing once Firestore was off, so verified badges disappeared from the feed.
        com.friendbook.social.core.CloudflareStore.get("users", uid, d -> {
            String lvl = d == null ? null : Format.str(d, "tickLevel");
            if (lvl == null || lvl.isEmpty()) {
                fetchRemote(iv, uid);
                return;
            }
            CACHE.put(uid, lvl);
            if (uid.equals(iv.getTag())) Icons.setTickBadge(iv, CACHE.get(uid), false);
        });
    }

    private static void fetchRemote(ImageView iv, String uid) {
        com.friendbook.social.core.CloudflareStore.get("users", uid, d -> {
            String lvl = d == null ? null : Format.str(d, "tickLevel");
            if (lvl == null || lvl.isEmpty()) lvl = "none";
            CACHE.put(uid, lvl);
            if (uid.equals(iv.getTag())) Icons.setTickBadge(iv, CACHE.get(uid), false);
        });
    }

    public static void put(String uid, String level) {
        if (uid != null) CACHE.put(uid, level == null ? "none" : level);
    }

    public static String peek(String uid) {
        if (uid == null || uid.isEmpty()) return "none";
        String level = CACHE.get(uid);
        if (level != null) return level;
        Models.User u = FastCache.getUser(uid);
        if (u != null && u.tickLevel != null) return u.tickLevel;
        return "none";
    }

    public static void clear() { CACHE.clear(); }
}
