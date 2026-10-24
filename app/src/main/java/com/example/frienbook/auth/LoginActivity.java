package com.example.frienbook.auth;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Presence;
import com.example.frienbook.core.Ui;
import com.example.frienbook.main.MainActivity;

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
                    Prefs.setUnlockedSession(this, true);
                    openMain();
                })
                .addOnFailureListener(err -> {
                    btn.setText("Log In");
                    btn.setEnabled(true);
                    Ui.toast(this, human(err.getMessage()));
                });
    }

    private void forgotDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 12, 20, 0);
        EditText e = Ui.input(this, "Email address");
        e.setText(email.getText().toString());
        box.addView(e, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        new android.app.AlertDialog.Builder(this)
                .setTitle("Reset password")
                .setMessage("We'll email you a reset link.")
                .setView(box)
                .setPositiveButton("Send", (d, w) -> {
                    String em = e.getText().toString().trim();
                    if (em.isEmpty()) return;
                    Fb.auth().sendPasswordResetEmail(em)
                            .addOnSuccessListener(x -> Ui.toast(this, "Reset email sent"))
                            .addOnFailureListener(err -> Ui.toast(this, human(err.getMessage())));
                })
                .setNegativeButton("Cancel", null)
                .show();
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
