package com.friendbook.auth;

import android.app.Activity;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.core.Fb;
import com.friendbook.core.Ui;

import java.util.Locale;

/**
 * 🔐 Official Password Reset Link Screen (Firebase Auth `sendPasswordResetEmail`):
 * Sends a secure password reset link directly to any registered user's email inbox.
 */
public class ForgotPasswordActivity extends Activity {

    private EditText emailField;
    private TextView statusCard, sendBtn, backToLoginBtn;
    private CountDownTimer cooldownTimer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarWhite(this);

        LinearLayout screen = Ui.col(this);
        screen.setBackgroundColor(Ui.bg(this));
        screen.addView(Ui.pageBar(this, "Reset Password"));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);

        LinearLayout body = Ui.col(this);
        Ui.pad(body, 22, 28, 22, 28);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView titleTv = Ui.tvBold(this, "Forgot your password?", 23, Ui.txt(this));
        titleTv.setGravity(Gravity.CENTER);
        body.addView(titleTv);

        TextView subTv = Ui.tv(this,
                "Enter your account email address below. We'll send you an official password reset link so you can choose a new password.",
                13.5f, Ui.sub(this));
        subTv.setGravity(Gravity.CENTER);
        subTv.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 20));
        body.addView(subTv);

        emailField = Ui.input(this, "Enter your email address");
        emailField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        String prefill = getIntent().getStringExtra("prefill_email");
        if (prefill != null && !prefill.trim().isEmpty()) {
            emailField.setText(prefill.trim());
        }
        body.addView(emailField, fieldLp(0));

        statusCard = Ui.tv(this, "", 13, Ui.txt(this));
        statusCard.setBackground(Ui.roundStroke(this, Ui.card(this), 10, Ui.div(this), 1));
        Ui.pad(statusCard, 14, 12, 14, 12);
        statusCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(this, 14);
        body.addView(statusCard, slp);

        sendBtn = Ui.btn(this, "Send Reset Link");
        sendBtn.setOnClickListener(v -> sendResetLink());
        body.addView(sendBtn, fieldLp(16));

        backToLoginBtn = Ui.btnGrey(this, "Back to Log In");
        backToLoginBtn.setTextColor(Ui.txt(this));
        backToLoginBtn.setOnClickListener(v -> finish());
        body.addView(backToLoginBtn, fieldLp(12));

        sv.addView(body);
        screen.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(screen);
    }

    private LinearLayout.LayoutParams fieldLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private void sendResetLink() {
        String raw = emailField.getText().toString().trim().toLowerCase(Locale.US);
        if (!GoogleAuthHelper.isValidEmail(raw)) {
            Ui.toast(this, "Please enter a valid email address");
            return;
        }
        Ui.hideKeyboard(this);
        sendBtn.setEnabled(false);
        sendBtn.setText("Sending Reset Link…");
        statusCard.setVisibility(View.GONE);

        Fb.auth().sendPasswordResetEmail(raw)
                .addOnSuccessListener(unused -> {
                    statusCard.setTextColor(0xFF15803D);
                    statusCard.setText("✅ Password reset link sent to " + raw + "!\n\n"
                            + "1. Open your email app (check Inbox & Spam folder).\n"
                            + "2. Tap the password reset link to set your new password.\n"
                            + "3. Return to friendbook and log in with your new password.");
                    statusCard.setVisibility(View.VISIBLE);
                    Ui.toast(this, "Reset link sent to " + raw + " ✓");
                    startCooldown();
                })
                .addOnFailureListener(err -> {
                    sendBtn.setEnabled(true);
                    sendBtn.setText("Send Reset Link");
                    String msg = err != null && err.getMessage() != null
                            ? err.getMessage() : "Could not send reset email. Check your internet connection.";
                    if (msg.contains("no user record")) {
                        msg = "No account found with this email address.";
                    } else if (msg.contains("badly formatted")) {
                        msg = "Please enter a valid email address.";
                    }
                    statusCard.setTextColor(0xFFDC2626);
                    statusCard.setText("❌ " + msg);
                    statusCard.setVisibility(View.VISIBLE);
                });
    }

    private void startCooldown() {
        if (cooldownTimer != null) cooldownTimer.cancel();
        sendBtn.setEnabled(false);
        cooldownTimer = new CountDownTimer(30_000L, 1_000L) {
            @Override
            public void onTick(long ms) {
                sendBtn.setText(String.format(Locale.US, "Resend Reset Link (%ds)", ms / 1000L + 1));
            }

            @Override
            public void onFinish() {
                sendBtn.setEnabled(true);
                sendBtn.setText("Resend Reset Link");
            }
        }.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cooldownTimer != null) cooldownTimer.cancel();
    }
}
