package com.friendbook.social.core;

import android.content.Context;
import android.net.Uri;

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
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

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
        // Instant 0ms cold-start hydration of signed-in user from local snapshot
        String currentUid = uid();
        Models.User cachedMe = FastCache.getMe();
        if (currentUid != null && cachedMe != null && currentUid.equals(cachedMe.uid)) {
            me = cachedMe;
        }
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

    public static StorageReference storage() {
        return FirebaseStorage.getInstance().getReference();
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

    /** Upload a local file and resolve its download URL. */
    public static Task<Uri> upload(String path, Uri file) {
        StorageReference ref = storage().child(path);
        UploadTask up = ref.putFile(file);
        return up.continueWithTask(t -> {
            if (!t.isSuccessful()) throw t.getException();
            return ref.getDownloadUrl();
        });
    }

    /** Start (or restart) the live listener that keeps {@link #me} fresh. */
    public static void startMeListener() {
        String uid = uid();
        if (uid == null) return;
        if (me == null) {
            Models.User cachedMe = FastCache.getMe();
            if (cachedMe != null && uid.equals(cachedMe.uid)) me = cachedMe;
        }
        if (meReg != null) meReg.remove();
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
                    if (u.banned && !Admin.isOwnerEmail(u.email)) {
                        me = null;
                        FastCache.clearAll(com.friendbook.social.App.instance);
                        Presence.goOffline();
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
