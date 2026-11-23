package com.friendbook.social.core;

import android.widget.ImageView;

import com.friendbook.social.models.Models;
import com.friendbook.social.d1.documents.Source;

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
        Fb.db().collection("users").document(uid).get(Source.CACHE)
                .addOnSuccessListener(d -> {
                    if (d != null && d.exists()) {
                        String lvl = d.getString("tickLevel");
                        CACHE.put(uid, lvl == null ? "none" : lvl);
                        if (uid.equals(iv.getTag())) Icons.setTickBadge(iv, CACHE.get(uid), false);
                    } else {
                        fetchRemote(iv, uid);
                    }
                })
                .addOnFailureListener(e -> fetchRemote(iv, uid));
    }

    private static void fetchRemote(ImageView iv, String uid) {
        Fb.db().collection("users").document(uid).get()
                .addOnSuccessListener(d -> {
                    String lvl = d != null && d.exists() ? d.getString("tickLevel") : null;
                    CACHE.put(uid, lvl == null ? "none" : lvl);
                    if (uid.equals(iv.getTag())) Icons.setTickBadge(iv, CACHE.get(uid), false);
                })
                .addOnFailureListener(e -> CACHE.put(uid, "none"));
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
