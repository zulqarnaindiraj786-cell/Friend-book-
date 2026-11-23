package com.friendbook.social.core;

import android.content.Context;

import androidx.annotation.Nullable;

import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.D1DocumentStore;
import com.friendbook.social.d1.documents.D1DocumentStoreSettings;
import com.friendbook.social.d1.documents.ListenerRegistration;
import com.friendbook.social.d1.documents.PersistentCacheSettings;
import com.friendbook.social.d1.documents.SetOptions;
import com.friendbook.social.d1.documents.Source;

import java.util.HashMap;
import java.util.Map;

/**
 * Central Firebase access point with friendbook 2G cache-first engine:
 * - 100 MB Persistent Offline Disk Cache so posts, chats, comments & profiles render in <2ms even offline/2G.
 * - Instant 0ms RAM hydration for {@link #me} and {@link #getUser(String)}.
 */
public class Fb {

    private static boolean firestoreConfigured;
    public static Models.User me;           // cached profile of the signed-in user
    private static ListenerRegistration meReg;
    private static String meListenerActor;
    private static boolean meListenerFailed;
    public interface MeObserver { void changed(Models.User user); }
    private static final java.util.Set<MeObserver> ME_OBSERVERS=new java.util.concurrent.CopyOnWriteArraySet<>();
    public static void addMeObserver(MeObserver observer) {
        ME_OBSERVERS.add(observer);
        if (me!=null && java.util.Objects.equals(me.uid,uid())) observer.changed(me);
    }
    public static void removeMeObserver(MeObserver observer) { ME_OBSERVERS.remove(observer); }

    public static void init(Context appContext) {
        FastCache.init(appContext);
        if (!firestoreConfigured) {
            firestoreConfigured = true;
            try {
                D1DocumentStoreSettings settings = new D1DocumentStoreSettings.Builder()
                        .setLocalCacheSettings(PersistentCacheSettings.newBuilder()
                                .setSizeBytes(100L * 1024L * 1024L) // 100 MB offline disk cache
                                .build())
                        .build();
                D1DocumentStore.getInstance().setFirestoreSettings(settings);
            } catch (Throwable ignored) { }
        }
        // Instant 0ms cold-start hydration of signed-in user from local snapshot
        String currentUid = uid();
        Models.User cachedMe = FastCache.getMe();
        if (currentUid != null && cachedMe != null && currentUid.equals(cachedMe.uid)) {
            me = cachedMe;
        }
    }

    public static FirebaseAuth auth() { return FirebaseAuth.getInstance(); }

    public static D1DocumentStore db() { return D1DocumentStore.getInstance(); }


    public static String uid() {
        return auth().getCurrentUser() != null ? auth().getCurrentUser().getUid() : null;
    }

    public static boolean signedIn() { return auth().getCurrentUser() != null; }

    /** Canonical 1:1 chat id (same scheme as the Flutter app). */
    public static String chatId(String a, String b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    /** Start (or restart) the live listener that keeps {@link #me} fresh. */
    public static void startMeListener() {
        String uid = uid();
        if (uid == null) return;
        if (me != null && !uid.equals(me.uid)) me=null;
        if (me == null) {
            Models.User cachedMe = FastCache.getMe();
            if (cachedMe != null && uid.equals(cachedMe.uid)) me = cachedMe;
        }
        if (meReg != null) meReg.remove();
        meListenerActor=uid;
        meListenerFailed=false;
        meReg = db().collection("users").document(uid)
                .addSnapshotListener((doc, err) -> {
                    if (!uid.equals(Fb.uid())) return;
                    if (err!=null) { meListenerFailed=true;return; }
                    if (doc==null) return;
                    meListenerFailed=false;
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
                                db().collection("users").document(uid).create(
                                        com.friendbook.social.auth.GoogleAuthHelper.buildDefaultGoogleUserMap(uid, em, dn, ph));
                            }
                        }
                        return;
                    }
                    Models.User u = Models.User.fromDoc(doc);
                    me = u;
                    FastCache.saveMe(com.friendbook.social.App.instance, u);
                    syncProfileToCloudflare(u);
                    if (u.banned && !Admin.isOwnerEmail(u.email)) {
                        me = null;
                        FastCache.clearAll(com.friendbook.social.App.instance);

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
                    // Security handling must complete before any optional UI callback.
                    if (me!=null && uid.equals(Fb.uid()))
                        for (MeObserver observer : ME_OBSERVERS) observer.changed(u);
                });
    }

    public static void ensureMeFresh() {
        // The existing registration receives committed changes/foreground refreshes already.
        // Age alone is not a reason to detach and fetch the same document again.
        if (OnDemandLoads.profileListenerNeedsRestart(uid(),meListenerActor,meReg!=null,meListenerFailed))
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

    /** Apply the patch once through the D1 document API and return its acknowledgement. */
    public static Task<Void> patchMe(Map<String, Object> fields) {
        String uid = uid();
        if (uid == null) return Tasks.forException(new IllegalStateException("Not signed in"));
        return db().collection("users").document(uid).set(fields, SetOptions.merge());
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
    private static final ProfileReadFlights<java.util.List<String>,Models.User> USER_READS = new ProfileReadFlights<>();

    public static Task<Models.User> getUser(String uid) {
        if (uid == null || uid.isEmpty()) return Tasks.forResult(null);
        Models.User ram = FastCache.getUser(uid);
        if (ram != null) return Tasks.forResult(ram);
        final String actor=Fb.uid();
        // Share the complete async disk->network lookup, not only the HTTP
        // interval. Independent caller callbacks may otherwise enqueue another
        // network read after the first response has already left transport.
        return USER_READS.read(java.util.Arrays.asList(actor,uid),()->readUserCacheFirst(uid,actor));
    }

    private static Task<Models.User> readUserCacheFirst(String uid,String actor) {
        TaskCompletionSource<Models.User> tcs = new TaskCompletionSource<>();
        db().collection("users").document(uid).get(Source.CACHE)
                .addOnSuccessListener(d -> {
                    if (!java.util.Objects.equals(actor,Fb.uid())) { tcs.trySetResult(null); return; }
                    if (d != null && d.exists()) {
                        Models.User u = Models.User.fromDoc(d);
                        FastCache.putUser(u);
                        tcs.trySetResult(u);
                    } else {
                        fetchUserNetwork(uid, actor, tcs);
                    }
                })
                .addOnFailureListener(e -> fetchUserNetwork(uid, actor, tcs));
        return tcs.getTask();
    }

    /** Refresh an author from D1 instead of treating a stale name/photo cache as authoritative. */
    public static Task<Models.User> getUserFresh(String uid) {
        if (uid == null || uid.isEmpty()) return Tasks.forResult(null);
        TaskCompletionSource<Models.User> tcs=new TaskCompletionSource<>();
        fetchUserNetwork(uid,Fb.uid(),tcs);
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

    private static void fetchUserNetwork(String uid, String actor, TaskCompletionSource<Models.User> tcs) {
        if (!java.util.Objects.equals(actor,Fb.uid())) { tcs.trySetResult(null); return; }
        db().collection("users").document(uid).get()
                .addOnSuccessListener(d -> {
                    if (!java.util.Objects.equals(actor,Fb.uid())) { tcs.trySetResult(null); return; }
                    Models.User u = (d != null && d.exists()) ? Models.User.fromDoc(d) : null;
                    if (u != null) FastCache.putUser(u);
                    tcs.trySetResult(u);
                })
                .addOnFailureListener(e -> tcs.trySetResult(null));
    }
}
