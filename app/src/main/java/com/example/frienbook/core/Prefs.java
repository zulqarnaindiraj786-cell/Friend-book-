package com.example.frienbook.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Local settings (Facebook-Lite style preferences + secret features). */
public class Prefs {
    private static SharedPreferences p;

    private static SharedPreferences p(Context c) {
        if (p == null) p = c.getApplicationContext().getSharedPreferences("fb_prefs", Context.MODE_PRIVATE);
        return p;
    }

    // ---- appearance ----
    public static boolean dark(Context c) { return p(c).getBoolean("dark", false); }
    public static void setDark(Context c, boolean v) { p(c).edit().putBoolean("dark", v).apply(); }

    /** 0 = small, 1 = default, 2 = large */
    public static int fontSize(Context c) { return p(c).getInt("fontSize", 1); }
    public static void setFontSize(Context c, int v) { p(c).edit().putInt("fontSize", v).apply(); }
    public static float fontScale(Context c) {
        switch (fontSize(c)) { case 0: return 0.85f; case 2: return 1.18f; default: return 1f; }
    }

    // ---- data saver (classic Facebook Lite feature) ----
    public static boolean dataSaver(Context c) { return p(c).getBoolean("dataSaver", false); }
    public static void setDataSaver(Context c, boolean v) { p(c).edit().putBoolean("dataSaver", v).apply(); }

    // ---- quiet mode: hides badges & mutes vibration ----
    public static boolean quiet(Context c) { return p(c).getBoolean("quiet", false); }
    public static void setQuiet(Context c, boolean v) { p(c).edit().putBoolean("quiet", v).apply(); }

    // ---- app lock PIN ----
    public static String pin(Context c) { return p(c).getString("pin", ""); }
    public static void setPin(Context c, String v) { p(c).edit().putString("pin", v).apply(); }
    public static boolean unlockedSession(Context c) { return p(c).getBoolean("unlocked", false); }
    public static void setUnlockedSession(Context c, boolean v) { p(c).edit().putBoolean("unlocked", v).apply(); }

    // ---- privacy ----
    public static boolean activeStatus(Context c) { return p(c).getBoolean("activeStatus", true); }
    public static void setActiveStatus(Context c, boolean v) { p(c).edit().putBoolean("activeStatus", v).apply(); }

    public static boolean readReceipts(Context c) { return p(c).getBoolean("readReceipts", true); }
    public static void setReadReceipts(Context c, boolean v) { p(c).edit().putBoolean("readReceipts", v).apply(); }

    public static String defaultPrivacy(Context c) { return p(c).getString("defPrivacy", "Public"); }
    public static void setDefaultPrivacy(Context c, String v) { p(c).edit().putString("defPrivacy", v).apply(); }

    public static String friendRequestPrivacy(Context c) { return p(c).getString("reqPrivacy", "Everyone"); }
    public static void setFriendRequestPrivacy(Context c, String v) { p(c).edit().putString("reqPrivacy", v).apply(); }

    // ---- notification toggles ----
    public static boolean notifLikes(Context c) { return p(c).getBoolean("nLikes", true); }
    public static void setNotifLikes(Context c, boolean v) { p(c).edit().putBoolean("nLikes", v).apply(); }
    public static boolean notifComments(Context c) { return p(c).getBoolean("nComments", true); }
    public static void setNotifComments(Context c, boolean v) { p(c).edit().putBoolean("nComments", v).apply(); }
    public static boolean notifRequests(Context c) { return p(c).getBoolean("nRequests", true); }
    public static void setNotifRequests(Context c, boolean v) { p(c).edit().putBoolean("nRequests", v).apply(); }
    public static boolean notifMessages(Context c) { return p(c).getBoolean("nMessages", true); }
    public static void setNotifMessages(Context c, boolean v) { p(c).edit().putBoolean("nMessages", v).apply(); }

    // ---- pinned chats ----
    public static Set<String> pinnedChats(Context c) {
        return new HashSet<>(p(c).getStringSet("pinnedChats", new HashSet<>()));
    }
    public static void setPinnedChats(Context c, Set<String> v) {
        p(c).edit().putStringSet("pinnedChats", new HashSet<>(v)).apply();
    }

    // ---- seen-intro flags ----
    public static boolean seen(Context c, String key) { return p(c).getBoolean("seen_" + key, false); }
    public static void markSeen(Context c, String key) { p(c).edit().putBoolean("seen_" + key, true).apply(); }
}
