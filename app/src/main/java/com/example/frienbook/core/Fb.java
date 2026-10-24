package com.example.frienbook.core;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.example.frienbook.models.Models;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.util.HashMap;
import java.util.Map;

/** Central Firebase access point: Auth, Firestore, Realtime Database, Storage. */
public class Fb {

    public static final String RTDB_URL = "https://friendbook-17551-default-rtdb.firebaseio.com";

    private static FirebaseDatabase rtdb;
    private static boolean rtdbTried;
    public static Models.User me;           // cached profile of the signed-in user
    private static ListenerRegistration meReg;
    private static long meLastRefresh;

    public static void init(Context appContext) {
        // Nothing special needed: google-services.json wires everything through
        // the google-services Gradle plugin. Persistence is ON by default.
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
            // touch to validate
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

    /** Start (or restart) the live listener that keeps {@link #me} fresh.
     *  Also enforces admin bans: banned accounts are signed out immediately. */
    public static void startMeListener() {
        String uid = uid();
        if (uid == null) return;
        if (meReg != null) meReg.remove();
        meReg = db().collection("users").document(uid)
                .addSnapshotListener((doc, err) -> {
                    if (doc == null || !doc.exists()) return;
                    Models.User u = Models.User.fromDoc(doc);
                    me = u;
                    if (u.banned && !Admin.isOwnerEmail(u.email)) {
                        // ban enforcement: kick the user out to the login screen
                        me = null;
                        Presence.goOffline();
                        auth().signOut();
                        android.content.Context app = com.example.frienbook.App.instance;
                        if (app != null) {
                            Ui.toast(app, "Your friendbook account has been suspended by an admin.");
                            android.content.Intent i = new android.content.Intent(app,
                                    com.example.frienbook.auth.LoginActivity.class);
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
        return auth().getCurrentUser() != null && auth().getCurrentUser().getEmail() != null
                ? auth().getCurrentUser().getEmail().split("@")[0] : "User";
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

    /** Fetch a user doc once and map it. */
    public static Task<Models.User> getUser(String uid) {
        return db().collection("users").document(uid).get().continueWith(t -> {
            DocumentSnapshot d = t.getResult();
            return d != null && d.exists() ? Models.User.fromDoc(d) : null;
        });
    }
}
