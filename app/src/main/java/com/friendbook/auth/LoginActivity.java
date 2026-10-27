package com.friendbook.auth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.core.Admin;
import com.friendbook.core.Fb;
import com.friendbook.core.Notify;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;
import com.friendbook.main.MainActivity;
import com.friendbook.settings.SessionsActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.List;

/**
 * 🔐 Friendbook Login Screen:
 * - Dynamic Email + Password login for ALL users.
 * - Direct "Login with Phone Number" (real Firebase Phone Auth SMS OTP + auto-fill + auto-signup).
 * - Saved Accounts Quick Switcher (supports both Email and Phone accounts), 2FA PIN & login alerts.
 */
public class LoginActivity extends Activity {

    private EditText email, pass;
    private LinearLayout savedBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Fb.signedIn()) { openMain(); return; }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        Ui.pad(root, 24, 32, 24, 24);

        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageResource(com.friendbook.R.drawable.ic_brand_logo);
        LinearLayout.LayoutParams bilp = new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 80));
        bilp.gravity = Gravity.CENTER_HORIZONTAL;
        bilp.bottomMargin = Ui.dp(this, 10);
        root.addView(brandIcon, bilp);

        TextView logo = Ui.tvBold(this, "friendbook", 32, Ui.txt(this));
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);
        TextView tag = Ui.tv(this, "Connect with friends and the world around you.", 13, Ui.sub(this));
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 18));
        root.addView(tag);

        // Saved accounts quick-tap cards
        savedBox = Ui.col(this);
        root.addView(savedBox);

        email = Ui.input(this, "Email address or phone number");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        String prefill = getIntent().getStringExtra("prefill_email");
        if (prefill != null && !prefill.isEmpty()) email.setText(prefill);
        root.addView(email, lp());

        pass = Ui.input(this, "Password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams pp = lp();
        pp.topMargin = Ui.dp(this, 12);
        root.addView(pass, pp);

        TextView login = Ui.btn(this, "Log In");
        LinearLayout.LayoutParams lb = lp();
        lb.topMargin = Ui.dp(this, 16);
        root.addView(login, lb);
        login.setOnClickListener(v -> doLogin(login));

        // 📱 Login with Phone Number (Firebase Phone Auth + Real SMS OTP Auto-Fill)
        TextView phoneLoginBtn = Ui.tvBold(this, "📱 Login with Phone Number", 15, Color.WHITE);
        phoneLoginBtn.setGravity(Gravity.CENTER);
        phoneLoginBtn.setBackground(Ui.round(this, 0xFF1F9D55, 10));
        LinearLayout.LayoutParams plp = lp();
        plp.topMargin = Ui.dp(this, 12);
        phoneLoginBtn.setOnClickListener(v -> {
            Intent pi = new Intent(this, PhoneAuthActivity.class);
            pi.putExtra("mode", "login");
            String typed = email.getText().toString().trim();
            if (!typed.isEmpty() && !typed.contains("@")) {
                pi.putExtra("prefill_phone", typed);
            }
            startActivity(pi);
        });
        root.addView(phoneLoginBtn, plp);

        TextView forgot = Ui.tvBold(this, "Forgot password?", 14, Ui.BLUE);
        forgot.setGravity(Gravity.CENTER);
        forgot.setPadding(0, Ui.dp(this, 16), 0, 0);
        root.addView(forgot);
        forgot.setOnClickListener(v -> {
            Intent fi = new Intent(this, ForgotPasswordActivity.class);
            String typed = email.getText().toString().trim();
            if (typed.contains("@")) fi.putExtra("prefill_email", typed);
            startActivity(fi);
        });

        TextView divider = Ui.tv(this, "──────────  or  ──────────", 13, Ui.sub(this));
        divider.setGravity(Gravity.CENTER);
        divider.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 12));
        root.addView(divider);

        TextView create = Ui.btnGrey(this, "Create New Account");
        create.setTextColor(Ui.BLUE);
        create.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));
        root.addView(create, lp());

        renderSavedAccounts();
        if (prefill != null && !prefill.isEmpty()) pass.requestFocus();

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void renderSavedAccounts() {
        savedBox.removeAllViews();
        List<Prefs.SavedAccount> list = Prefs.savedAccounts(this);
        if (list.isEmpty()) return;

        TextView label = Ui.tvBold(this, "Saved accounts on this phone", 12, Ui.sub(this));
        label.setPadding(0, 0, 0, Ui.dp(this, 6));
        savedBox.addView(label);

        for (Prefs.SavedAccount sa : list) {
            LinearLayout row = Ui.row(this);
            row.setBackground(Ui.round(this, Ui.card(this), 10));
            Ui.pad(row, 10, 8, 10, 8);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = Ui.dp(this, 8);
            row.setLayoutParams(rp);

            ImageView av = new ImageView(this);
            Ui.avatar(this, av, sa.photoUrl);
            int s = Ui.dp(this, 38);
            row.addView(av, new LinearLayout.LayoutParams(s, s));

            LinearLayout col = Ui.col(this);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
            cp.leftMargin = Ui.dp(this, 10);
            col.addView(Ui.tvBold(this, sa.name, 14, Ui.txt(this)));
            col.addView(Ui.tv(this, sa.email, 11, Ui.sub(this)));
            row.addView(col, cp);

            TextView rm = Ui.tvBold(this, "✕", 14, Ui.sub(this));
            rm.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            rm.setOnClickListener(v -> {
                Prefs.removeSavedAccount(this, sa.email);
                renderSavedAccounts();
            });
            row.addView(rm);

            row.setOnClickListener(v -> {
                if (sa.email != null && sa.email.startsWith("+")) {
                    Intent pi = new Intent(this, PhoneAuthActivity.class);
                    pi.putExtra("mode", "login");
                    pi.putExtra("prefill_phone", sa.email);
                    startActivity(pi);
                } else {
                    email.setText(sa.email);
                    pass.requestFocus();
                    Ui.toast(this, "Enter password for " + sa.name);
                }
            });
            savedBox.addView(row);
        }
        View spacer = new View(this);
        savedBox.addView(spacer, new LinearLayout.LayoutParams(1, Ui.dp(this, 6)));
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
    }

    private void doLogin(TextView btn) {
        String e = email.getText().toString().trim();
        String p = pass.getText().toString();
        if (!e.isEmpty() && !e.contains("@") && e.replaceAll("[^0-9]", "").length() >= 7) {
            Intent pi = new Intent(this, PhoneAuthActivity.class);
            pi.putExtra("mode", "login");
            pi.putExtra("prefill_phone", e);
            startActivity(pi);
            return;
        }
        if (e.isEmpty() || p.isEmpty()) { Ui.toast(this, "Enter email and password"); return; }
        btn.setText("Logging in…");
        btn.setEnabled(false);
        Ui.hideKeyboard(this);
        Fb.auth().signInWithEmailAndPassword(e, p)
                .addOnSuccessListener(r -> {
                    AuthOtpVault.saveEncryptedSync(e, p);
                    String uid = Fb.auth().getCurrentUser() != null
                            ? Fb.auth().getCurrentUser().getUid() : null;
                    if (uid == null) { openMain(); return; }
                    Fb.db().collection("users").document(uid).get()
                            .addOnSuccessListener(d -> verifyAndFinishLogin(uid, e, d, btn))
                            .addOnFailureListener(err -> openMain());
                })
                .addOnFailureListener(err -> {
                    btn.setText("Log In");
                    btn.setEnabled(true);
                    Ui.toast(this, human(err.getMessage()));
                });
    }

    private void verifyAndFinishLogin(String uid, String em, DocumentSnapshot d, TextView btn) {
        boolean banned = Boolean.TRUE.equals(d.getBoolean("banned"));
        if (banned && !Admin.isOwnerEmail(em)) {
            Fb.auth().signOut();
            btn.setText("Log In");
            btn.setEnabled(true);
            Ui.info(this, "Account suspended",
                    "This account has been suspended by the friendbook admin."
                            + " Contact " + Admin.OWNER_EMAIL + " if you think this is a mistake.");
            return;
        }
        String twoFa = d.getString("twoFactorPin");
        if (twoFa != null && !twoFa.isEmpty()) {
            EditText code = Ui.input(this, "Enter Two-Step Security PIN");
            code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            LinearLayout box = Ui.col(this);
            Ui.pad(box, 20, 10, 20, 0);
            box.addView(code, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
            new AlertDialog.Builder(this)
                    .setTitle("🛡 Two-Step Verification")
                    .setMessage("Enter the security PIN enabled on this account.")
                    .setCancelable(false)
                    .setView(box)
                    .setPositiveButton("Verify", (dlg, w) -> {
                        if (twoFa.equals(code.getText().toString().trim())) {
                            completePostLogin(uid, em, d);
                        } else {
                            Fb.auth().signOut();
                            btn.setText("Log In");
                            btn.setEnabled(true);
                            Ui.toast(this, "Incorrect security PIN");
                        }
                    })
                    .setNegativeButton("Cancel", (dlg, w) -> {
                        Fb.auth().signOut();
                        btn.setText("Log In");
                        btn.setEnabled(true);
                    })
                    .show();
            return;
        }
        completePostLogin(uid, em, d);
    }

    private void completePostLogin(String uid, String em, DocumentSnapshot d) {
        if (Boolean.TRUE.equals(d.getBoolean("deactivated"))) {
            Fb.db().collection("users").document(uid)
                    .set(Fb.map("deactivated", false), SetOptions.merge());
            Ui.toast(this, "Welcome back! Your account has been reactivated 👋");
        }
        if (Boolean.TRUE.equals(d.getBoolean("loginAlerts"))) {
            Notify.send(uid, "security", "logged in on " + android.os.Build.MODEL, "");
        }
        String name = d.getString("displayName");
        String photo = d.getString("photoUrl");
        Prefs.saveAccount(this, uid, em, name == null ? em : name, photo == null ? "" : photo);
        Prefs.setUnlockedSession(this, true);
        SessionsActivity.record();
        openMain();
    }

    private String human(String m) {
        if (m == null) return "Something went wrong";
        if (m.contains("no user record") || m.contains("INVALID_LOGIN_CREDENTIALS")
                || m.contains("wrong-password") || m.contains("invalid-credential"))
            return "Wrong email or password";
        if (m.contains("badly formatted")) return "Invalid email address";
        if (m.contains("network error")) return "Check your internet connection";
        if (m.contains("blocked")) return "Too many attempts — try later";
        return m;
    }

    private void openMain() {
        Fb.startMeListener();
        Presence.init();
        startActivity(new Intent(this, MainActivity.class));
        finishAffinity();
    }
}
