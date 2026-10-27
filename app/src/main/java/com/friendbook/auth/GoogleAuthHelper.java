package com.friendbook.auth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.util.Patterns;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.friendbook.R;
import com.friendbook.core.Admin;
import com.friendbook.core.Fb;
import com.friendbook.core.Notify;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;
import com.friendbook.main.MainActivity;
import com.friendbook.settings.SessionsActivity;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.OAuthProvider;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 🔐 Direct Google Login & Sign-Up Engine for friendbook:
 * - Supports BOTH native Google Play Services One-Tap/Sign-In (when Web Client ID is present)
 *   AND Firebase's zero-config OAuthProvider("google.com") Custom Tabs flow (works immediately
 *   even when oauth_client in google-services.json is empty — zero DEVELOPER_ERROR 10!).
 * - Automatically provisions new Google accounts in Firestore with default Email privacy = "Only me".
 */
public final class GoogleAuthHelper {

    public static final int RC_GOOGLE_SIGN_IN = 9101;

    private GoogleAuthHelper() {}

    public static boolean isValidEmail(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        return !s.isEmpty() && Patterns.EMAIL_ADDRESS.matcher(s).matches();
    }

    /** Builds a crisp bordered "Continue with Google" button with the 4-color Google G icon. */
    public static LinearLayout buildGoogleButton(Activity a, TextView[] outLabel, Runnable onClick) {
        LinearLayout btn = Ui.row(a);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(Ui.roundStroke(a, Ui.card(a), 10, Ui.div(a), 1));
        btn.setMinimumHeight(Ui.dp(a, 48));
        Ui.pad(btn, 16, 11, 16, 11);

        ImageView gIcon = new ImageView(a);
        gIcon.setImageResource(R.drawable.ic_google_g);
        int gs = Ui.dp(a, 22);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(gs, gs);
        glp.rightMargin = Ui.dp(a, 10);
        btn.addView(gIcon, glp);

        TextView label = Ui.tvBold(a, "Continue with Google", 15, Ui.txt(a));
        btn.addView(label);
        if (outLabel != null && outLabel.length > 0) outLabel[0] = label;

        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    /** Starts Direct Google Login (Native GoogleSignIn if web client ID exists, else Firebase OAuthProvider). */
    public static void startGoogleSignIn(Activity a, View btnView, TextView labelTv, String referralInput) {
        setBusy(btnView, labelTv, true);
        String webClientId = resolveWebClientId(a);
        if (!webClientId.isEmpty()) {
            try {
                GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(webClientId)
                        .requestEmail()
                        .build();
                GoogleSignInClient client = GoogleSignIn.getClient(a, gso);
                // Sign out cached picker state so user can freely pick any Google account on their phone
                client.signOut().addOnCompleteListener(t ->
                        a.startActivityForResult(client.getSignInIntent(), RC_GOOGLE_SIGN_IN));
                return;
            } catch (Throwable ignored) {
                // Fall through to Firebase's direct OAuthProvider("google.com")
            }
        }
        startFirebaseGoogleOAuth(a, btnView, labelTv, referralInput);
    }

    /** Handles native GoogleSignIn result from Activity.onActivityResult; falls back to OAuthProvider on Error 10. */
    public static void handleActivityResult(Activity a, int requestCode, Intent data,
                                            View btnView, TextView labelTv, String referralInput) {
        if (requestCode != RC_GOOGLE_SIGN_IN) return;
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount acct = task.getResult(ApiException.class);
            String idToken = acct != null ? acct.getIdToken() : null;
            if (idToken == null || idToken.isEmpty()) {
                startFirebaseGoogleOAuth(a, btnView, labelTv, referralInput);
                return;
            }
            AuthCredential cred = GoogleAuthProvider.getCredential(idToken, null);
            Fb.auth().signInWithCredential(cred)
                    .addOnSuccessListener(res -> onFirebaseGoogleAuthed(a, res, btnView, labelTv, referralInput))
                    .addOnFailureListener(err -> {
                        setBusy(btnView, labelTv, false);
                        Ui.toast(a, friendlyError(err != null ? err.getMessage() : null));
                    });
        } catch (ApiException e) {
            int code = e.getStatusCode();
            // 12501 = user cancelled picker; 10 / 12500 = SHA-1 not added yet -> seamless fallback to web OAuth!
            if (code == 12501) {
                setBusy(btnView, labelTv, false);
            } else {
                startFirebaseGoogleOAuth(a, btnView, labelTv, referralInput);
            }
        }
    }

    /** Completes any pending OAuth result if the Activity was recreated while Custom Tabs was open. */
    public static void checkPendingResult(Activity a, View btnView, TextView labelTv, String referralInput) {
        try {
            Task<AuthResult> pending = Fb.auth().getPendingAuthResult();
            if (pending != null) {
                setBusy(btnView, labelTv, true);
                pending.addOnSuccessListener(res ->
                                onFirebaseGoogleAuthed(a, res, btnView, labelTv, referralInput))
                        .addOnFailureListener(err -> {
                            setBusy(btnView, labelTv, false);
                            Ui.toast(a, friendlyError(err != null ? err.getMessage() : null));
                        });
            }
        } catch (Throwable ignored) { }
    }

    private static void startFirebaseGoogleOAuth(Activity a, View btnView, TextView labelTv, String referralInput) {
        try {
            OAuthProvider.Builder provider = OAuthProvider.newBuilder("google.com");
            provider.addCustomParameter("prompt", "select_account");
            ListScopes(provider);
            Fb.auth().startActivityForSignInWithProvider(a, provider.build())
                    .addOnSuccessListener(res -> onFirebaseGoogleAuthed(a, res, btnView, labelTv, referralInput))
                    .addOnFailureListener(err -> {
                        setBusy(btnView, labelTv, false);
                        String msg = err != null ? err.getMessage() : null;
                        if (msg != null && (msg.toLowerCase(Locale.US).contains("canceled")
                                || msg.toLowerCase(Locale.US).contains("cancelled"))) {
                            return;
                        }
                        Ui.toast(a, friendlyError(msg));
                    });
        } catch (Throwable t) {
            setBusy(btnView, labelTv, false);
            Ui.toast(a, friendlyError(t.getMessage()));
        }
    }

    private static void ListScopes(OAuthProvider.Builder provider) {
        ArrayList<String> scopes = new ArrayList<>();
        scopes.add("email");
        scopes.add("profile");
        provider.setScopes(scopes);
    }

    private static void onFirebaseGoogleAuthed(Activity a, AuthResult res,
                                               View btnView, TextView labelTv, String referralInput) {
        FirebaseUser fu = res != null ? res.getUser() : Fb.auth().getCurrentUser();
        if (fu == null) fu = Fb.auth().getCurrentUser();
        if (fu == null) {
            setBusy(btnView, labelTv, false);
            Ui.toast(a, "Google sign-in could not complete. Please try again.");
            return;
        }
        final String uid = fu.getUid();
        final String email = fu.getEmail() != null
                ? fu.getEmail().trim().toLowerCase(Locale.US) : "";
        final String rawName = fu.getDisplayName();
        final String fallbackName = !email.isEmpty() && email.contains("@")
                ? email.substring(0, email.indexOf('@')) : "friendbook User";
        final String displayName = (rawName != null && !rawName.trim().isEmpty())
                ? rawName.trim() : fallbackName;
        Uri pUri = fu.getPhotoUrl();
        final String googlePhoto = pUri != null
                ? pUri.toString().replace("=s96-c", "=s400-c") : "";

        Fb.db().collection("users").document(uid).get()
                .addOnSuccessListener(d -> {
                    if (d != null && d.exists()) {
                        finishExistingGoogleUser(a, uid, email, displayName, googlePhoto, d, btnView, labelTv);
                    } else {
                        provisionNewGoogleUser(a, uid, email, displayName, googlePhoto, referralInput, btnView, labelTv);
                    }
                })
                .addOnFailureListener(err -> {
                    // Offline or transient Firestore error: still let authenticated user enter
                    Prefs.saveAccount(a, uid, email, displayName, googlePhoto);
                    Prefs.setUnlockedSession(a, true);
                    openMain(a);
                });
    }

    private static void provisionNewGoogleUser(Activity a, String uid, String email,
                                               String displayName, String googlePhoto,
                                               String referralInput, View btnView, TextView labelTv) {
        Map<String, Object> u = new HashMap<>();
        u.put("displayName", displayName);
        u.put("email", email);
        u.put("phoneNumber", "");
        u.put("photoUrl", googlePhoto);
        u.put("bio", "");
        u.put("coverUrl", "");
        u.put("authProvider", "google");
        // Default email visibility is strictly "Only me" for every newly created account!
        u.put("emailPrivacy", "Only me");
        u.put("contactPrivacy", "Only me");
        u.put("contactInfoPrivacy", "Only me");
        u.put("friendsListPrivacy", "Public");
        u.put("lookupPrivacy", "Everyone");
        u.put("isOnline", true);
        u.put("isAdmin", Admin.isOwnerEmail(email));
        u.put("isVerified", Admin.isOwnerEmail(email));
        u.put("friendsCount", 0);
        u.put("followers", new ArrayList<String>());
        u.put("following", new ArrayList<String>());
        u.put("blockedUsers", new ArrayList<String>());
        u.put("createdAt", FieldValue.serverTimestamp());

        Fb.db().collection("users").document(uid).set(u)
                .addOnSuccessListener(x -> {
                    recordReferralIfPresent(a, uid, displayName, googlePhoto, referralInput);
                    Prefs.saveAccount(a, uid, email, displayName, googlePhoto);
                    Prefs.setUnlockedSession(a, true);
                    SessionsActivity.record();
                    openMain(a);
                })
                .addOnFailureListener(err -> {
                    setBusy(btnView, labelTv, false);
                    Ui.toast(a, friendlyError(err != null ? err.getMessage() : null));
                });
    }

    private static void finishExistingGoogleUser(Activity a, String uid, String email,
                                                 String fallbackName, String googlePhoto,
                                                 DocumentSnapshot d, View btnView, TextView labelTv) {
        boolean banned = Boolean.TRUE.equals(d.getBoolean("banned"));
        if (banned && !Admin.isOwnerEmail(email)) {
            Fb.auth().signOut();
            setBusy(btnView, labelTv, false);
            Ui.info(a, "Account suspended",
                    "This account has been suspended by the friendbook admin. Contact "
                            + Admin.OWNER_EMAIL + " if you think this is a mistake.");
            return;
        }
        String twoFa = d.getString("twoFactorPin");
        if (twoFa != null && !twoFa.isEmpty()) {
            EditText code = Ui.input(a, "Enter Two-Step Security PIN");
            code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            LinearLayout box = Ui.col(a);
            Ui.pad(box, 20, 10, 20, 0);
            box.addView(code, new LinearLayout.LayoutParams(-1, Ui.dp(a, 48)));
            new AlertDialog.Builder(a)
                    .setTitle("🛡 Two-Step Verification")
                    .setMessage("Enter the security PIN enabled on this account.")
                    .setCancelable(false)
                    .setView(box)
                    .setPositiveButton("Verify", (dlg, w) -> {
                        if (twoFa.equals(code.getText().toString().trim())) {
                            completeExistingSignIn(a, uid, email, fallbackName, googlePhoto, d);
                        } else {
                            Fb.auth().signOut();
                            setBusy(btnView, labelTv, false);
                            Ui.toast(a, "Incorrect security PIN");
                        }
                    })
                    .setNegativeButton("Cancel", (dlg, w) -> {
                        Fb.auth().signOut();
                        setBusy(btnView, labelTv, false);
                    })
                    .show();
            return;
        }
        completeExistingSignIn(a, uid, email, fallbackName, googlePhoto, d);
    }

    private static void completeExistingSignIn(Activity a, String uid, String email,
                                               String fallbackName, String googlePhoto,
                                               DocumentSnapshot d) {
        Map<String, Object> patch = new HashMap<>();
        if (Boolean.TRUE.equals(d.getBoolean("deactivated"))) {
            patch.put("deactivated", false);
            Ui.toast(a, "Welcome back! Your account has been reactivated 👋");
        }
        String existingPhoto = d.getString("photoUrl");
        if ((existingPhoto == null || existingPhoto.isEmpty()) && !googlePhoto.isEmpty()) {
            patch.put("photoUrl", googlePhoto);
            existingPhoto = googlePhoto;
        }
        if (!patch.isEmpty()) {
            Fb.db().collection("users").document(uid).set(patch, SetOptions.merge());
        }
        if (Boolean.TRUE.equals(d.getBoolean("loginAlerts"))) {
            Notify.send(uid, "security", "logged in with Google on " + android.os.Build.MODEL, "");
        }
        String name = d.getString("displayName");
        if (name == null || name.isEmpty()) name = fallbackName;
        if (existingPhoto == null) existingPhoto = "";
        Prefs.saveAccount(a, uid, email, name, existingPhoto);
        Prefs.setUnlockedSession(a, true);
        SessionsActivity.record();
        openMain(a);
    }

    public static void recordReferralIfPresent(Activity a, String newUid, String newName,
                                               String newPhoto, String rawCode) {
        String code = Admin.extractRefCode(rawCode);
        if (code == null || code.isEmpty()) {
            code = Admin.extractRefCode(Prefs.pendingReferral(a));
        }
        if (code == null || code.isEmpty() || code.equals(newUid)) return;
        final String refBy = code;
        Prefs.setPendingReferral(a, "");
        Fb.db().collection("users").document(refBy).get().addOnSuccessListener(d -> {
            if (!d.exists()) return;
            Map<String, Object> r = new HashMap<>();
            r.put("refBy", refBy);
            r.put("refTo", newUid);
            r.put("status", "completed");
            r.put("date", FieldValue.serverTimestamp());
            r.put("createdAt", FieldValue.serverTimestamp());
            Fb.db().collection("referrals").document(refBy + "_" + newUid).set(r);

            Map<String, Object> n = new HashMap<>();
            n.put("toUserId", refBy);
            n.put("fromUserId", newUid);
            n.put("fromUserName", newName);
            n.put("fromUserPhotoUrl", newPhoto == null ? "" : newPhoto);
            n.put("type", "referral");
            n.put("text", "joined friendbook via your invite 🎉 (referral credit +1)");
            n.put("isRead", false);
            n.put("createdAt", FieldValue.serverTimestamp());
            Fb.db().collection("notifications").add(n);
        });
    }

    private static String resolveWebClientId(Activity a) {
        try {
            int id = a.getResources().getIdentifier("default_web_client_id", "string", a.getPackageName());
            if (id != 0) {
                String val = a.getString(id).trim();
                if (!val.isEmpty() && val.endsWith(".googleusercontent.com")) return val;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    private static void setBusy(View btnView, TextView labelTv, boolean busy) {
        if (btnView != null) btnView.setEnabled(!busy);
        if (labelTv != null) labelTv.setText(busy ? "Connecting to Google…" : "Continue with Google");
    }

    private static String friendlyError(String m) {
        if (m == null || m.isEmpty()) return "Google sign-in failed. Check your connection.";
        String low = m.toLowerCase(Locale.US);
        if (low.contains("operation-not-allowed") || low.contains("provider is disabled")) {
            return "Enable Google provider in Firebase Console → Authentication → Sign-in method → Google";
        }
        if (low.contains("network")) return "Check your internet connection";
        return m;
    }

    private static void openMain(Activity a) {
        Fb.startMeListener();
        Presence.init();
        a.startActivity(new Intent(a, MainActivity.class));
        a.finishAffinity();
    }
}
