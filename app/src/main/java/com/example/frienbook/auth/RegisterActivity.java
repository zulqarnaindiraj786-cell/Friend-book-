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
import com.google.firebase.firestore.FieldValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class RegisterActivity extends Activity {

    private EditText name, email, pass, pass2, refCode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        Ui.pad(root, 24, 36, 24, 24);

        TextView title = Ui.tvBold(this, "Create account", 26, Ui.txt(this));
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = Ui.tv(this, "It's quick and easy.", 13, Ui.sub(this));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 20));
        root.addView(sub);

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

        // 🔵 Referral: paste an invite link/code → the inviter earns towards their FREE blue tick
        refCode = Ui.input(this, "Invite code (optional)");
        TextView refHint = Ui.tv(this, "Got an invite link? Paste it — the sticker ✓ blue tick counts for your friend.", 11, Ui.sub(this));
        refHint.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), 0, 0);
        root.addView(refCode, lp(10));
        root.addView(refHint);
        String pending = Prefs.pendingReferral(this);
        if (!pending.isEmpty()) {
            refCode.setText(com.example.frienbook.core.Admin.inviteLink(pending));
            refHint.setText("Invite detected — your friend gets referral credit 🎉");
            refHint.setTextColor(Ui.BLUE);
        }

        TextView create = Ui.btn(this, "Sign Up");
        root.addView(create, lp(18));
        create.setOnClickListener(v -> doRegister(create));

        TextView have = Ui.tvBold(this, "Already have an account?", 14, Ui.BLUE);
        have.setGravity(Gravity.CENTER);
        have.setPadding(0, Ui.dp(this, 18), 0, 0);
        have.setOnClickListener(v -> finish());
        root.addView(have);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        p.topMargin = Ui.dp(this, topDp);
        return p;
    }

    /** Write the referral record so the inviter earns credit towards the free blue tick. */
    private void recordReferral(String newUid) {
        String code = com.example.frienbook.core.Admin.extractRefCode(
                refCode != null ? refCode.getText().toString() : null);
        if (code == null || code.equals(newUid)) return;
        Prefs.setPendingReferral(this, "");
        // guard: inviter must be a real user
        Fb.db().collection("users").document(code).get().addOnSuccessListener(d -> {
            if (!d.exists()) return;
            Map<String, Object> r = new HashMap<>();
            r.put("refBy", code);
            r.put("refTo", newUid);
            r.put("status", "completed");
            r.put("date", FieldValue.serverTimestamp());
            r.put("createdAt", FieldValue.serverTimestamp());
            Fb.db().collection("referrals").document(code + "_" + newUid).set(r);
            // real-time welcome notification for the inviter
            Map<String, Object> n = new HashMap<>();
            n.put("toUserId", code);
            n.put("fromUserId", newUid);
            n.put("fromUserName", name.getText().toString().trim());
            n.put("fromUserPhotoUrl", "");
            n.put("type", "referral");
            n.put("text", "joined friendbook via your invite 🎉 (referral credit +1)");
            n.put("isRead", false);
            n.put("createdAt", FieldValue.serverTimestamp());
            Fb.db().collection("notifications").add(n);
        });
    }


    private void doRegister(TextView btn) {
        String n = name.getText().toString().trim();
        String e = email.getText().toString().trim();
        String p = pass.getText().toString();
        String p2 = pass2.getText().toString();
        if (n.length() < 2) { Ui.toast(this, "Enter your name"); return; }
        if (e.isEmpty() || !e.contains("@")) { Ui.toast(this, "Enter a valid email"); return; }
        if (p.length() < 6) { Ui.toast(this, "Password must be 6+ characters"); return; }
        if (!p.equals(p2)) { Ui.toast(this, "Passwords don't match"); return; }

        btn.setText("Creating…");
        btn.setEnabled(false);
        Ui.hideKeyboard(this);

        Fb.auth().createUserWithEmailAndPassword(e, p)
                .addOnSuccessListener(res -> {
                    String uid = res.getUser().getUid();
                    Map<String, Object> u = new HashMap<>();
                    u.put("displayName", n);
                    u.put("email", e);
                    u.put("photoUrl", "");
                    u.put("bio", "");
                    u.put("coverUrl", "");
                    u.put("isOnline", true);
                    u.put("friendsCount", 0);
                    u.put("followers", new ArrayList<String>());
                    u.put("following", new ArrayList<String>());
                    u.put("blockedUsers", new ArrayList<String>());
                    u.put("createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("users").document(uid).set(u)
                            .addOnSuccessListener(x -> {
                                recordReferral(uid);   // ref code → referrals doc (inviter's free-tick credit)
                                Prefs.setUnlockedSession(this, true);
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
