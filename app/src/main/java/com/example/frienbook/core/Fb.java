package com.example.frienbook.core;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.example.frienbook.models.Models;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/** Central Firebase access point. Auth is the product here; app data lives on Cloudflare. */
public class Fb {

    public static Models.User me;           // cached profile of the signed-in user
    private static ListenerRegistration meReg;
    private static long meLastRefresh;

    public static void init(Context appContext) {
        // Firebase is sign-in only: google-services.json wires FirebaseAuth. Every other byte of app
        // state lives on Cloudflare (D1 documents, R2 media, Analytics Engine views, presence).
    }

    public static FirebaseAuth auth() { return FirebaseAuth.getInstance(); }

    public static FirebaseFirestore db() { return FirebaseFirestore.getInstance(); }


    @Nullable
    public static String uid() {
        return auth().getCurrentUser() != null ? auth().getCurrentUser().getUid() : null;
    }

    public static boolean signedIn() { return auth().getCurrentUser() != null; }

    /** Canonical 1:1 chat id (same scheme as the Flutter app). */
    public static String chatId(String a, String b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
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
        CloudflareDocs.mirror("users", uid, fields);
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
