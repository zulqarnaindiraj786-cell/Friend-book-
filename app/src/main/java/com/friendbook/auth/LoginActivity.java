package com.friendbook.auth;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.core.Fb;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;
import com.friendbook.main.MainActivity;

public class LoginActivity extends Activity {

    private EditText email, pass;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Fb.signedIn()) { openMain(); return; }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        Ui.pad(root, 24, 48, 24, 24);

        TextView logo = Ui.tvBold(this, "friendbook", 34, Ui.BLUE);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);
        TextView tag = Ui.tv(this, "Connect with friends and the world around you.", 13, Ui.sub(this));
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 28));
        root.addView(tag);

        email = Ui.input(this, "Email address");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(email, lp());

        pass = Ui.input(this, "Password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams pp = lp(); pp.topMargin = Ui.dp(this, 12);
        root.addView(pass, pp);

        TextView login = Ui.btn(this, "Log In");
        LinearLayout.LayoutParams lb = lp(); lb.topMargin = Ui.dp(this, 18);
        root.addView(login, lb);
        login.setOnClickListener(v -> doLogin(login));

        TextView forgot = Ui.tvBold(this, "Forgot password?", 14, Ui.BLUE);
        forgot.setGravity(Gravity.CENTER);
        forgot.setPadding(0, Ui.dp(this, 18), 0, 0);
        root.addView(forgot);
        forgot.setOnClickListener(v -> forgotDialog());

        TextView divider = Ui.tv(this, "──────────  or  ──────────", 13, Ui.sub(this));
        divider.setGravity(Gravity.CENTER);
        divider.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 14));
        root.addView(divider);

        TextView create = Ui.btnGrey(this, "Create New Account");
        create.setTextColor(Ui.BLUE);
        create.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));
        root.addView(create, lp());

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        return p;
    }

    private void doLogin(TextView btn) {
        String e = email.getText().toString().trim();
        String p = pass.getText().toString();
        if (e.isEmpty() || p.isEmpty()) { Ui.toast(this, "Enter email and password"); return; }
        btn.setText("Logging in…");
        btn.setEnabled(false);
        Ui.hideKeyboard(this);
        Fb.auth().signInWithEmailAndPassword(e, p)
                .addOnSuccessListener(r -> {
                    // banned-account gate: verify profile before letting them in
                    String uid = Fb.auth().getCurrentUser() != null
                            ? Fb.auth().getCurrentUser().getUid() : null;
                    if (uid == null) { openMain(); return; }
                    Fb.db().collection("users").document(uid).get().addOnSuccessListener(d -> {
                        boolean banned = Boolean.TRUE.equals(d.getBoolean("banned"));
                        String em = Fb.auth().getCurrentUser() != null
                                ? Fb.auth().getCurrentUser().getEmail() : "";
                        if (banned && !com.friendbook.core.Admin.isOwnerEmail(em)) {
                            Fb.auth().signOut();
                            btn.setText("Log In");
                            btn.setEnabled(true);
                            Ui.info(this, "Account suspended",
                                    "This account has been suspended by the friendbook admin."
                                            + " Contact " + com.friendbook.core.Admin.OWNER_EMAIL
                                            + " if you think this is a mistake.");
                            return;
                        }
                        Prefs.setUnlockedSession(this, true);
                        com.friendbook.settings.SessionsActivity.record(); // "Where you're logged in"
                        openMain();
                    });
                })
                .addOnFailureListener(err -> {
                    btn.setText("Log In");
                    btn.setEnabled(true);
                    Ui.toast(this, human(err.getMessage()));
                });
    }

    private void forgotDialog() {
        // Facebook-Lite 2026 recovery: Gmail OTP code screen
        startActivity(new android.content.Intent(this,
                com.friendbook.auth.ForgotPasswordActivity.class));
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
