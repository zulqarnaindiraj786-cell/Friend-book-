package com.friendbook.social.core;

import android.content.Context;

import androidx.annotation.Nullable;

import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.PersistentCacheSettings;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;

import java.util.HashMap;
import java.util.Map;

/**
 * Central Firebase access point with friendbook 2G cache-first engine:
 * - 100 MB Persistent Offline Disk Cache so posts, chats, comments & profiles render in <2ms even offline/2G.
 * - Instant 0ms RAM hydration for {@link #me} and {@link #getUser(String)}.
 */
public class Fb {

    public static final String RTDB_URL = "https://friendbook-17551-default-rtdb.firebaseio.com";

    private static FirebaseDatabase rtdb;
    private static boolean rtdbTried;
    private static boolean firestoreConfigured;
    /** Latched once Firestore's network has been switched off for this process. */
    private static boolean firestoreNetworkOff;
    public static Models.User me;           // cached profile of the signed-in user
    private static ListenerRegistration meReg;
    private static long meLastRefresh;

    public static void init(Context appContext) {
        FastCache.init(appContext);
        if (!firestoreConfigured) {
            firestoreConfigured = true;
            try {
                FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                        .setLocalCacheSettings(PersistentCacheSettings.newBuilder()
                                .setSizeBytes(100L * 1024L * 1024L) // 100 MB offline disk cache
                                .build())
                        .build();
                FirebaseFirestore.getInstance().setFirestoreSettings(settings);
            } catch (Throwable ignored) { }
        }
        // The data plane is D1. Firestore's network is switched off below, so this must run
        // before anything has a chance to open a listener.
        if (com.friendbook.social.BuildConfig.CLOUDFLARE_DATA_ENABLED) {
            disableFirestoreNetwork();
        }
        // Instant 0ms cold-start hydration of signed-in user from local snapshot
        String currentUid = uid();
        Models.User cachedMe = FastCache.getMe();
        if (currentUid != null && cachedMe != null && currentUid.equals(cachedMe.uid)) {
            me = cachedMe;
        }
    }

    /**
     * Switches Firestore's network off for the whole process.
     *
     * <p>This is the actual "Firebase off, D1 on" switch. Firebase Auth, FCM, Crashlytics and
     * App Check all keep working - they are separate Firebase products with their own
     * transports, and disabling Firestore's network touches none of them.
     *
     * <p>What it stops: every snapshot listener and every query in this app reads Firestore,
     * and a disabled network means those operations fail from the local cache instead of
     * spending the daily read quota. With the Spark allowance exhausted, that is the difference
     * between an app that shows cached content and one that hangs on a refused request.
     *
     * <p>What it does not do: it does not delete anything, on device or in the cloud. The
     * Firestore database is untouched and still holds every document; re-enabling the network
     * is a one-line revert. Writes that still target Firestore go to the local write queue and
     * are flushed if the network is ever re-enabled, so nothing is lost either way.
     */
    private static void disableFirestoreNetwork() {
        try {
            if (firestoreNetworkOff) return;
            firestoreNetworkOff = true;
            FirebaseFirestore.getInstance().disableNetwork()
                    .addOnSuccessListener(v -> android.util.Log.i("Fb",
                            "Firestore network disabled: data plane is Cloudflare D1"))
                    .addOnFailureListener(err -> {
                        firestoreNetworkOff = false;
                        android.util.Log.w("Fb", "Could not disable the Firestore network");
                    });
        } catch (Throwable unavailable) {
            android.util.Log.w("Fb", "Could not disable the Firestore network");
        }
    }

    /** True once Firestore's network has been switched off for this process. */
    public static boolean isFirestoreNetworkOff() {
        return firestoreNetworkOff;
    }

    public static FirebaseAuth auth() { return FirebaseAuth.getInstance(); }

    public static FirebaseFirestore db() { return FirebaseFirestore.getInstance(); }

    /** Realtime Database (presence + typing). Returns null if the instance does not exist yet. */
    @Nullable
    public static FirebaseDatabase rtdb() {
        if (rtdb != null) return rtdb;
        if (rtdbTried) return null;
        rtdbTried = true;
        try {
            rtdb = FirebaseDatabase.getInstance(RTDB_URL);
            rtdb.setPersistenceEnabled(true);
            rtdb.getReference(".info/connected");
        } catch (Throwable t) {
            rtdb = null;
        }
        return rtdb;
    }

    @Nullable
    public static String uid() {
        return auth().getCurrentUser() != null ? auth().getCurrentUser().getUid() : null;
    }

    public static boolean signedIn() { return auth().getCurrentUser() != null; }

    /** Canonical 1:1 chat id (same scheme as the Flutter app). */
    public static String chatId(String a, String b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    /** Start (or restart) the live listener that keeps {@link #me} fresh. */
    /**
     * Pulls {@code users/{me}} from D1.
     *
     * <p>Needed because the Firestore me-listener can no longer produce a fresh document: with
     * Firestore's network off it only ever replays the on-device cache, so a profile edit made
     * on another device would never arrive and a fresh install would never populate at all.
     * D1 is the source of truth for this document now, so this is what fills it in.
     */
    public static void hydrateMeFromD1() {
        final String uid = uid();
        if (uid == null) return;
        CloudflareStore.get("users", uid, doc -> {
            if (doc == null || !uid.equals(uidOf())) return;
            Models.User fresh = Models.User.fromMap(uid, doc);
            if (fresh == null || fresh.uid == null) return;
            me = fresh;
            FastCache.saveMe(com.friendbook.social.App.instance, fresh);
            notifyMeObservers(null);
        });
    }

    /** The signed-in uid, read defensively so this class works before auth settles. */
    private static String uidOf() {
        try {
            return uid();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void startMeListener() {
        String uid = uid();
        if (uid == null) return;
        if (me == null) {
            Models.User cachedMe = FastCache.getMe();
            if (cachedMe != null && uid.equals(cachedMe.uid)) me = cachedMe;
        }
        if (meReg != null) meReg.remove();
        if (com.friendbook.social.BuildConfig.CLOUDFLARE_DATA_ENABLED) hydrateMeFromD1();
        meReg = db().collection("users").document(uid)
                .addSnapshotListener((doc, err) -> {
                    if (doc == null) return;
                    if (!doc.exists()) {
                        if (!doc.getMetadata().isFromCache()) {
                            com.google.firebase.auth.FirebaseUser fu = auth().getCurrentUser();
                            if (fu != null && uid.equals(fu.getUid())) {
                                String em = com.friendbook.social.auth.GoogleAuthHelper.resolveGoogleEmail(fu);
                                String rawN = fu.getDisplayName();
                                String dn = (rawN != null && !rawN.trim().isEmpty())
                                        ? rawN.trim()
                                        : (!em.isEmpty() && em.contains("@") ? em.substring(0, em.indexOf('@')) : "friendbook User");
                                String ph = fu.getPhotoUrl() != null
                                        ? fu.getPhotoUrl().toString().replace("=s96-c", "=s400-c") : "";
                                db().collection("users").document(uid).set(
                                        com.friendbook.social.auth.GoogleAuthHelper.buildDefaultGoogleUserMap(uid, em, dn, ph),
                                        com.google.firebase.firestore.SetOptions.merge());
                            }
                        }
                        return;
                    }
                    Models.User u = Models.User.fromDoc(doc);
                    me = u;
                    FastCache.saveMe(com.friendbook.social.App.instance, u);
                    syncProfileToCloudflare(u);
                    notifyMeObservers(doc);
                    if (u.banned && !Admin.isOwnerEmail(u.email)) {
                        me = null;
                        FastCache.clearAll(com.friendbook.social.App.instance);
                        Presence.goOffline();
                        com.friendbook.social.push.PushManager.unregister(com.friendbook.social.App.instance);
                        auth().signOut();
                        android.content.Context app = com.friendbook.social.App.instance;
                        if (app != null) {
                            Ui.toast(app, "Your friendbook account has been suspended by an admin.");
                            android.content.Intent i = new android.content.Intent(app,
                                    com.friendbook.social.auth.LoginActivity.class);
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            app.startActivity(i);
                        }
                    }
                });
        meLastRefresh = System.currentTimeMillis();
    }

    /** Live listeners on users/{me}, shared by every screen that needs them. */
    private static final java.util.List<MeObserver> meObservers = new java.util.ArrayList<>();

    /** Called with the current user document whenever {@code users/{me}} changes. */
    public interface MeObserver {
        void onMe(DocumentSnapshot doc);
    }

    /**
     * Subscribe to the already-running {@code users/{me}} listener instead of starting another.
     *
     * <p>Six screens (menu, profile, edit profile, settings, blocked users, feed preferences,
     * monetization, blue tick) each opened their own listener on this same document. Firestore
     * charges per document delivered per listener, so the same unchanged document was billed
     * once per open screen, and every screen had to be trusted to detach correctly. {@link
     * #startMeListener()} already keeps this document live for the whole session; screens now
     * piggyback on it.
     *
     * <p>The observer is invoked immediately with the cached value when one exists, so a screen
     * never has to wait for a snapshot to render.
     */
    public static void observeMe(MeObserver observer) {
        if (observer == null) return;
        synchronized (meObservers) {
            if (!meObservers.contains(observer)) meObservers.add(observer);
        }
        Models.User cached = me;
        if (cached != null) {
            try { observer.onMe(null); } catch (Throwable ignored) { }
        }
        if (meReg == null) startMeListener();
    }

    public static void removeMeObserver(MeObserver observer) {
        if (observer == null) return;
        synchronized (meObservers) { meObservers.remove(observer); }
    }

    /** Delivers a fresh users/{me} document to every subscribed screen. */
    private static void notifyMeObservers(DocumentSnapshot doc) {
        java.util.List<MeObserver> copy;
        synchronized (meObservers) { copy = new java.util.ArrayList<>(meObservers); }
        for (MeObserver o : copy) {
            try { o.onMe(doc); } catch (Throwable ignored) { }
        }
    }

    /** Number of screens currently sharing the me listener. Test/diagnostic only. */
    static int meObserverCount() {
        synchronized (meObservers) { return meObservers.size(); }
    }

    public static void ensureMeFresh() {
        if (uid() != null && (meReg == null || System.currentTimeMillis() - meLastRefresh > 60_000))
            startMeListener();
    }

    public static String myName() {
        if (me != null && me.displayName != null && !me.displayName.isEmpty()) return me.displayName;
        com.google.firebase.auth.FirebaseUser cu = auth().getCurrentUser();
        if (cu != null) {
            if (cu.getDisplayName() != null && !cu.getDisplayName().isEmpty()) return cu.getDisplayName();
            if (cu.getEmail() != null && !cu.getEmail().isEmpty()) return cu.getEmail().split("@")[0];
            if (cu.getPhoneNumber() != null && !cu.getPhoneNumber().isEmpty()) return cu.getPhoneNumber();
        }
        return "User";
    }

    public static String myPhoto() { return me != null && me.photoUrl != null ? me.photoUrl : ""; }

    /** Am I blocking this user? */
    public static boolean iBlocked(String otherUid) {
        return me != null && me.blocked != null && me.blocked.contains(otherUid);
    }

    /** Patch a couple of fields on my user doc (merge). */
    public static void patchMe(Map<String, Object> fields) {
        String uid = uid();
        if (uid == null) return;
        CloudflareStore.put("users", uid, fields);
        db().collection("users").document(uid).set(fields, SetOptions.merge());
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Wait for both tasks. */
    public static Task<Void> all(Task<?> a, Task<?> b) {
        return Tasks.whenAll(a, b);
    }

    /**
     * ⚡ Zero-latency 3-tier user lookup:
     * 1. RAM Cache (`FastCache`) → 0.0ms immediate return!
     * 2. Local Firestore Disk Cache (`Source.CACHE`) → <2ms offline/2G return!
     * 3. Server fallback only on cold miss (and stores into RAM + Disk cache).
     */
    public static Task<Models.User> getUser(String uid) {
        if (uid == null || uid.isEmpty()) return Tasks.forResult(null);
        Models.User ram = FastCache.getUser(uid);
        if (ram != null) return Tasks.forResult(ram);

        TaskCompletionSource<Models.User> tcs = new TaskCompletionSource<>();
        db().collection("users").document(uid).get(Source.CACHE)
                .addOnSuccessListener(d -> {
                    if (d != null && d.exists()) {
                        Models.User u = Models.User.fromDoc(d);
                        FastCache.putUser(u);
                        tcs.trySetResult(u);
                    } else {
                        fetchUserNetwork(uid, tcs);
                    }
                })
                .addOnFailureListener(e -> fetchUserNetwork(uid, tcs));
        return tcs.getTask();
    }

    private static String lastCloudflareProfileKey = "";
    private static final java.util.concurrent.ExecutorService CF_PROFILE_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static void syncProfileToCloudflare(Models.User u) {
        if (!com.friendbook.social.BuildConfig.D1_INTERACTIONS_ENABLED || u == null || u.uid == null) return;
        String dn = u.displayName != null ? u.displayName : "";
        String ph = u.photoUrl != null ? u.photoUrl : "";
        String key = u.uid + "|" + dn + "|" + ph;
        if (key.equals(lastCloudflareProfileKey)) return;
        lastCloudflareProfileKey = key;
        try {
            com.google.firebase.auth.FirebaseUser user = auth().getCurrentUser();
            if (user == null) return;
            user.getIdToken(false).addOnSuccessListener(res -> {
                String token = res != null ? res.getToken() : null;
                if (token == null || token.isEmpty()) return;
                CF_PROFILE_IO.execute(() -> {
                    try {
                        D1HttpTransport.send(
                                new java.net.URL("https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/me"),
                                "PUT", token, D1Client.object("displayName", dn, "photoUrl", ph));
                    } catch (Throwable ignored) { }
                });
            });
        } catch (Throwable ignored) { }
    }

    private static void fetchUserNetwork(String uid, TaskCompletionSource<Models.User> tcs) {
        db().collection("users").document(uid).get()
                .addOnSuccessListener(d -> {
                    Models.User u = (d != null && d.exists()) ? Models.User.fromDoc(d) : null;
                    if (u != null) FastCache.putUser(u);
                    tcs.trySetResult(u);
                })
                .addOnFailureListener(e -> tcs.trySetResult(null));
    }
}
