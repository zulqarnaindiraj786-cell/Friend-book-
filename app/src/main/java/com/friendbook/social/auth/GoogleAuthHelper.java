package com.friendbook.social.auth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.CancellationSignal;
import android.text.InputType;
import android.util.Log;
import android.util.Patterns;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.friendbook.social.R;
import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Presence;
import com.friendbook.social.core.Ui;
import com.friendbook.social.main.MainActivity;
import com.friendbook.social.settings.SessionsActivity;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
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
import com.friendbook.social.core.D1Write;

/**
 * 🔐 3-Layer Bulletproof Direct Google Login & Sign-Up Engine (`com.friendbook.social`):
 *   Layer 1: Official Android Credential Manager (`GetSignInWithGoogleOption` bottom sheet)
 *   Layer 2: Google Play Services `GoogleSignInClient` (`requestIdToken` + `requestEmail`)
 *   Layer 3: Direct Firebase `OAuthProvider("google.com")` Custom Tabs (works immediately even
 *            before SHA-1 is added to Firebase Console — zero Error 10 dead-ends!)
 *   - Protects against `onResume()` / `getPendingAuthResult()` race conditions via `sAuthInFlight`.
 *   - Automatically provisions new Google accounts in Firestore with default Email privacy = "Only me".
 */
public final class GoogleAuthHelper {

    private static final String TAG = "GoogleAuthHelper";
    public static final int RC_GOOGLE_SIGN_IN = 9101;

    /** Official Web Client ID (`client_type: 3`) from `friendbook-17551` `google-services.json`. */
    public static final String FIREBASE_WEB_CLIENT_ID =
            "218848343658-5lmkb64ugvkl0a1ibkvu1k8p5e5crq04.apps.googleusercontent.com";

    /** Official Release & Debug APK SHA-1 fingerprint (`app/friendbook-release.p12`). */
    public static final String RELEASE_SHA1 =
            "48:00:EF:58:AD:C6:0B:A3:3E:FC:1B:D8:E5:1B:60:A3:76:D7:B2:DF";

    private static volatile boolean sAuthInFlight = false;
    private static volatile boolean sNativeSha1Missing = false;

    private GoogleAuthHelper() {}

    public static boolean isAuthInFlight() {
        return sAuthInFlight;
    }

    public static void resetInFlight() {
        sAuthInFlight = false;
    }

    public static boolean isValidEmail(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        return !s.isEmpty() && Patterns.EMAIL_ADDRESS.matcher(s).matches();
    }

    /** Clears cached Google Sign-In / Credential Manager session on logout so user can switch accounts. */
    public static void signOutGoogle(Context c) {
        if (c == null) return;
        try {
            String webClientId = resolveWebClientId(c);
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .build();
            GoogleSignIn.getClient(c.getApplicationContext(), gso).signOut();
        } catch (Throwable ignored) {}
        try {
            CredentialManager cm = CredentialManager.create(c.getApplicationContext());
            cm.clearCredentialStateAsync(
                    new ClearCredentialStateRequest(),
                    new CancellationSignal(),
                    ContextCompat.getMainExecutor(c),
                    new CredentialManagerCallback<Void, ClearCredentialException>() {
                        @Override public void onResult(Void result) {}
                        @Override public void onError(ClearCredentialException e) {}
                    });
        } catch (Throwable ignored) {}
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

    /** Entry point: tries Credential Manager -> Legacy GoogleSignIn -> Firebase OAuthProvider. */
    public static void startGoogleSignIn(Activity a, View btnView, TextView labelTv, String referralInput) {
        if (a == null || a.isFinishing()) return;
        sAuthInFlight = true;
        setBusy(btnView, labelTv, true);

        if (sNativeSha1Missing) {
            startFirebaseGoogleOAuth(a, btnView, labelTv, referralInput);
            return;
        }

        final String webClientId = resolveWebClientId(a);
        try {
            GetSignInWithGoogleOption googleOpt = new GetSignInWithGoogleOption.Builder(webClientId)
                    .build();
            GetCredentialRequest req = new GetCredentialRequest.Builder()
                    .addCredentialOption(googleOpt)
                    .build();
            CredentialManager cm = CredentialManager.create(a);
            cm.getCredentialAsync(
                    a,
                    req,
                    new CancellationSignal(),
                    ContextCompat.getMainExecutor(a),
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse response) {
                            handleCredentialManagerResponse(a, response, btnView, labelTv, referralInput);
                        }

                        @Override
                        public void onError(GetCredentialException e) {
                            if (e instanceof GetCredentialCancellationException) {
                                sAuthInFlight = false;
                                setBusy(btnView, labelTv, false);
                                return;
                            }
                            Log.w(TAG, "CredentialManager fallback to GoogleSignInClient: " + e.getMessage());
                            startLegacyGoogleSignIn(a, webClientId, btnView, labelTv, referralInput);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "CredentialManager init fallback: " + t.getMessage());
            startLegacyGoogleSignIn(a, webClientId, btnView, labelTv, referralInput);
        }
    }

    private static void handleCredentialManagerResponse(Activity a, GetCredentialResponse response,
                                                        View btnView, TextView labelTv, String referralInput) {
        try {
            Credential cred = response != null ? response.getCredential() : null;
            if (cred instanceof CustomCredential
                    && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(cred.getType())) {
                GoogleIdTokenCredential gid = GoogleIdTokenCredential.createFrom(cred.getData());
                String idToken = gid.getIdToken();
                if (idToken != null && !idToken.isEmpty()) {
                    signInFirebaseWithIdToken(a, idToken, btnView, labelTv, referralInput);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "GoogleIdTokenCredential parse fallback: " + t.getMessage());
        }
        startLegacyGoogleSignIn(a, resolveWebClientId(a), btnView, labelTv, referralInput);
    }

    private static void startLegacyGoogleSignIn(Activity a, String webClientId,
                                                View btnView, TextView labelTv, String referralInput) {
        try {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .requestProfile()
                    .build();
            GoogleSignInClient client = GoogleSignIn.getClient(a, gso);
            client.signOut().addOnCompleteListener(t -> {
                if (!a.isFinishing()) {
                    try {
                        a.startActivityForResult(client.getSignInIntent(), RC_GOOGLE_SIGN_IN);
                    } catch (Throwable err) {
                        startFirebaseGoogleOAuth(a, btnView, labelTv, referralInput);
                    }
                }
            });
        } catch (Throwable t) {
            startFirebaseGoogleOAuth(a, btnView, labelTv, referralInput);
        }
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
            signInFirebaseWithIdToken(a, idToken, btnView, labelTv, referralInput);
        } catch (ApiException e) {
            int code = e.getStatusCode();
            Log.w(TAG, "GoogleSignIn ApiException statusCode=" + code, e);
            if (code == 12501) {
                // User pressed Back / cancelled picker
                sAuthInFlight = false;
                setBusy(btnView, labelTv, false);
            } else {
                // 10 = DEVELOPER_ERROR (SHA-1 not added yet), 12500 = SIGN_IN_FAILED, 8 = INTERNAL_ERROR
                if (code == 10 || code == 12500) sNativeSha1Missing = true;
                startFirebaseGoogleOAuth(a, btnView, labelTv, referralInput);
            }
        } catch (Throwable t) {
            startFirebaseGoogleOAuth(a, btnView, labelTv, referralInput);
        }
    }

    private static void signInFirebaseWithIdToken(Activity a, String idToken,
                                                  View btnView, TextView labelTv, String referralInput) {
        AuthCredential cred = GoogleAuthProvider.getCredential(idToken, null);
        Fb.auth().signInWithCredential(cred)
                .addOnSuccessListener(res -> onFirebaseGoogleAuthed(a, res, btnView, labelTv, referralInput))
                .addOnFailureListener(err -> {
                    sAuthInFlight = false;
                    setBusy(btnView, labelTv, false);
                    Ui.toast(a, friendlyError(err != null ? err.getMessage() : null));
                });
    }

    /**
     * Completes any pending OAuth result ONLY if the Activity was killed & recreated by Android
     * while Custom Tabs was open (`savedInstanceState != null && !sAuthInFlight`).
     */
    public static void checkPendingResultOnRecreate(Activity a, View btnView, TextView labelTv, String referralInput) {
        if (sAuthInFlight) return;
        try {
            Task<AuthResult> pending = Fb.auth().getPendingAuthResult();
            if (pending != null) {
                sAuthInFlight = true;
                setBusy(btnView, labelTv, true);
                pending.addOnSuccessListener(res ->
                                onFirebaseGoogleAuthed(a, res, btnView, labelTv, referralInput))
                        .addOnFailureListener(err -> {
                            sAuthInFlight = false;
                            setBusy(btnView, labelTv, false);
                            String msg = err != null ? err.getMessage() : null;
                            if (!isCancelMessage(msg)) Ui.toast(a, friendlyError(msg));
                        });
            }
        } catch (Throwable ignored) {}
    }

    private static void startFirebaseGoogleOAuth(Activity a, View btnView, TextView labelTv, String referralInput) {
        if (a == null || a.isFinishing()) {
            sAuthInFlight = false;
            return;
        }
        try {
            sAuthInFlight = true;
            OAuthProvider.Builder provider = OAuthProvider.newBuilder("google.com");
            provider.addCustomParameter("prompt", "select_account");
            ArrayList<String> scopes = new ArrayList<>();
            scopes.add("email");
            scopes.add("profile");
            provider.setScopes(scopes);

            Fb.auth().startActivityForSignInWithProvider(a, provider.build())
                    .addOnSuccessListener(res -> onFirebaseGoogleAuthed(a, res, btnView, labelTv, referralInput))
                    .addOnFailureListener(err -> {
                        sAuthInFlight = false;
                        setBusy(btnView, labelTv, false);
                        String msg = err != null ? err.getMessage() : null;
                        if (isCancelMessage(msg)) return;
                        showDetailedAuthHelpIfNeeded(a, msg);
                    });
        } catch (Throwable t) {
            sAuthInFlight = false;
            setBusy(btnView, labelTv, false);
            showDetailedAuthHelpIfNeeded(a, t.getMessage());
        }
    }

    private static boolean isCancelMessage(String msg) {
        if (msg == null) return false;
        String low = msg.toLowerCase(Locale.US);
        return low.contains("canceled") || low.contains("cancelled") || low.contains("user cancelled");
    }

    private static void showDetailedAuthHelpIfNeeded(Activity a, String rawMsg) {
        String low = rawMsg == null ? "" : rawMsg.toLowerCase(Locale.US);
        if (sNativeSha1Missing || low.contains("cert") || low.contains("sha")
                || low.contains("developer_error") || low.contains("initial state")) {
            new AlertDialog.Builder(a)
                    .setTitle("🔐 Add SHA-1 in Firebase Console")
                    .setMessage("To enable instant 1-tap Google Sign-In for package com.friendbook.social, add this SHA-1 fingerprint in:\n\n"
                            + "Firebase Console → Project Settings → Your apps → com.friendbook.social → Add fingerprint:\n\n"
                            + RELEASE_SHA1)
                    .setPositiveButton("📋 Copy SHA-1", (d, w) -> Ui.copy(a, RELEASE_SHA1))
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        Ui.toast(a, friendlyError(rawMsg));
    }

    /** Ensures a signed-in FirebaseUser has a complete `users/{uid}` Firestore profile before entering MainActivity. */
    public static void ensureUserDocAndEnter(Activity a, View btnView, TextView labelTv, String referralInput) {
        onFirebaseGoogleAuthed(a, null, btnView, labelTv, referralInput);
    }

    private static void onFirebaseGoogleAuthed(Activity a, AuthResult res,
                                               View btnView, TextView labelTv, String referralInput) {
        FirebaseUser fu = res != null ? res.getUser() : Fb.auth().getCurrentUser();
        if (fu == null) fu = Fb.auth().getCurrentUser();
        if (fu == null) {
            sAuthInFlight = false;
            setBusy(btnView, labelTv, false);
            Ui.toast(a, "Google sign-in could not complete. Please try again.");
            return;
        }
        final String uid = fu.getUid();
        final String email = resolveGoogleEmail(fu);
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
                    sAuthInFlight = false;
                    Prefs.saveAccount(a, uid, email, displayName, googlePhoto);
                    Prefs.setUnlockedSession(a, true);
                    openMain(a);
                });
    }

    public static String resolveGoogleEmail(FirebaseUser fu) {
        if (fu == null) return "";
        if (fu.getEmail() != null && !fu.getEmail().trim().isEmpty()) {
            return fu.getEmail().trim().toLowerCase(Locale.US);
        }
        if (fu.getProviderData() != null) {
            for (com.google.firebase.auth.UserInfo info : fu.getProviderData()) {
                if (info != null && info.getEmail() != null && !info.getEmail().trim().isEmpty()) {
                    return info.getEmail().trim().toLowerCase(Locale.US);
                }
            }
        }
        return "";
    }

    private static void provisionNewGoogleUser(Activity a, String uid, String email,
                                               String displayName, String googlePhoto,
                                               String referralInput, View btnView, TextView labelTv) {
        Map<String, Object> u = buildDefaultGoogleUserMap(uid, email, displayName, googlePhoto);
        // WRITE = Cloudflare: the profile of a user who signs in on this build is a D1 row, in the
        // same store every read of this build already looks at first.
        com.friendbook.social.core.CloudflareDocs.writeDocument("users", uid, u, () -> {
                    sAuthInFlight = false;
                    recordReferralIfPresent(a, uid, displayName, googlePhoto, referralInput);
                    Prefs.saveAccount(a, uid, email, displayName, googlePhoto);
                    Prefs.setUnlockedSession(a, true);
                    SessionsActivity.record();
                    openMain(a);
                }, reason -> {
                    sAuthInFlight = false;
                    // The account exists in Firebase Auth; the profile row is retried, never lost.
                    com.friendbook.social.core.CloudflareOutbox.enqueue(a, "users", uid,
                            com.friendbook.social.core.CloudflareDocs.sanitize(u));
                    Prefs.saveAccount(a, uid, email, displayName, googlePhoto);
                    Prefs.setUnlockedSession(a, true);
                    SessionsActivity.record();
                    openMain(a);
                },
                () -> Fb.db().collection("users").document(uid).set(u, SetOptions.merge())
                .addOnSuccessListener(x -> {
                    sAuthInFlight = false;
                    recordReferralIfPresent(a, uid, displayName, googlePhoto, referralInput);
                    Prefs.saveAccount(a, uid, email, displayName, googlePhoto);
                    Prefs.setUnlockedSession(a, true);
                    SessionsActivity.record();
                    openMain(a);
                })
                .addOnFailureListener(err -> {
                    sAuthInFlight = false;
                    setBusy(btnView, labelTv, false);
                    Ui.toast(a, friendlyError(err != null ? err.getMessage() : null));
                }));
    }

    public static Map<String, Object> buildDefaultGoogleUserMap(String uid, String email,
                                                                String displayName, String googlePhoto) {
        Map<String, Object> u = new HashMap<>();
        u.put("displayName", displayName);
        u.put("email", email);
        u.put("phoneNumber", "");
        u.put("photoUrl", googlePhoto == null ? "" : googlePhoto);
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
        return u;
    }

    private static void finishExistingGoogleUser(Activity a, String uid, String email,
                                                 String fallbackName, String googlePhoto,
                                                 DocumentSnapshot d, View btnView, TextView labelTv) {
        boolean banned = Boolean.TRUE.equals(d.getBoolean("banned"));
        if (banned && !Admin.isOwnerEmail(email)) {
            sAuthInFlight = false;
            com.friendbook.social.push.PushManager.unregister(com.friendbook.social.App.instance);
                    Fb.auth().signOut();
            signOutGoogle(a);
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
                            sAuthInFlight = false;
                            completeExistingSignIn(a, uid, email, fallbackName, googlePhoto, d);
                        } else {
                            sAuthInFlight = false;
                            com.friendbook.social.push.PushManager.unregister(com.friendbook.social.App.instance);
                    Fb.auth().signOut();
                            setBusy(btnView, labelTv, false);
                            Ui.toast(a, "Incorrect security PIN");
                        }
                    })
                    .setNegativeButton("Cancel", (dlg, w) -> {
                        sAuthInFlight = false;
                        com.friendbook.social.push.PushManager.unregister(com.friendbook.social.App.instance);
                    Fb.auth().signOut();
                        setBusy(btnView, labelTv, false);
                    })
                    .show();
            return;
        }
        sAuthInFlight = false;
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
            // WRITE = Cloudflare (the profile row lives there for this build).
            com.friendbook.social.core.CloudflareDocs.writeDocument("users", uid, patch, null, null,
                    () -> Fb.db().collection("users").document(uid).set(patch, SetOptions.merge()));
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
            D1Write.write("referrals", refBy + "_" + newUid, Fb.map("refBy", refBy, "refTo", newUid, "status", "completed"), null, null, () -> Fb.db().collection("referrals").document(refBy + "_" + newUid).set(r));

            Map<String, Object> n = new HashMap<>();
            n.put("toUserId", refBy);
            n.put("fromUserId", newUid);
            n.put("fromUserName", newName);
            n.put("fromUserPhotoUrl", newPhoto == null ? "" : newPhoto);
            n.put("type", "referral");
            n.put("text", "joined friendbook via your invite 🎉 (referral credit +1)");
            n.put("isRead", false);
            n.put("createdAt", FieldValue.serverTimestamp());
            D1Write.add("notifications", Fb.map("toUserId", refBy, "fromUserId", newUid, "fromUserName", newName, "fromUserPhotoUrl", newPhoto == null ? "" : newPhoto, "type", "referral", "text", "joined friendbook via your invite 🎉 (referral credit +1)", "isRead", false), null, null, () -> Fb.db().collection("notifications").add(n));
        });
    }

    public static String resolveWebClientId(Context c) {
        if (c != null) {
            try {
                int id = c.getResources().getIdentifier("default_web_client_id", "string", c.getPackageName());
                if (id != 0) {
                    String val = c.getString(id).trim();
                    if (!val.isEmpty() && val.endsWith(".googleusercontent.com")) return val;
                }
            } catch (Throwable ignored) {}
        }
        return FIREBASE_WEB_CLIENT_ID;
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
