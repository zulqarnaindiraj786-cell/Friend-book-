package com.example.frienbook.core;

import android.view.View;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Map;

/**
 * Session cache of uid → tickLevel for surfaces whose docs do not carry
 * the tick (comments, notifications, ...). One users/{uid} read per user,
 * then every badge renders free. Recycler-safe via view tag.
 */
public final class TickCache {
    private TickCache() { }

    private static final Map<String, String> CACHE = new HashMap<>();

    /** Attach the tick badge for a user id (async on first sight). */
    public static void attach(ImageView iv, String uid) {
        if (iv == null) return;
        if (uid == null || uid.isEmpty()) {
            Icons.setTickBadge(iv, "none", false);
            return;
        }
        String level = CACHE.get(uid);
        if (level != null) {
            Icons.setTickBadge(iv, level, false);
            return;
        }
        iv.setTag(uid);
        Icons.setTickBadge(iv, "none", false);
        Fb.db().collection("users").document(uid).get()
                .addOnSuccessListener(d -> {
                    String lvl = d != null && d.exists() ? d.getString("tickLevel") : null;
                    CACHE.put(uid, lvl == null ? "none" : lvl);
                    if (uid.equals(iv.getTag()))
                        Icons.setTickBadge(iv, CACHE.get(uid), false);
                })
                .addOnFailureListener(e -> { /* badge just stays hidden */ });
    }

    /** Call whenever admin changes a tick, so views refresh instantly. */
    public static void put(String uid, String level) {
        if (uid != null) CACHE.put(uid, level == null ? "none" : level);
    }

    public static void clear() { CACHE.clear(); }
}
