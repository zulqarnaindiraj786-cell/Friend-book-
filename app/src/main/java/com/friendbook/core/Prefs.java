package com.friendbook.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Local settings (Facebook-Lite 525.0.0.5.102 full preferences, accounts, wellbeing & Lite data tools). */
public class Prefs {
    private static SharedPreferences p;

    private static SharedPreferences p(Context c) {
        if (p == null) p = c.getApplicationContext().getSharedPreferences("fb_prefs", Context.MODE_PRIVATE);
        return p;
    }

    // ---- appearance ----
    public static boolean dark(Context c) { return p(c).getBoolean("dark", false); }
    public static void setDark(Context c, boolean v) { p(c).edit().putBoolean("dark", v).apply(); }

    /** 0 = small, 1 = default, 2 = large, 3 = extra large */
    public static int fontSize(Context c) { return p(c).getInt("fontSize", 1); }
    public static void setFontSize(Context c, int v) { p(c).edit().putInt("fontSize", v).apply(); }
    public static float fontScale(Context c) {
        switch (fontSize(c)) {
            case 0: return 0.85f;
            case 2: return 1.16f;
            case 3: return 1.30f;
            default: return 1f;
        }
    }

    // ---- data saver & photo quality (classic Facebook Lite feature) ----
    public static boolean dataSaver(Context c) { return p(c).getBoolean("dataSaver", false); }
    public static void setDataSaver(Context c, boolean v) { p(c).edit().putBoolean("dataSaver", v).apply(); }

    /** 0 = Low (Data Saver), 1 = Medium, 2 = High */
    public static int photoQuality(Context c) {
        return p(c).getInt("photoQuality", dataSaver(c) ? 0 : 2);
    }
    public static void setPhotoQuality(Context c, int q) {
        p(c).edit().putInt("photoQuality", q).apply();
    }
    public static String photoQualityLabel(Context c) {
        switch (photoQuality(c)) {
            case 0: return "Low (saves up to 80% data)";
            case 1: return "Medium (balanced)";
            default: return "High (best clarity)";
        }
    }

    // ---- live data usage tracker (FB Lite Data Saver stats) ----
    public static long dataBytesUsed(Context c) { return p(c).getLong("dataBytesUsed", 0L); }
    public static long dataBytesSaved(Context c) { return p(c).getLong("dataBytesSaved", 0L); }
    public static void trackMediaLoad(Context c, long approxFullBytes) {
        int q = dataSaver(c) ? 0 : photoQuality(c);
        long used = q == 0 ? approxFullBytes / 5 : (q == 1 ? approxFullBytes / 2 : approxFullBytes);
        long saved = Math.max(0, approxFullBytes - used);
        SharedPreferences sp = p(c);
        sp.edit()
                .putLong("dataBytesUsed", sp.getLong("dataBytesUsed", 0L) + used)
                .putLong("dataBytesSaved", sp.getLong("dataBytesSaved", 0L) + saved)
                .apply();
    }
    public static void trackMediaLoad(Context c, long approxFullBytes, long unused) {
        trackMediaLoad(c, approxFullBytes);
    }
    public static void resetDataStats(Context c) {
        p(c).edit().putLong("dataBytesUsed", 0L).putLong("dataBytesSaved", 0L).apply();
    }

    // ---- AMOLED pure-black theme (needs dark mode on) ----
    public static boolean amoled(Context c) { return p(c).getBoolean("amoled", false); }
    public static void setAmoled(Context c, boolean v) { p(c).edit().putBoolean("amoled", v).apply(); }

    // ---- quiet mode: hides badges & mutes vibration ----
    public static boolean quiet(Context c) { return p(c).getBoolean("quiet", false); }
    public static void setQuiet(Context c, boolean v) { p(c).edit().putBoolean("quiet", v).apply(); }

    // ---- shake to report toggle ----
    public static boolean shakeToReport(Context c) { return p(c).getBoolean("shakeReport", true); }
    public static void setShakeToReport(Context c, boolean v) { p(c).edit().putBoolean("shakeReport", v).apply(); }

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

    public static String storyPrivacy(Context c) { return p(c).getString("storyPrivacy", "Public"); }
    public static void setStoryPrivacy(Context c, String v) { p(c).edit().putString("storyPrivacy", v).apply(); }

    public static boolean storyArchive(Context c) { return p(c).getBoolean("storyArchive", true); }
    public static void setStoryArchive(Context c, boolean v) { p(c).edit().putBoolean("storyArchive", v).apply(); }

    public static String reelPrivacy(Context c) { return p(c).getString("reelPrivacy", "Public"); }
    public static void setReelPrivacy(Context c, String v) { p(c).edit().putString("reelPrivacy", v).apply(); }

    public static String friendRequestPrivacy(Context c) { return p(c).getString("reqPrivacy", "Everyone"); }
    public static void setFriendRequestPrivacy(Context c, String v) { p(c).edit().putString("reqPrivacy", v).apply(); }

    // ---- reaction count preferences (FB Lite Reaction Preferences) ----
    public static boolean hideOtherReactions(Context c) { return p(c).getBoolean("hideOtherReact", false); }
    public static void setHideOtherReactions(Context c, boolean v) { p(c).edit().putBoolean("hideOtherReact", v).apply(); }
    public static boolean hideMyReactions(Context c) { return p(c).getBoolean("hideMyReact", false); }
    public static void setHideMyReactions(Context c, boolean v) { p(c).edit().putBoolean("hideMyReact", v).apply(); }
    public static boolean hideReactionCounts(Context c) { return hideOtherReactions(c) || hideMyReactions(c); }

    // ---- notification toggles ----
    public static boolean notifLikes(Context c) { return p(c).getBoolean("nLikes", true); }
    public static void setNotifLikes(Context c, boolean v) { p(c).edit().putBoolean("nLikes", v).apply(); }
    public static boolean notifComments(Context c) { return p(c).getBoolean("nComments", true); }
    public static void setNotifComments(Context c, boolean v) { p(c).edit().putBoolean("nComments", v).apply(); }
    public static boolean notifRequests(Context c) { return p(c).getBoolean("nRequests", true); }
    public static void setNotifRequests(Context c, boolean v) { p(c).edit().putBoolean("nRequests", v).apply(); }
    public static boolean notifMessages(Context c) { return p(c).getBoolean("nMessages", true); }
    public static void setNotifMessages(Context c, boolean v) { p(c).edit().putBoolean("nMessages", v).apply(); }
    public static boolean notifTags(Context c) { return p(c).getBoolean("nTags", true); }
    public static void setNotifTags(Context c, boolean v) { p(c).edit().putBoolean("nTags", v).apply(); }
    public static boolean notifBirthdays(Context c) { return p(c).getBoolean("nBirthdays", true); }
    public static void setNotifBirthdays(Context c, boolean v) { p(c).edit().putBoolean("nBirthdays", v).apply(); }
    public static boolean notifGroups(Context c) { return p(c).getBoolean("nGroups", true); }
    public static void setNotifGroups(Context c, boolean v) { p(c).edit().putBoolean("nGroups", v).apply(); }
    public static boolean notifEvents(Context c) { return p(c).getBoolean("nEvents", true); }
    public static void setNotifEvents(Context c, boolean v) { p(c).edit().putBoolean("nEvents", v).apply(); }
    public static boolean notifVibrate(Context c) { return p(c).getBoolean("nVibrate", true); }
    public static void setNotifVibrate(Context c, boolean v) { p(c).edit().putBoolean("nVibrate", v).apply(); }
    public static boolean notifMentions(Context c) { return notifTags(c); }
    public static boolean notifMarketplace(Context c) { return p(c).getBoolean("nMarket", true); }
    public static boolean notifSecurity(Context c) { return p(c).getBoolean("nSecurity", true); }

    // ---- feed filter & sort ----
    // all | recent | favorites | photos | videos | text
    public static String feedFilter(Context c) { return p(c).getString("feedFilter", "all"); }
    public static void setFeedFilter(Context c, String v) { p(c).edit().putString("feedFilter", v).apply(); }

    public static String feedSort(Context c) { return p(c).getString("feedSort", "top"); } // top | recent
    public static void setFeedSort(Context c, String v) { p(c).edit().putString("feedSort", v).apply(); }

    // ---- media, sound & browser ----
    public static boolean autoplay(Context c) { return autoplayMode(c) != 0; }
    public static void setAutoplay(Context c, boolean v) {
        p(c).edit().putBoolean("autoplay", v).putInt("autoplayMode", v ? 2 : 0).apply();
    }

    // 🎬 v525-style autoplay modes: 0=Never, 1=On Wi-Fi only, 2=Always
    public static int autoplayMode(Context c) {
        int v = p(c).getInt("autoplayMode", -1);
        if (v < 0) { v = p(c).getBoolean("autoplay", true) ? 2 : 0; setAutoplayMode(c, v); }
        return v;
    }
    public static void setAutoplayMode(Context c, int v) {
        p(c).edit().putInt("autoplayMode", v).putBoolean("autoplay", v != 0).apply();
    }
    public static String autoplayModeLabel(Context c) {
        switch (autoplayMode(c)) {
            case 0: return "Never autoplay videos";
            case 1: return "On Wi-Fi only";
            default: return "On mobile data and Wi-Fi";
        }
    }
    public static boolean shouldAutoplayNow(Context c) {
        if (dataSaver(c)) return false;
        int m = autoplayMode(c);
        if (m == 0) return false;
        if (m == 1) return onWifi(c);
        return true;
    }

    public static boolean videoSound(Context c) { return p(c).getBoolean("videoSound", true); }
    public static void setVideoSound(Context c, boolean v) { p(c).edit().putBoolean("videoSound", v).apply(); }

    public static boolean inAppSounds(Context c) { return p(c).getBoolean("inAppSounds", true); }
    public static void setInAppSounds(Context c, boolean v) { p(c).edit().putBoolean("inAppSounds", v).apply(); }

    public static boolean externalBrowser(Context c) { return p(c).getBoolean("extBrowser", false); }
    public static void setExternalBrowser(Context c, boolean v) { p(c).edit().putBoolean("extBrowser", v).apply(); }

    // ---- language & region ----
    public static String appLanguage(Context c) { return p(c).getString("appLang", "English"); }
    public static void setAppLanguage(Context c, String v) { p(c).edit().putString("appLang", v).apply(); }
    public static String appLang(Context c) {
        String l = appLanguage(c).toLowerCase(Locale.ROOT);
        if (l.contains("urdu") && !l.contains("roman")) return "ur";
        if (l.contains("hindi")) return "hi";
        if (l.contains("arabic") || l.contains("العربية")) return "ar";
        if (l.contains("deutsch") || l.contains("german")) return "de";
        if (l.contains("espa")) return "es";
        if (l.contains("fran")) return "fr";
        return "en";
    }

    /** 📶 true when the active network is Wi-Fi (or ethernet) — used by autoplay mode 1. */
    public static boolean onWifi(Context c) {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Throwable t) { return true; }
    }

    // ---- post composer draft ----
    public static String postDraft(Context c) { return p(c).getString("postDraft", ""); }
    public static void setPostDraft(Context c, String v) {
        p(c).edit().putString("postDraft", v == null ? "" : v).apply();
    }

    // ---- saved accounts switcher (FB Lite multi-account quick switch) ----
    public static class SavedAccount {
        public String uid, email, name, photoUrl;
    }
    public static List<SavedAccount> savedAccounts(Context c) {
        List<SavedAccount> out = new ArrayList<>();
        String raw = p(c).getString("savedAccounts", "");
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\\n")) {
            String[] parts = line.split("\\t", -1);
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                SavedAccount sa = new SavedAccount();
                sa.uid = parts[0];
                sa.email = parts[1];
                sa.name = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : parts[1];
                sa.photoUrl = parts.length > 3 ? parts[3] : "";
                out.add(sa);
            }
        }
        return out;
    }
    public static void saveAccount(Context c, String uid, String email, String name, String photoUrl) {
        if (email == null || email.trim().isEmpty()) return;
        List<SavedAccount> cur = savedAccounts(c);
        List<SavedAccount> next = new ArrayList<>();
        SavedAccount fresh = new SavedAccount();
        fresh.uid = uid == null ? "" : uid.replace("\t", " ").replace("\n", " ");
        fresh.email = email.trim().replace("\t", "").replace("\n", "");
        fresh.name = (name == null || name.isEmpty() ? fresh.email : name).replace("\t", " ").replace("\n", " ");
        fresh.photoUrl = (photoUrl == null ? "" : photoUrl).replace("\t", "").replace("\n", "");
        next.add(fresh);
        for (SavedAccount a : cur) {
            if (!a.email.equalsIgnoreCase(fresh.email) && next.size() < 5) next.add(a);
        }
        saveAccountList(c, next);
    }
    public static void removeSavedAccount(Context c, String email) {
        List<SavedAccount> cur = savedAccounts(c);
        cur.removeIf(a -> a.email.equalsIgnoreCase(email));
        saveAccountList(c, cur);
    }
    private static void saveAccountList(Context c, List<SavedAccount> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            SavedAccount a = list.get(i);
            if (i > 0) sb.append('\n');
            sb.append(a.uid).append('\t').append(a.email).append('\t')
                    .append(a.name).append('\t').append(a.photoUrl);
        }
        p(c).edit().putString("savedAccounts", sb.toString()).apply();
    }

    // ---- Your Time on friendbook (7-day wellbeing tracker + daily limit) ----
    private static String dayKey(Date d) {
        return "timeMin_" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(d);
    }
    /** Records foreground seconds and returns true when crossing the daily reminder threshold. */
    public static boolean addForegroundSeconds(Context c, int sec) {
        if (sec <= 0) return false;
        SharedPreferences sp = p(c);
        String sk = "timeSec_" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        int totalSec = sp.getInt(sk, 0) + sec;
        int prevMin = sp.getInt(dayKey(new Date()), 0);
        int nowMin = Math.max(prevMin, totalSec / 60);
        sp.edit().putInt(sk, totalSec).putInt(dayKey(new Date()), nowMin).apply();
        int limit = dailyLimitMinutes(c);
        return limit > 0 && prevMin < limit && nowMin >= limit;
    }
    public static int todayMinutes(Context c) {
        int m = p(c).getInt(dayKey(new Date()), 0);
        int s = p(c).getInt("timeSec_" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()), 0);
        return Math.max(m, s > 0 ? Math.max(1, s / 60) : 0);
    }
    public static int[] last7DaysMinutes(Context c) {
        int[] out = new int[7];
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -6);
        for (int i = 0; i < 7; i++) {
            out[i] = i == 6 ? todayMinutes(c) : p(c).getInt(dayKey(cal.getTime()), 0);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return out;
    }
    public static String[] last7DayLabels() {
        String[] out = new String[7];
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -6);
        SimpleDateFormat fmt = new SimpleDateFormat("EEE", Locale.US);
        for (int i = 0; i < 7; i++) {
            out[i] = i == 6 ? "Today" : fmt.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return out;
    }
    public static int dailyLimitMinutes(Context c) { return p(c).getInt("dailyLimitMin", 0); }
    public static void setDailyLimitMinutes(Context c, int mins) { p(c).edit().putInt("dailyLimitMin", mins).apply(); }

    // 🔍 recent searches (max 8, newest first) — FB Lite search screen
    public static List<String> searchHistory(Context c) {
        String s = p(c).getString("searchHistory", "");
        List<String> out = new ArrayList<>();
        if (s != null && !s.isEmpty())
            for (String q : s.split("\\n")) if (!q.trim().isEmpty()) out.add(q);
        return out;
    }
    public static void addSearch(Context c, String q) {
        if (q == null) return;
        q = q.trim();
        if (q.isEmpty()) return;
        List<String> h = searchHistory(c);
        h.remove(q);
        h.add(0, q);
        while (h.size() > 8) h.remove(h.size() - 1);
        p(c).edit().putString("searchHistory", android.text.TextUtils.join("\n", h)).apply();
    }
    public static void clearSearch(Context c) { p(c).edit().remove("searchHistory").apply(); }

    // ---- pinned, archived, muted chats & nicknames ----
    public static Set<String> pinnedChats(Context c) {
        return new HashSet<>(p(c).getStringSet("pinnedChats", new HashSet<>()));
    }
    public static void setPinnedChats(Context c, Set<String> v) {
        p(c).edit().putStringSet("pinnedChats", new HashSet<>(v)).apply();
    }

    public static Set<String> archivedChats(Context c) {
        return new HashSet<>(p(c).getStringSet("archivedChats", new HashSet<>()));
    }
    public static void setChatArchived(Context c, String chatId, boolean arch) {
        Set<String> s = archivedChats(c);
        if (arch) s.add(chatId); else s.remove(chatId);
        p(c).edit().putStringSet("archivedChats", s).apply();
    }
    public static boolean isChatArchived(Context c, String chatId) {
        return archivedChats(c).contains(chatId);
    }
    public static void archiveChat(Context c, String chatId, boolean arch) {
        setChatArchived(c, chatId, arch);
    }

    public static Set<String> mutedChats(Context c) {
        return new HashSet<>(p(c).getStringSet("mutedChats", new HashSet<>()));
    }
    public static void setChatMuted(Context c, String chatId, boolean muted) {
        Set<String> s = mutedChats(c);
        if (muted) s.add(chatId); else s.remove(chatId);
        p(c).edit().putStringSet("mutedChats", s).apply();
    }
    public static boolean isChatMuted(Context c, String chatId) {
        return mutedChats(c).contains(chatId);
    }
    public static void muteChat(Context c, String chatId, boolean muted) {
        setChatMuted(c, chatId, muted);
    }

    public static String chatNickname(Context c, String chatId) {
        return p(c).getString("nick_" + chatId, "");
    }
    public static void setChatNickname(Context c, String chatId, String nick) {
        p(c).edit().putString("nick_" + chatId, nick == null ? "" : nick.trim()).apply();
    }

    // ---- muted stories ----
    public static Set<String> mutedStories(Context c) {
        return new HashSet<>(p(c).getStringSet("mutedStories", new HashSet<>()));
    }
    public static void muteStoryAuthor(Context c, String uid, boolean mute) {
        Set<String> s = mutedStories(c);
        if (mute) s.add(uid); else s.remove(uid);
        p(c).edit().putStringSet("mutedStories", s).apply();
    }
    public static boolean isStoryAuthorMuted(Context c, String uid) {
        return mutedStories(c).contains(uid);
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
    public static void setChatTheme(Context c, int bg, int txt) {
        p(c).edit().putInt("chatBg", bg).putInt("chatTxt", txt).apply();
    }
    public static void chatTheme(Context c, int bg, int txt) {
        setChatTheme(c, bg, txt);
    }

    // ---- snoozed people: set of "uid:untilMillis" (Snooze 30 days) ----
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
