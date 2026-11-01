package com.friendbook.social.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.friendbook.social.models.Models;
import com.friendbook.social.story.StoriesAdapter;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ⚡ friendbook 525 Zero-Loading 2G Engine:
 * - Instant 0ms RAM + persistent disk snapshot for Feed, Me, Users, Stories, Chats, Notifications & Reactions.
 * - Live 2G / EDGE / 3G / 4G / Wi-Fi / Offline network tier detector.
 * - Text-Only 2G Basic Mode ("Tap to view photo") session unlock tracker.
 * - 1-Tap Offline Feed Preloader & Lite Speed Control Sheet.
 */
public final class FastCache {

    private FastCache() { }

    public enum NetTier { OFFLINE, NET_2G, NET_3G, NET_4G, WIFI }

    public interface ReactionObserver {
        void onReactionChanged(String postId, String myReaction, int totalCount);
    }

    private static final String PREF_NAME = "fb_fast_cache_v1";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static final Map<String, Models.User> USERS = new ConcurrentHashMap<>();
    private static final Map<String, String> MY_REACTIONS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> REACTION_TOTALS = new ConcurrentHashMap<>();
    private static final Map<String, String> REACTION_EMOJIS = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> REACTION_TYPES = new ConcurrentHashMap<>();
    private static final Map<String, Long> LOCAL_REACTION_TS = new ConcurrentHashMap<>();
    private static final Set<String> REACTION_FETCHED = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> REVEALED_PHOTOS = Collections.synchronizedSet(new HashSet<>());
    private static final List<ReactionObserver> REACTION_OBSERVERS = new CopyOnWriteArrayList<>();

    private static volatile List<Models.Post> FEED = new ArrayList<>();
    private static volatile List<StoriesAdapter.Group> STORIES = new ArrayList<>();
    private static volatile List<Models.ChatRoom> CHATS = new ArrayList<>();
    private static volatile List<Models.Notif> NOTIFS = new ArrayList<>();
    private static volatile Models.User CACHED_ME = null;
    private static volatile boolean initialized = false;
    private static volatile int ramHits = 0;

    // ===================== BOOTSTRAP =====================

    public static synchronized void init(Context ctx) {
        if (initialized || ctx == null) return;
        initialized = true;
        Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        try {
            String meRaw = sp.getString("me_json", null);
            if (meRaw != null && !meRaw.isEmpty()) {
                CACHED_ME = userFromJson(new JSONObject(meRaw));
                if (CACHED_ME != null && CACHED_ME.uid != null) {
                    USERS.put(CACHED_ME.uid, CACHED_ME);
                }
            }
        } catch (Throwable ignored) { }

        try {
            String feedRaw = sp.getString("feed_json", null);
            if (feedRaw != null && !feedRaw.isEmpty()) {
                JSONArray arr = new JSONArray(feedRaw);
                List<Models.Post> loaded = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    Models.Post p = postFromJson(arr.optJSONObject(i));
                    if (p != null && p.id != null) loaded.add(p);
                }
                FEED = loaded;
            }
        } catch (Throwable ignored) { }

        try {
            String reactRaw = sp.getString("my_reactions_json", null);
            if (reactRaw != null && !reactRaw.isEmpty()) {
                JSONObject o = new JSONObject(reactRaw);
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = o.optString(k, "");
                    if (!v.isEmpty()) MY_REACTIONS.put(k, v);
                }
            }
        } catch (Throwable ignored) { }
    }

    public static void clearAll(Context ctx) {
        USERS.clear();
        MY_REACTIONS.clear();
        REACTION_TOTALS.clear();
        REACTION_EMOJIS.clear();
        REACTION_TYPES.clear();
        LOCAL_REACTION_TS.clear();
        REACTION_FETCHED.clear();
        REVEALED_PHOTOS.clear();
        FEED = new ArrayList<>();
        STORIES = new ArrayList<>();
        CHATS = new ArrayList<>();
        NOTIFS = new ArrayList<>();
        CACHED_ME = null;
        if (ctx != null) {
            ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().clear().apply();
        }
    }

    public static int ramHits() { return ramHits; }
    public static int cachedUserCount() { return USERS.size(); }
    public static int cachedFeedCount() { return FEED.size(); }

    // ===================== ME & USERS =====================

    public static Models.User getMe() { return CACHED_ME; }

    public static void saveMe(Context ctx, Models.User u) {
        if (u == null || u.uid == null) return;
        CACHED_ME = u;
        USERS.put(u.uid, u);
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit().putString("me_json", userToJson(u).toString()).apply();
            } catch (Throwable ignored) { }
        });
    }

    public static Models.User getUser(String uid) {
        if (uid == null || uid.isEmpty()) return null;
        Models.User u = USERS.get(uid);
        if (u != null) ramHits++;
        return u;
    }

    public static void putUser(Models.User u) {
        if (u != null && u.uid != null && !u.uid.isEmpty()) {
            USERS.put(u.uid, u);
            TickCache.put(u.uid, u.tickLevel);
        }
    }

    // ===================== FEED, STORIES, CHATS, NOTIFS =====================

    public static List<Models.Post> getFeed() {
        if (!FEED.isEmpty()) ramHits++;
        return new ArrayList<>(FEED);
    }

    public static void saveFeed(Context ctx, List<Models.Post> posts) {
        if (posts == null) return;
        List<Models.Post> copy = new ArrayList<>(posts);
        FEED = copy;
        long now = System.currentTimeMillis();
        for (Models.Post p : copy) {
            if (p != null && p.id != null) {
                Long lastLocal = LOCAL_REACTION_TS.get(p.id);
                boolean inFlight = lastLocal != null && (now - lastLocal) < 4000L;
                if (!inFlight) {
                    Integer prev = REACTION_TOTALS.get(p.id);
                    int incoming = Math.max(0, p.likes);
                    if (prev == null || !REACTION_FETCHED.contains(p.id) || incoming > prev) {
                        REACTION_TOTALS.put(p.id, incoming);
                    }
                }
            }
        }
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                int limit = Math.min(30, copy.size());
                for (int i = 0; i < limit; i++) {
                    arr.put(postToJson(copy.get(i)));
                }
                app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit().putString("feed_json", arr.toString()).apply();
            } catch (Throwable ignored) { }
        });
    }

    public static void prependFeedPost(Context ctx, Models.Post p) {
        if (p == null || p.id == null) return;
        List<Models.Post> next = new ArrayList<>(FEED.size() + 1);
        next.add(p);
        for (Models.Post old : FEED) {
            if (old != null && !p.id.equals(old.id)) next.add(old);
        }
        saveFeed(ctx, next);
    }

    public static void removeFeedPost(Context ctx, String postId) {
        if (postId == null || postId.isEmpty()) return;
        List<Models.Post> next = new ArrayList<>();
        boolean changed = false;
        for (Models.Post old : FEED) {
            if (old == null) continue;
            if (postId.equals(old.id)) {
                changed = true;
            } else {
                next.add(old);
            }
        }
        if (changed) saveFeed(ctx, next);
    }

    public static List<StoriesAdapter.Group> getStories() {
        return new ArrayList<>(STORIES);
    }

    public static void saveStories(List<StoriesAdapter.Group> list) {
        if (list != null) STORIES = new ArrayList<>(list);
    }

    public static List<Models.ChatRoom> getChats() {
        return new ArrayList<>(CHATS);
    }

    public static void saveChats(List<Models.ChatRoom> list) {
        if (list != null) CHATS = new ArrayList<>(list);
    }

    public static List<Models.Notif> getNotifications() {
        return new ArrayList<>(NOTIFS);
    }

    public static void saveNotifications(List<Models.Notif> list) {
        if (list != null) NOTIFS = new ArrayList<>(list);
    }

    // ===================== 0ms OPTIMISTIC REACTIONS =====================

    public static void addReactionObserver(ReactionObserver o) {
        if (o != null && !REACTION_OBSERVERS.contains(o)) REACTION_OBSERVERS.add(o);
    }

    public static void removeReactionObserver(ReactionObserver o) {
        if (o != null) REACTION_OBSERVERS.remove(o);
    }

    public static String getMyReaction(String postId) {
        if (postId == null) return null;
        String r = MY_REACTIONS.get(postId);
        return (r == null || r.isEmpty() || "none".equals(r)) ? null : r;
    }

    public static int getReactionTotal(String postId, int fallback) {
        if (postId == null) return Math.max(0, fallback);
        Integer v = REACTION_TOTALS.get(postId);
        if (v == null) return Math.max(0, fallback);
        Long lastLocal = LOCAL_REACTION_TS.get(postId);
        boolean inFlight = lastLocal != null && (System.currentTimeMillis() - lastLocal) < 4500L;
        if (inFlight) {
            return Math.max(0, v);
        }
        if (fallback >= 0 && fallback != v && !REACTION_FETCHED.contains(postId)) {
            return Math.max(fallback, v);
        }
        return Math.max(0, v);
    }

    /** Returns ordered distinct reaction types active on this post (e.g. ["love", "haha"]), or empty list. */
    public static List<String> getReactionTypes(String postId) {
        if (postId == null) return Collections.emptyList();
        List<String> list = REACTION_TYPES.get(postId);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    public static void setReactionTypes(String postId, List<String> types) {
        if (postId == null) return;
        if (types == null || types.isEmpty()) {
            REACTION_TYPES.remove(postId);
            REACTION_EMOJIS.remove(postId);
            return;
        }
        List<String> clean = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String t : types) {
            if (t == null || t.isEmpty() || clean.contains(t)) continue;
            clean.add(t);
            if (clean.size() <= 3) {
                sb.append(com.friendbook.social.post.Reactions.emojiFor(t));
            }
        }
        if (clean.isEmpty()) {
            REACTION_TYPES.remove(postId);
            REACTION_EMOJIS.remove(postId);
        } else {
            REACTION_TYPES.put(postId, clean);
            REACTION_EMOJIS.put(postId, sb.toString());
        }
    }

    public static String getReactionEmojis(String postId) {
        if (postId == null) return "";
        String s = REACTION_EMOJIS.get(postId);
        return s == null ? "" : s;
    }

    public static void setReactionEmojis(String postId, String emojis) {
        if (postId == null) return;
        if (emojis == null || emojis.isEmpty()) {
            REACTION_EMOJIS.remove(postId);
        } else {
            REACTION_EMOJIS.put(postId, emojis);
        }
    }

    public static void markLocalReactionTap(String postId) {
        if (postId != null) LOCAL_REACTION_TS.put(postId, System.currentTimeMillis());
    }

    public static boolean isLocalReactionInFlight(String postId) {
        if (postId == null) return false;
        Long ts = LOCAL_REACTION_TS.get(postId);
        return ts != null && (System.currentTimeMillis() - ts) < 4500L;
    }

    /**
     * Hydrates reaction state from embedded post.reactionsMap (both cold start AND live Firestore snapshots)
     * whenever no local optimistic tap is in flight — ensuring multi-user reactions & reaction type switches
     * (love, haha, wow, sad, angry, like) stay 100% accurate with zero stuck icons!
     */
    public static void seedReactionFromPostMap(String postId, String myReaction, int total) {
        if (postId == null || isLocalReactionInFlight(postId)) return;
        int cleanTotal = Math.max(0, total);
        REACTION_TOTALS.put(postId, cleanTotal);
        if (myReaction != null && !myReaction.isEmpty()) {
            MY_REACTIONS.put(postId, myReaction);
        } else if (cleanTotal == 0) {
            MY_REACTIONS.put(postId, "none");
        } else if (!MY_REACTIONS.containsKey(postId)) {
            MY_REACTIONS.put(postId, "none");
        }
    }

    public static boolean isReactionSynced(String postId) {
        return postId != null && REACTION_FETCHED.contains(postId);
    }

    public static void markReactionSynced(String postId) {
        if (postId != null) REACTION_FETCHED.add(postId);
    }

    public static void setReactionState(Context ctx, String postId, String myReaction, int total, boolean notify) {
        if (postId == null) return;
        int cleanTotal = Math.max(0, total);
        REACTION_TOTALS.put(postId, cleanTotal);
        if (myReaction == null || myReaction.isEmpty()) {
            MY_REACTIONS.put(postId, "none");
        } else {
            MY_REACTIONS.put(postId, myReaction);
        }
        REACTION_FETCHED.add(postId);
        // Update post object in FEED if present
        for (Models.Post p : FEED) {
            if (p != null && postId.equals(p.id)) {
                p.likes = cleanTotal;
                break;
            }
        }
        if (ctx != null) {
            final Context app = ctx.getApplicationContext();
            IO.execute(() -> {
                try {
                    JSONObject o = new JSONObject();
                    int c = 0;
                    for (Map.Entry<String, String> e : MY_REACTIONS.entrySet()) {
                        if (e.getValue() != null && !"none".equals(e.getValue())) {
                            o.put(e.getKey(), e.getValue());
                            if (++c >= 400) break;
                        }
                    }
                    app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit().putString("my_reactions_json", o.toString()).apply();
                } catch (Throwable ignored) { }
            });
        }
        if (notify) {
            String cleanMine = getMyReaction(postId);
            for (ReactionObserver obs : REACTION_OBSERVERS) {
                try { obs.onReactionChanged(postId, cleanMine, cleanTotal); }
                catch (Throwable ignored) { }
            }
        }
    }

    // ===================== TEXT-ONLY 2G PHOTO UNLOCKS =====================

    public static void revealPhoto(String postId) {
        if (postId != null) REVEALED_PHOTOS.add(postId);
    }

    public static boolean isPhotoRevealed(String postId) {
        return postId != null && REVEALED_PHOTOS.contains(postId);
    }

    // ===================== LIVE 2G / NETWORK DETECTOR =====================

    @SuppressWarnings("deprecation")
    public static NetTier netTier(Context ctx) {
        if (ctx == null) return NetTier.NET_4G;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return NetTier.NET_4G;
            Network net = cm.getActiveNetwork();
            if (net == null) return NetTier.OFFLINE;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return NetTier.OFFLINE;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                int down = caps.getLinkDownstreamBandwidthKbps();
                if (down > 0 && down < 200) return NetTier.NET_2G;
                return NetTier.WIFI;
            }
            int downKbps = caps.getLinkDownstreamBandwidthKbps();
            if (downKbps > 0 && downKbps < 220) return NetTier.NET_2G;
            if (downKbps > 0 && downKbps < 1200) return NetTier.NET_3G;

            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null && ni.getType() == ConnectivityManager.TYPE_MOBILE) {
                switch (ni.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                    case TelephonyManager.NETWORK_TYPE_GSM:
                        return NetTier.NET_2G;
                    case TelephonyManager.NETWORK_TYPE_UMTS:
                    case TelephonyManager.NETWORK_TYPE_EVDO_0:
                    case TelephonyManager.NETWORK_TYPE_EVDO_A:
                    case TelephonyManager.NETWORK_TYPE_HSDPA:
                    case TelephonyManager.NETWORK_TYPE_HSUPA:
                    case TelephonyManager.NETWORK_TYPE_HSPA:
                    case TelephonyManager.NETWORK_TYPE_EVDO_B:
                    case TelephonyManager.NETWORK_TYPE_EHRPD:
                    case TelephonyManager.NETWORK_TYPE_HSPAP:
                        return NetTier.NET_3G;
                }
            }
            return NetTier.NET_4G;
        } catch (Throwable ignored) {
            return NetTier.NET_4G;
        }
    }

    public static boolean isOffline(Context ctx) {
        return netTier(ctx) == NetTier.OFFLINE;
    }

    /** True if device is on 2G/EDGE, Offline, or user turned on Data Saver / Turbo 2G low-bandwidth mode. */
    public static boolean isSlow2G(Context ctx) {
        if (ctx == null) return false;
        NetTier t = netTier(ctx);
        return t == NetTier.NET_2G || t == NetTier.OFFLINE || Prefs.dataSaver(ctx);
    }

    public static String netLabel(Context ctx) {
        switch (netTier(ctx)) {
            case OFFLINE: return "📵 Offline (Cached Mode)";
            case NET_2G:  return "🐌 2G / EDGE (Ultra-Lite Auto)";
            case NET_3G:  return "📶 3G Mobile Data";
            case WIFI:    return "📡 Wi-Fi Connected";
            default:      return "⚡ 4G / LTE Connected";
        }
    }

    public static String modeBadgeLabel(Context ctx) {
        if (Prefs.textOnlyMode(ctx)) return "📵 Text 2G";
        if (Prefs.dataSaver(ctx) || netTier(ctx) == NetTier.NET_2G) return "⚡ 2G Saver";
        if (isOffline(ctx)) return "📵 Offline";
        return "⚡ Lite";
    }

    // ===================== OFFLINE FEED PRELOADER =====================

    public static void preloadFeedForOffline(Activity a, Runnable onDone) {
        if (a == null) return;
        Ui.toast(a, "📥 Caching latest 30 posts & mini thumbnails for offline reading…");
        Fb.db().collection("posts")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(30)
                .get()
                .addOnSuccessListener(snap -> {
                    List<Models.Post> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Models.Post p = Models.Post.fromDoc(d);
                        list.add(p);
                        if (!Prefs.textOnlyMode(a)) {
                            String u = p.mediaUrl;
                            if ((u == null || u.isEmpty()) && p.mediaUrls != null && !p.mediaUrls.isEmpty())
                                u = p.mediaUrls.get(0);
                            if (u != null && !u.isEmpty() && !"video".equals(p.mediaType)) {
                                Glide.with(a.getApplicationContext())
                                        .downloadOnly()
                                        .load(Ui.optimizeImageUrl(a, u, false))
                                        .submit();
                            }
                        }
                    }
                    saveFeed(a, list);
                    Ui.toast(a, "✅ " + list.size() + " posts saved! Ready for 0ms offline & 2G reading.");
                    if (onDone != null) onDone.run();
                })
                .addOnFailureListener(e -> Ui.toast(a, "Using " + FEED.size() + " already-cached posts offline."));
    }

    // ===================== LITE MODE & 2G SPEED CONTROL SHEET =====================

    public static void showLiteModeDialog(Activity a, Runnable onChanged) {
        if (a == null) return;
        ScrollView sv = new ScrollView(a);
        LinearLayout box = Ui.col(a);
        box.setBackgroundColor(Ui.card(a));
        Ui.pad(box, 18, 16, 18, 16);

        TextView head = Ui.tvBold(a, "⚡ friendbook 2G Turbo & Data Mode", 17, Ui.txt(a));
        box.addView(head);

        String savedMb = String.format(java.util.Locale.US, "%.2f MB", Prefs.dataBytesSaved(a) / 1048576.0);
        String usedMb = String.format(java.util.Locale.US, "%.2f MB", Prefs.dataBytesUsed(a) / 1048576.0);
        TextView stats = Ui.tv(a,
                "Network: " + netLabel(a)
                + "\nCached in RAM: " + cachedFeedCount() + " posts · " + cachedUserCount() + " profiles · " + ramHits() + " instant hits"
                + "\nData Saved: " + savedMb + "  (Used: " + usedMb + ")",
                12, Ui.sub(a));
        stats.setBackground(Ui.round(a, Ui.inputBg(a), 10));
        Ui.pad(stats, 12, 10, 12, 10);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(a, 10);
        sp.bottomMargin = Ui.dp(a, 12);
        box.addView(stats, sp);

        final AlertDialog[] holder = new AlertDialog[1];

        // Mode 1: Standard Fast WebP Mode
        boolean isText = Prefs.textOnlyMode(a);
        boolean isSaver = Prefs.dataSaver(a);
        int curMode = isText ? 2 : (isSaver ? 1 : 0);

        box.addView(modeOption(a,
                (curMode == 0 ? "🔘 " : "⚪ ") + "🌐 Standard Fast Mode (WebP Auto)",
                "Instant 0ms cache + crisp compressed WebP photos (~35 KB/photo).",
                curMode == 0, () -> {
                    Prefs.setTextOnlyMode(a, false);
                    Prefs.setDataSaver(a, false);
                    Prefs.setPhotoQuality(a, 1);
                    if (holder[0] != null) holder[0].dismiss();
                    if (onChanged != null) onChanged.run();
                    Ui.toast(a, "🌐 Standard Fast Mode active");
                }));

        box.addView(modeOption(a,
                (curMode == 1 ? "🔘 " : "⚪ ") + "⚡ 2G Data Saver Mode (~14 KB Photos)",
                "Saves up to 92% mobile data. Tiny ultra-fast WebP photos, manual videos, skips heavy ad scripts.",
                curMode == 1, () -> {
                    Prefs.setTextOnlyMode(a, false);
                    Prefs.setDataSaver(a, true);
                    Prefs.setPhotoQuality(a, 0);
                    if (holder[0] != null) holder[0].dismiss();
                    if (onChanged != null) onChanged.run();
                    Ui.toast(a, "⚡ 2G Data Saver Mode active");
                }));

        box.addView(modeOption(a,
                (curMode == 2 ? "🔘 " : "⚪ ") + "📵 Text-Only Basic 2G Mode (0 KB Photos)",
                "Classic friendbook Lite Free/Basic mode! Loads text in 0ms on weak 2G/EDGE — tap 'See Photo' only when you want.",
                curMode == 2, () -> {
                    Prefs.setTextOnlyMode(a, true);
                    Prefs.setDataSaver(a, true);
                    Prefs.setPhotoQuality(a, 0);
                    if (holder[0] != null) holder[0].dismiss();
                    if (onChanged != null) onChanged.run();
                    Ui.toast(a, "📵 Text-Only 2G Mode ON — 0 KB auto photo data!");
                }));

        TextView preloadBtn = Ui.btn(a, "📥 Preload 30 Posts for Offline / Bus / 2G");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.topMargin = Ui.dp(a, 8);
        preloadBtn.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            preloadFeedForOffline(a, onChanged);
        });
        box.addView(preloadBtn, pp);

        sv.addView(box);
        holder[0] = new AlertDialog.Builder(a)
                .setView(sv)
                .setNegativeButton("Close", null)
                .show();
    }

    private static LinearLayout modeOption(Activity a, String title, String sub, boolean selected, Runnable tap) {
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundStroke(a,
                selected ? Ui.blend(Ui.BLUE, Ui.card(a), 0.14f) : Ui.inputBg(a),
                10, selected ? Ui.BLUE : Ui.div(a), selected ? 2 : 1));
        Ui.pad(card, 12, 10, 12, 10);
        card.addView(Ui.tvBold(a, title, 14, selected ? Ui.BLUE : Ui.txt(a)));
        TextView s = Ui.tv(a, sub, 12, Ui.sub(a));
        s.setPadding(0, Ui.dp(a, 3), 0, 0);
        card.addView(s);
        card.setOnClickListener(v -> tap.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(a, 8);
        card.setLayoutParams(lp);
        return card;
    }

    // ===================== JSON SERIALIZERS =====================

    private static JSONObject userToJson(Models.User u) throws Exception {
        JSONObject o = new JSONObject();
        o.put("uid", u.uid == null ? "" : u.uid);
        o.put("displayName", u.displayName == null ? "User" : u.displayName);
        o.put("email", u.email == null ? "" : u.email);
        o.put("photoUrl", u.photoUrl == null ? "" : u.photoUrl);
        o.put("coverUrl", u.coverUrl == null ? "" : u.coverUrl);
        o.put("bio", u.bio == null ? "" : u.bio);
        o.put("tickLevel", u.tickLevel == null ? "none" : u.tickLevel);
        o.put("isAdmin", u.isAdmin);
        o.put("isModerator", u.isModerator);
        o.put("isVerified", u.isVerified);
        o.put("stars", u.stars);
        o.put("friendsCount", u.friendsCount);
        o.put("statusNote", u.statusNote == null ? "" : u.statusNote);
        o.put("statusNoteAt", u.statusNoteAt);
        o.put("hideReactionCounts", u.hideReactionCounts);
        o.put("emailPrivacy", u.emailPrivacy == null || u.emailPrivacy.isEmpty() ? "Only me" : u.emailPrivacy);
        o.put("contactPrivacy", u.contactPrivacy == null || u.contactPrivacy.isEmpty() ? "Only me" : u.contactPrivacy);
        o.put("contactInfoPrivacy", u.contactInfoPrivacy == null || u.contactInfoPrivacy.isEmpty() ? "Only me" : u.contactInfoPrivacy);
        return o;
    }

    private static Models.User userFromJson(JSONObject o) {
        if (o == null) return null;
        Models.User u = new Models.User();
        u.uid = o.optString("uid", "");
        u.displayName = o.optString("displayName", "User");
        u.email = o.optString("email", "");
        u.photoUrl = o.optString("photoUrl", "");
        u.coverUrl = o.optString("coverUrl", "");
        u.bio = o.optString("bio", "");
        u.tickLevel = o.optString("tickLevel", "none");
        u.isAdmin = o.optBoolean("isAdmin", false);
        u.isModerator = o.optBoolean("isModerator", false);
        u.isVerified = o.optBoolean("isVerified", false);
        u.stars = o.optInt("stars", 0);
        u.friendsCount = o.optInt("friendsCount", 0);
        u.statusNote = o.optString("statusNote", "");
        u.statusNoteAt = o.optLong("statusNoteAt", 0L);
        u.hideReactionCounts = o.optBoolean("hideReactionCounts", false);
        u.emailPrivacy = o.optString("emailPrivacy", "Only me");
        u.contactPrivacy = o.optString("contactPrivacy", u.emailPrivacy);
        u.contactInfoPrivacy = o.optString("contactInfoPrivacy", u.emailPrivacy);
        return u;
    }

    private static JSONObject postToJson(Models.Post p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", p.id == null ? "" : p.id);
        o.put("authorId", p.authorId == null ? "" : p.authorId);
        o.put("authorName", p.authorName == null ? "User" : p.authorName);
        o.put("authorPhoto", p.authorPhoto == null ? "" : p.authorPhoto);
        o.put("content", p.content == null ? "" : p.content);
        o.put("mediaUrl", p.mediaUrl == null ? "" : p.mediaUrl);
        o.put("mediaType", p.mediaType == null ? "none" : p.mediaType);
        o.put("feeling", p.feeling == null ? "" : p.feeling);
        o.put("privacy", p.privacy == null ? "Public" : p.privacy);
        o.put("location", p.location == null ? "" : p.location);
        o.put("postType", p.postType == null ? "regular" : p.postType);
        o.put("lifeEventIcon", p.lifeEventIcon == null ? "" : p.lifeEventIcon);
        o.put("lifeEventTitle", p.lifeEventTitle == null ? "" : p.lifeEventTitle);
        o.put("marketTitle", p.marketTitle == null ? "" : p.marketTitle);
        o.put("marketPrice", p.marketPrice == null ? "" : p.marketPrice);
        o.put("marketSold", p.marketSold);
        o.put("pageId", p.pageId == null ? "" : p.pageId);
        o.put("pageName", p.pageName == null ? "" : p.pageName);
        o.put("pagePhoto", p.pagePhoto == null ? "" : p.pagePhoto);
        o.put("bgColor", p.bgColor);
        o.put("likes", p.likes);
        o.put("comments", p.comments);
        o.put("shares", p.shares);
        o.put("createdAt", p.createdAt);
        o.put("authorVerified", p.authorVerified);
        o.put("authorTickLevel", p.authorTickLevel == null ? "none" : p.authorTickLevel);
        o.put("sharedFromId", p.sharedFromId == null ? "" : p.sharedFromId);
        o.put("sharedFromAuthor", p.sharedFromAuthor == null ? "" : p.sharedFromAuthor);
        o.put("sharedFromContent", p.sharedFromContent == null ? "" : p.sharedFromContent);
        if (p.mediaUrls != null && !p.mediaUrls.isEmpty()) {
            JSONArray m = new JSONArray();
            for (String u : p.mediaUrls) m.put(u);
            o.put("mediaUrls", m);
        }
        if (p.reactionsMap != null && !p.reactionsMap.isEmpty()) {
            JSONObject rxJson = new JSONObject();
            int rc = 0;
            for (Map.Entry<String, Map<String, Object>> e : p.reactionsMap.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                JSONObject sub = new JSONObject();
                Object t = e.getValue().get("type");
                sub.put("type", t == null ? "like" : String.valueOf(t));
                Object un = e.getValue().get("userName");
                if (un != null) sub.put("userName", String.valueOf(un));
                Object up = e.getValue().get("userPhoto");
                if (up != null) sub.put("userPhoto", String.valueOf(up));
                rxJson.put(e.getKey(), sub);
                if (++rc >= 120) break;
            }
            o.put("reactionsMap", rxJson);
        }
        return o;
    }

    private static Models.Post postFromJson(JSONObject o) {
        if (o == null) return null;
        Models.Post p = new Models.Post();
        p.id = o.optString("id", "");
        p.authorId = o.optString("authorId", "");
        p.authorName = o.optString("authorName", "User");
        p.authorPhoto = o.optString("authorPhoto", "");
        p.content = o.optString("content", "");
        p.mediaUrl = o.optString("mediaUrl", "");
        p.mediaType = o.optString("mediaType", "none");
        p.feeling = o.optString("feeling", "");
        p.privacy = o.optString("privacy", "Public");
        p.location = o.optString("location", "");
        p.postType = o.optString("postType", "regular");
        p.lifeEventIcon = o.optString("lifeEventIcon", "");
        p.lifeEventTitle = o.optString("lifeEventTitle", "");
        p.marketTitle = o.optString("marketTitle", "");
        p.marketPrice = o.optString("marketPrice", "");
        p.marketSold = o.optBoolean("marketSold", false);
        p.pageId = o.optString("pageId", "");
        p.pageName = o.optString("pageName", "");
        p.pagePhoto = o.optString("pagePhoto", "");
        p.bgColor = o.optInt("bgColor", 0);
        p.likes = o.optInt("likes", 0);
        p.comments = o.optInt("comments", 0);
        p.shares = o.optInt("shares", 0);
        p.createdAt = o.optLong("createdAt", 0L);
        p.authorVerified = o.optBoolean("authorVerified", false);
        p.authorTickLevel = o.optString("authorTickLevel", "none");
        p.sharedFromId = o.optString("sharedFromId", "");
        p.sharedFromAuthor = o.optString("sharedFromAuthor", "");
        p.sharedFromContent = o.optString("sharedFromContent", "");
        JSONArray m = o.optJSONArray("mediaUrls");
        if (m != null) {
            for (int i = 0; i < m.length(); i++) {
                String u = m.optString(i, "");
                if (!u.isEmpty()) p.mediaUrls.add(u);
            }
        }
        JSONObject rxJson = o.optJSONObject("reactionsMap");
        if (rxJson != null) {
            java.util.Iterator<String> it = rxJson.keys();
            while (it.hasNext()) {
                String uid = it.next();
                JSONObject subObj = rxJson.optJSONObject(uid);
                if (uid != null && subObj != null) {
                    Map<String, Object> sub = new java.util.HashMap<>();
                    sub.put("type", subObj.optString("type", "like"));
                    sub.put("userName", subObj.optString("userName", ""));
                    sub.put("userPhoto", subObj.optString("userPhoto", ""));
                    p.reactionsMap.put(uid, sub);
                }
            }
            if (p.reactionsMap.size() > p.likes) p.likes = p.reactionsMap.size();
        }
        return p;
    }
}
