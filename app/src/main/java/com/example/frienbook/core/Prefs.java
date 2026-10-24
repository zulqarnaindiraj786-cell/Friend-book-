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

    // ---- hidden posts (Hide post feature) ----
    public static Set<String> hiddenPosts(Context c) {
        return new HashSet<>(p(c).getStringSet("hiddenPosts", new HashSet<>()));
    }
    public static void hidePost(Context c, String postId) {
        Set<String> s = hiddenPosts(c);
        s.add(postId);
        p(c).edit().putStringSet("hiddenPosts", s).apply();
    }
    public static void unhideAllPosts(Context c) {
        p(c).edit().putStringSet("hiddenPosts", new HashSet<>()).apply();
    }
    public static boolean isPostHidden(Context c, String postId) {
        return hiddenPosts(c).contains(postId);
    }

    // ---- chat theme (per-device): bubble colors for outgoing messages ----
    public static int chatBg(Context c) { return p(c).getInt("chatBg", 0xFF1877F2); }
    public static int chatTxt(Context c) { return p(c).getInt("chatTxt", 0xFFFFFFFF); }
    public static void chatTheme(Context c, int bg, int txt) {
        p(c).edit()
                .putInt("chatBg", bg)
                .putInt("chatTxt", txt)
                .apply();
    }

    // ---- snoozed people: set of "uid:untilMillis" (Snooze 30 days) ---- -- ----
    public static boolean isSnoozed(Context c, String uid) {
        long until = snoozeUntil(c, uid);
        return until > 0 && until > System.currentTimeMillis();
    }
    public static long snoozeUntil(Context c, String uid) {
        for (String s : p(c).getStringSet("snoozed", new HashSet<>())) {
            int i = s.indexOf(':');
            if (i > 0 && s.substring(0, i).equals(uid)) {
                try { return Long.parseLong(s.substring(i + 1)); } catch (Exception e) { return 0; }
            }
        }
        return 0;
    }
    public static void snooze(Context c, String uid, long days) {
        Set<String> s = new HashSet<>(p(c).getStringSet("snoozed", new HashSet<>()));
        s.removeIf(x -> x.startsWith(uid + ":"));
        s.add(uid + ":" + (System.currentTimeMillis() + days * 86400_000L));
        p(c).edit().putStringSet("snoozed", s).apply();
    }
    public static void unsnooze(Context c, String uid) {
        Set<String> s = new HashSet<>(p(c).getStringSet("snoozed", new HashSet<>()));
        s.removeIf(x -> x.startsWith(uid + ":"));
        p(c).edit().putStringSet("snoozed", s).apply();
    }

    // ---- hidden conversations ----
    public static Set<String> hiddenChats(Context c) {
        return new HashSet<>(p(c).getStringSet("hiddenChats", new HashSet<>()));
    }
    public static void hideChat(Context c, String chatId) {
        Set<String> s = hiddenChats(c);
        s.add(chatId);
        p(c).edit().putStringSet("hiddenChats", s).apply();
    }

    // ---- hidden messages in a chat ----
    public static Set<String> hiddenMsgs(Context c, String chatId) {
        return new HashSet<>(p(c).getStringSet("hiddenMsgs_" + chatId, new HashSet<>()));
    }
    public static void hideMsg(Context c, String chatId, String msgId) {
        Set<String> s = hiddenMsgs(c, chatId);
        s.add(msgId);
        p(c).edit().putStringSet("hiddenMsgs_" + chatId, s).apply();
    }

    // ---- seen-intro flags ----
    public static boolean seen(Context c, String key) { return p(c).getBoolean("seen_" + key, false); }
    public static void markSeen(Context c, String key) { p(c).edit().putBoolean("seen_" + key, true).apply(); }

    // ---- pending referral code (from the friendbook.app/invite deep link) ----
    public static String pendingReferral(Context c) { return p(c).getString("pendingRef", ""); }
    public static void setPendingReferral(Context c, String code) {
        p(c).edit().putString("pendingRef", code == null ? "" : code).apply();
    }
}
