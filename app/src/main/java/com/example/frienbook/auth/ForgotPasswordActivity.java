package com.example.frienbook.auth;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Ui;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.SetOptions;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 🔐 Find your account — Facebook-Lite 2026 style recovery:
 *   Step 1: enter your Gmail → a 6-digit code is emailed to you
 *            (writes a `mail` doc for Firebase "Trigger Email" extension,
 *            and the code's SHA-256 hash into `passwordOtps/<email>`)
 *   Step 2: enter the 6-digit code from your inbox
 *   Step 3: verified → friendbook emails the secure "set new password" link
 *            (Firebase Auth official reset) — tap it and log in.
 */
public class ForgotPasswordActivity extends Activity {

    private static final long OTP_TTL_MS = 10 * 60 * 1000; // 10 minutes
    private static final int MAX_ATTEMPTS = 5;

    private String email;
    private TextView stepT, statusT, actionBtn;
    private EditText field;
    private int step = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Find your account"));

        LinearLayout body = Ui.col(this);
        Ui.pad(body, 20, 24, 20, 20);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView icon = Ui.tv(this, "🔐", 44, Ui.txt(this));
        icon.setGravity(Gravity.CENTER);
        body.addView(icon);

        stepT = Ui.tvBold(this, "Enter your Gmail", 19, Ui.txt(this));
        stepT.setGravity(Gravity.CENTER);
        stepT.setPadding(0, Ui.dp(this, 12), 0, 0);
        body.addView(stepT);

        TextView sub = Ui.tv(this,
                "Hum aapki Gmail pe 6-digit code bhejein ge — jaise Facebook Lite me hota hai.", 13, Ui.sub(this));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 6), 0, 0);
        body.addView(sub);

        field = Ui.input(this, "you@gmail.com");
        field.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        field.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        fp.setMargins(0, Ui.dp(this, 18), 0, 0);
        body.addView(field, fp);

        statusT = Ui.tv(this, "", 13, Ui.GREEN);
        statusT.setGravity(Gravity.CENTER);
        statusT.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(statusT);

        actionBtn = Ui.btn(this, "📧 Send code");
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        ap.setMargins(0, Ui.dp(this, 12), 0, 0);
        actionBtn.setOnClickListener(v -> onAction());
        body.addView(actionBtn, ap);

        // fallback row: direct official reset link (always works, no extension needed)
        TextView link = Ui.tvBold(this, "Ya seedha password-reset link 📩 bhejo", 13, Ui.BLUE);
        link.setGravity(Gravity.CENTER);
        link.setPadding(0, Ui.dp(this, 16), 0, 0);
        link.setOnClickListener(v -> {
            String e = field.getText().toString().trim();
            if (!e.contains("@")) { Ui.toast(this, "Enter your Gmail first"); return; }
            Fb.auth().sendPasswordResetEmail(e)
                    .addOnSuccessListener(x -> {
                        statusT.setText("✅ Reset link sent to " + e + " — Gmail khol ke link tap karo");
                        stepT.setText("Check your inbox");
                    })
                    .addOnFailureListener(err -> Ui.toast(this, err.getMessage()));
        });
        body.addView(link);

        setContentView(root);
    }

    private void onAction() {
        if (step == 1) sendCode();
        else if (step == 2) verifyCode();
    }

    // ---------------- STEP 1 ----------------
    private void sendCode() {
        email = field.getText().toString().trim().toLowerCase();
        if (!email.contains("@") || !email.contains(".")) {
            Ui.toast(this, "Enter a valid Gmail address"); return;
        }
        actionBtn.setEnabled(false);
        statusT.setText("Sending code…");

        String code = String.format(java.util.Locale.US, "%06d", new Random().nextInt(1000000));
        String hash = sha256(code + ":" + email);
        long expires = System.currentTimeMillis() + OTP_TTL_MS;

        Map<String, Object> otp = new HashMap<>();
        otp.put("email", email);
        otp.put("codeHash", hash);
        otp.put("expiresAtMs", expires);
        otp.put("attempts", 0);
        otp.put("used", false);
        otp.put("createdAt", FieldValue.serverTimestamp());

        Fb.db().collection("passwordOtps").document(email.replaceAll("[^a-z0-9@._-]", "_"))
                .set(otp, SetOptions.merge())
                .addOnSuccessListener(x -> {
                    // queue the real email (Firebase "Trigger Email" extension reads `mail`)
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("subject", "friendbook password recovery code");
                    msg.put("text", "Your friendbook recovery code is: " + code
                            + "\nIt expires in 10 minutes. If this wasn't you, ignore this email.");
                    msg.put("html", "<div style=\"font-family:sans-serif;padding:16px\">"
                            + "<h2 style=\"color:#1877F2;margin:0\">friendbook</h2>"
                            + "<p>Your password recovery code:</p>"
                            + "<p style=\"font-size:34px;letter-spacing:10px;font-weight:bold;color:#050505;margin:8px 0\">"
                            + code + "</p>"
                            + "<p style=\"color:#65676B\">It expires in 10 minutes. If this wasn't you, ignore this email.</p></div>");
                    Map<String, Object> mail = new HashMap<>();
                    mail.put("to", email);
                    mail.put("message", msg);
                    mail.put("createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("mail").add(mail);

                    step = 2;
                    actionBtn.setEnabled(true);
                    stepT.setText("Enter the 6-digit code");
                    field.setText("");
                    field.setHint("••••••");
                    field.setInputType(InputType.TYPE_CLASS_NUMBER);
                    field.setTextSize(26f);
                    actionBtn.setText("✓ Verify code");
                    statusT.setText("📧 Code emailed to " + email
                            + "\n(Gmail inbox ya Spam folder check karo — 10 min valid)");
                })
                .addOnFailureListener(err -> {
                    actionBtn.setEnabled(true);
                    statusT.setText("Failed: " + err.getMessage());
                });
    }

    // ---------------- STEP 2 ----------------
    private void verifyCode() {
        String entered = field.getText().toString().trim();
        if (entered.length() != 6) { Ui.toast(this, "6-digit code likhen"); return; }
        actionBtn.setEnabled(false);
        statusT.setText("Verifying…");
        String docId = email.replaceAll("[^a-z0-9@._-]", "_");
        String guess = sha256(entered + ":" + email);

        Fb.db().collection("passwordOtps").document(docId).get()
                .addOnSuccessListener(d -> {
                    actionBtn.setEnabled(true);
                    if (!d.exists()) { statusT.setText("No code found — resend karo"); return; }
                    Map<String, Object> m = d.getData();
                    boolean used = Boolean.TRUE.equals(m.get("used"));
                    long expires = m.get("expiresAtMs") instanceof Number ? ((Number) m.get("expiresAtMs")).longValue() : 0;
                    long attempts = m.get("attempts") instanceof Number ? ((Number) m.get("attempts")).longValue() : 0;
                    String hash = String.valueOf(m.get("codeHash"));
                    if (used) { statusT.setText("Code already used — resend a new one"); return; }
                    if (System.currentTimeMillis() > expires) { statusT.setText("Code expired (10 min) — resend karo"); return; }
                    if (attempts >= MAX_ATTEMPTS) { statusT.setText("Too many tries — resend a new code"); return; }
                    if (!guess.equals(hash)) {
                        d.getReference().update("attempts", FieldValue.increment(1));
                        statusT.setText("❌ Wrong code (" + (attempts + 1) + "/" + MAX_ATTEMPTS + ")");
                        return;
                    }
                    d.getReference().update("used", true);
                    finishVerified();
                })
                .addOnFailureListener(e -> {
                    actionBtn.setEnabled(true);
                    statusT.setText("Error: " + e.getMessage());
                });
    }

    // ---------------- STEP 3 ----------------
    private void finishVerified() {
        statusT.setVisibility(View.VISIBLE);
        statusT.setText("✅ Code verified! Sending secure password-set link…");
        Fb.auth().sendPasswordResetEmail(email)
                .addOnSuccessListener(x -> {
                    stepT.setText("All set! 🎉");
                    field.setVisibility(View.GONE);
                    actionBtn.setVisibility(View.GONE);
                    statusT.setText("✅ " + email + " pe password-set link bhej di gayi.\n"
                            + "Gmail kholo → link tap karo → naya password set karo → login. "
                            + "Phir milte hain! 💙");
                })
                .addOnFailureListener(e -> statusT.setText("Reset email failed: " + e.getMessage()));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x));
            return sb.toString();
        } catch (Exception e) { return s; }
    }
}
