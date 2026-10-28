package com.friendbook.social.auth;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Presence;
import com.friendbook.social.core.Ui;
import com.friendbook.social.main.MainActivity;
import com.friendbook.social.settings.SessionsActivity;
import com.google.firebase.firestore.FieldValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ✨ friendbook Sign Up Screen:
 * - Direct 1-tap "Continue with Google" sign-up / login.
 * - Email + Password sign-up.
 * - Default Email visibility is automatically set to "Only me" for every newly created account!
 */
public class RegisterActivity extends Activity {

    private EditText name, email, pass, pass2, refCode;
    private CheckBox ugcTermsCheck;
    private LinearLayout googleBtn;
    private TextView googleLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarWhite(this);

        LinearLayout screen = Ui.col(this);
        screen.setBackgroundColor(Ui.bg(this));
        screen.addView(Ui.pageBar(this, "Create account"));

        LinearLayout root = Ui.col(this);
        Ui.pad(root, 24, 18, 24, 24);

        TextView title = Ui.tvBold(this, "Join friendbook", 25, Ui.txt(this));
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = Ui.tv(this, "Sign up quickly with Google or your email address.", 13, Ui.sub(this));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));
        root.addView(sub);

        // 🔐 1-Tap Continue with Google
        TextView[] gOut = new TextView[1];
        googleBtn = GoogleAuthHelper.buildGoogleButton(this, gOut, () -> {
            if (ugcTermsCheck != null && !ugcTermsCheck.isChecked()) {
                Ui.confirm(this, "Terms & Community Safety",
                        "By continuing with Google, you confirm you are 13+ years old and agree to friendbook's Terms of Service, Privacy Policy & Zero-Tolerance Community Safety Rules.",
                        "Agree & Continue", () -> {
                            ugcTermsCheck.setChecked(true);
                            GoogleAuthHelper.startGoogleSignIn(this, googleBtn, googleLabel,
                                    refCode != null ? refCode.getText().toString() : null);
                        });
            } else {
                GoogleAuthHelper.startGoogleSignIn(this, googleBtn, googleLabel,
                        refCode != null ? refCode.getText().toString() : null);
            }
        });
        googleLabel = gOut[0];
        root.addView(googleBtn, lp(0));

        TextView orDiv = Ui.tv(this, "──────────  or sign up with email  ──────────", 12, Ui.sub(this));
        orDiv.setGravity(Gravity.CENTER);
        orDiv.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        root.addView(orDiv);

        name = Ui.input(this, "Full name");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        root.addView(name, lp(0));

        email = Ui.input(this, "Email address");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(email, lp(12));

        pass = Ui.input(this, "New password (6+ characters)");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(pass, lp(12));

        pass2 = Ui.input(this, "Confirm password");
        pass2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(pass2, lp(12));

        refCode = Ui.input(this, "Invite code (optional)");
        TextView refHint = Ui.tv(this, "Got an invite link? Paste it — your friend gets referral credit.", 11, Ui.sub(this));
        refHint.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), 0, 0);
        root.addView(refCode, lp(10));
        root.addView(refHint);
        String pending = Prefs.pendingReferral(this);
        if (!pending.isEmpty()) {
            refCode.setText(Admin.inviteLink(pending));
            refHint.setText("Invite detected — your friend gets referral credit 🎉");
            refHint.setTextColor(Ui.BLUE);
        }

        // 🛡 Mandatory UGC & Child Safety Consent Checkbox
        LinearLayout termsBox = Ui.row(this);
        termsBox.setGravity(Gravity.TOP);
        termsBox.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 2));
        ugcTermsCheck = new CheckBox(this);
        termsBox.addView(ugcTermsCheck);

        LinearLayout termsCol = Ui.col(this);
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(0, -2, 1);
        tclp.leftMargin = Ui.dp(this, 6);
        TextView termsTv = Ui.tv(this,
                "I am 13+ years old and agree to friendbook's Terms of Service, Privacy Policy & Zero-Tolerance Community Safety Rules (no nudity, explicit media, abuse, hate speech, or CSAE).",
                12, Ui.txt(this));
        termsTv.setOnClickListener(v -> ugcTermsCheck.setChecked(!ugcTermsCheck.isChecked()));
        termsCol.addView(termsTv);
        TextView readTermsLink = Ui.tvBold(this,
                "📄 Read Full Terms, Privacy Policy & Child Safety Standards", 11.5f, Ui.BLUE);
        readTermsLink.setPadding(0, Ui.dp(this, 4), 0, 0);
        readTermsLink.setOnClickListener(v -> Ui.info(this,
                "friendbook Terms, UGC & Child Safety Policy",
                "1. Zero-Tolerance UGC Policy: Uploading sexually explicit photos/videos, nudity, graphic violence, hate speech, harassment, or illegal content is strictly prohibited. Violating posts are automatically deleted and offending accounts are permanently suspended.\n\n"
                        + "2. Zero-Tolerance Child Safety (CSAE): friendbook enforces strict zero tolerance against Child Sexual Abuse & Exploitation (CSAE/CSAM). Any violation is immediately removed and reported to relevant authorities.\n\n"
                        + "3. Reporting & Blocking: Every post, reel, story, and message includes 1-tap Report (🚩) and Block User (🚫) controls.\n\n"
                        + "4. Privacy & Data Control: Your email defaults to 'Only me' for maximum privacy, and you can freely change privacy settings or delete your account anytime in Settings."));
        termsCol.addView(readTermsLink);
        termsBox.addView(termsCol, tclp);
        root.addView(termsBox);

        TextView create = Ui.btn(this, "Sign Up");
        root.addView(create, lp(14));
        create.setOnClickListener(v -> doRegister(create));

        TextView have = Ui.tvBold(this, "Already have an account? Log In", 14, Ui.BLUE);
        have.setGravity(Gravity.CENTER);
        have.setPadding(0, Ui.dp(this, 18), 0, 0);
        have.setOnClickListener(v -> finish());
        root.addView(have);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        screen.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(screen);

        if (b != null) {
            GoogleAuthHelper.checkPendingResultOnRecreate(this, googleBtn, googleLabel,
                    refCode != null ? refCode.getText().toString() : null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (GoogleAuthHelper.isAuthInFlight()) return;
        if (Fb.signedIn()) {
            GoogleAuthHelper.ensureUserDocAndEnter(this, googleBtn, googleLabel,
                    refCode != null ? refCode.getText().toString() : null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        GoogleAuthHelper.handleActivityResult(this, requestCode, data, googleBtn, googleLabel,
                refCode != null ? refCode.getText().toString() : null);
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        p.topMargin = Ui.dp(this, topDp);
        return p;
    }

    private void doRegister(TextView btn) {
        String n = name.getText().toString().trim();
        String e = email.getText().toString().trim().toLowerCase(Locale.US);
        String p = pass.getText().toString();
        String p2 = pass2.getText().toString();
        if (n.length() < 2) { Ui.toast(this, "Enter your name"); return; }
        if (!GoogleAuthHelper.isValidEmail(e)) { Ui.toast(this, "Enter a valid email address"); return; }
        if (p.length() < 6) { Ui.toast(this, "Password must be 6+ characters"); return; }
        if (!p.equals(p2)) { Ui.toast(this, "Passwords don't match"); return; }
        if (ugcTermsCheck == null || !ugcTermsCheck.isChecked()) {
            Ui.toast(this, "Please accept the Terms, Privacy & Safety Policy to continue");
            return;
        }

        btn.setText("Creating account…");
        btn.setEnabled(false);
        Ui.hideKeyboard(this);

        Fb.auth().createUserWithEmailAndPassword(e, p)
                .addOnSuccessListener(res -> {
                    String uid = res.getUser().getUid();
                    Map<String, Object> u = new HashMap<>();
                    u.put("displayName", n);
                    u.put("email", e);
                    u.put("phoneNumber", "");
                    u.put("photoUrl", "");
                    u.put("bio", "");
                    u.put("coverUrl", "");
                    u.put("authProvider", "email");
                    // Default email visibility is strictly "Only me" for every newly created account!
                    u.put("emailPrivacy", "Only me");
                    u.put("contactPrivacy", "Only me");
                    u.put("contactInfoPrivacy", "Only me");
                    u.put("friendsListPrivacy", "Public");
                    u.put("lookupPrivacy", "Everyone");
                    u.put("isOnline", true);
                    u.put("isAdmin", Admin.isOwnerEmail(e));
                    u.put("isVerified", Admin.isOwnerEmail(e));
                    u.put("friendsCount", 0);
                    u.put("followers", new ArrayList<String>());
                    u.put("following", new ArrayList<String>());
                    u.put("blockedUsers", new ArrayList<String>());
                    u.put("createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("users").document(uid).set(u)
                            .addOnSuccessListener(x -> {
                                GoogleAuthHelper.recordReferralIfPresent(this, uid, n, "",
                                        refCode != null ? refCode.getText().toString() : null);
                                Prefs.saveAccount(this, uid, e, n, "");
                                Prefs.setUnlockedSession(this, true);
                                SessionsActivity.record();
                                Fb.startMeListener();
                                Presence.init();
                                startActivity(new Intent(this, MainActivity.class));
                                finishAffinity();
                            })
                            .addOnFailureListener(err -> {
                                btn.setText("Sign Up");
                                btn.setEnabled(true);
                                Ui.toast(this, err.getMessage());
                            });
                })
                .addOnFailureListener(err -> {
                    btn.setText("Sign Up");
                    btn.setEnabled(true);
                    String m = err.getMessage() == null ? "Sign up failed" : err.getMessage();
                    if (m.contains("email address is already")) m = "This email is already registered";
                    Ui.toast(this, m);
                });
    }
}
