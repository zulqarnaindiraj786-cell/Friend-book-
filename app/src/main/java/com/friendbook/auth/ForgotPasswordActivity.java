package com.friendbook.auth;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.core.Fb;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;
import com.friendbook.main.MainActivity;
import com.friendbook.settings.SessionsActivity;

import java.util.Locale;

/**
 * 🔐 Complete Friendbook Forgot Password Flow (100% Spark Plan — Zero Cloud Functions):
 * - Step 1: Enter ANY email → generates 6-digit OTP → saves `{ email, otp, createdAt, expiresAt (+10min) }`
 *           in Firestore `otps` collection + enqueues `{ to: [email], message: { subject: "Friendbook OTP",
 *           html: "<h1>OTP: ${otp}</h1><p>Valid 10 min, check spam</p>" } }` in `mail` collection
 *           for Firebase Trigger Email extension.
 * - Step 2: Enter 6-digit OTP → verifies directly from Firestore `otps` collection.
 * - Step 3: Set New Password → updates password via Firebase `updatePassword` and signs user in!
 */
public class ForgotPasswordActivity extends Activity {

    private int step = 1; // 1 = Email, 2 = Enter OTP, 3 = Set New Password
    private String verifiedEmail = "";

    private TextView stepBadge, titleTv, subTv, statusTv, primaryBtn, resendBtn, changeEmailBtn;
    private EditText emailField, otpField, newPassField, confirmPassField;
    private CountDownTimer resendTimer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout screen = Ui.col(this);
        screen.setBackgroundColor(Ui.bg(this));
        screen.addView(Ui.pageBar(this, "Forgot Password"));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);

        LinearLayout body = Ui.col(this);
        Ui.pad(body, 22, 24, 22, 28);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageResource(com.friendbook.R.drawable.ic_brand_logo);
        LinearLayout.LayoutParams bilp = new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 68));
        bilp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(brandIcon, bilp);

        stepBadge = Ui.tvBold(this, "STEP 1 OF 3", 11, Ui.BLUE);
        stepBadge.setGravity(Gravity.CENTER);
        stepBadge.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        body.addView(stepBadge);

        titleTv = Ui.tvBold(this, "Reset your password", 22, Ui.txt(this));
        titleTv.setGravity(Gravity.CENTER);
        body.addView(titleTv);

        subTv = Ui.tv(this,
                "Enter your email address and tap Send Code to receive a 6-digit OTP.",
                13, Ui.sub(this));
        subTv.setGravity(Gravity.CENTER);
        subTv.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 18));
        body.addView(subTv);

        // Step 1 Input: ANY Email
        emailField = Ui.input(this, "Enter your email address");
        emailField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        String prefill = getIntent().getStringExtra("prefill_email");
        if (prefill != null && !prefill.trim().isEmpty()) {
            emailField.setText(prefill.trim());
        }
        body.addView(emailField, fieldLp(0));

        // Step 2 Input: 6-Digit OTP
        otpField = Ui.input(this, "• • • • • •");
        otpField.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpField.setGravity(Gravity.CENTER);
        otpField.setTextSize(24f);
        otpField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            otpField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            otpField.setAutofillHints("smsOTPCode", "oneTimeCode");
        }
        otpField.setVisibility(View.GONE);
        body.addView(otpField, fieldLp(0));

        // Step 3 Inputs: New Password + Confirm New Password
        newPassField = Ui.input(this, "New password (6+ characters)");
        newPassField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        newPassField.setVisibility(View.GONE);
        body.addView(newPassField, fieldLp(0));

        confirmPassField = Ui.input(this, "Confirm new password");
        confirmPassField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirmPassField.setVisibility(View.GONE);
        body.addView(confirmPassField, fieldLp(12));

        statusTv = Ui.tv(this, "", 13, Ui.GREEN);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        statusTv.setVisibility(View.GONE);
        body.addView(statusTv);

        primaryBtn = Ui.btn(this, "Send Code");
        LinearLayout.LayoutParams pblp = fieldLp(14);
        primaryBtn.setOnClickListener(v -> onPrimaryClick());
        body.addView(primaryBtn, pblp);

        resendBtn = Ui.btnGrey(this, "Resend Code");
        resendBtn.setTextColor(Ui.BLUE);
        resendBtn.setVisibility(View.GONE);
        resendBtn.setOnClickListener(v -> handleSendCode(true));
        body.addView(resendBtn, fieldLp(10));

        changeEmailBtn = Ui.tvBold(this, "← Change email address", 13, Ui.sub(this));
        changeEmailBtn.setGravity(Gravity.CENTER);
        changeEmailBtn.setPadding(0, Ui.dp(this, 16), 0, 0);
        changeEmailBtn.setVisibility(View.GONE);
        changeEmailBtn.setOnClickListener(v -> showStep1());
        body.addView(changeEmailBtn);

        sv.addView(body);
        screen.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(screen);
    }

    private LinearLayout.LayoutParams fieldLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private void onPrimaryClick() {
        if (step == 1) {
            handleSendCode(false);
        } else if (step == 2) {
            handleVerifyOtp();
        } else if (step == 3) {
            handleUpdatePassword();
        }
    }

    private void showStatus(String msg, int color) {
        statusTv.setTextColor(color);
        statusTv.setText(msg);
        statusTv.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ==================== STEP 1: SEND 6-DIGIT OTP ====================
    private void showStep1() {
        stopTimer();
        step = 1;
        stepBadge.setText("STEP 1 OF 3");
        titleTv.setText("Reset your password");
        subTv.setText("Enter your email address and tap Send Code to receive a 6-digit OTP.");
        emailField.setVisibility(View.VISIBLE);
        otpField.setVisibility(View.GONE);
        newPassField.setVisibility(View.GONE);
        confirmPassField.setVisibility(View.GONE);
        resendBtn.setVisibility(View.GONE);
        changeEmailBtn.setVisibility(View.GONE);
        primaryBtn.setEnabled(true);
        primaryBtn.setText("Send Code");
        showStatus("", Ui.GREEN);
    }

    private void handleSendCode(boolean isResend) {
        String raw = step == 1 ? emailField.getText().toString() : verifiedEmail;
        if (!AuthOtpVault.isValidEmail(raw)) {
            Ui.toast(this, "Please enter a valid email address");
            return;
        }
        Ui.hideKeyboard(this);
        primaryBtn.setEnabled(false);
        resendBtn.setEnabled(false);
        showStatus("Sending 6-digit OTP to " + AuthOtpVault.normalizeEmail(raw) + "…", Ui.BLUE);

        AuthOtpVault.sendEmailOtp(raw, new AuthOtpVault.SendCallback() {
            @Override
            public void onSent(String normalizedEmail) {
                verifiedEmail = normalizedEmail;
                showStep2(isResend);
            }

            @Override
            public void onError(String message) {
                primaryBtn.setEnabled(true);
                resendBtn.setEnabled(true);
                showStatus("❌ " + message, 0xFFE41E3F);
            }
        });
    }

    // ==================== STEP 2: ENTER & VERIFY OTP ====================
    private void showStep2(boolean isResend) {
        step = 2;
        stepBadge.setText("STEP 2 OF 3");
        titleTv.setText("Enter 6-Digit OTP");
        subTv.setText("We sent a 6-digit OTP to " + verifiedEmail
                + "\nValid for 10 minutes — please check Inbox & Spam folder.");
        emailField.setVisibility(View.GONE);
        otpField.setVisibility(View.VISIBLE);
        otpField.setText("");
        otpField.requestFocus();
        newPassField.setVisibility(View.GONE);
        confirmPassField.setVisibility(View.GONE);
        primaryBtn.setEnabled(true);
        primaryBtn.setText("Verify OTP");
        resendBtn.setVisibility(View.VISIBLE);
        changeEmailBtn.setVisibility(View.VISIBLE);
        showStatus(isResend
                ? "✅ Fresh 6-digit OTP sent to " + verifiedEmail + "!"
                : "✅ OTP sent! Check your email (and Spam folder).", Ui.GREEN);
        startResendCooldown();
    }

    private void startResendCooldown() {
        stopTimer();
        resendBtn.setEnabled(false);
        resendTimer = new CountDownTimer(30_000L, 1_000L) {
            @Override
            public void onTick(long ms) {
                resendBtn.setText(String.format(Locale.US, "Resend Code (%ds)", ms / 1000L + 1));
            }

            @Override
            public void onFinish() {
                resendBtn.setEnabled(true);
                resendBtn.setText("Resend Code");
            }
        }.start();
    }

    private void handleVerifyOtp() {
        String entered = otpField.getText().toString().trim();
        if (entered.length() != 6) {
            Ui.toast(this, "Enter the 6-digit OTP");
            return;
        }
        Ui.hideKeyboard(this);
        primaryBtn.setEnabled(false);
        primaryBtn.setText("Verifying…");
        showStatus("Verifying OTP…", Ui.BLUE);

        AuthOtpVault.verifyEmailOtp(verifiedEmail, entered, new AuthOtpVault.VerifyCallback() {
            @Override
            public void onVerified() {
                showStep3();
            }

            @Override
            public void onError(String message) {
                primaryBtn.setEnabled(true);
                primaryBtn.setText("Verify OTP");
                showStatus("❌ " + message, 0xFFE41E3F);
            }
        });
    }

    // ==================== STEP 3: SET NEW PASSWORD ====================
    private void showStep3() {
        stopTimer();
        step = 3;
        stepBadge.setText("STEP 3 OF 3");
        titleTv.setText("Set New Password");
        subTv.setText("OTP verified for " + verifiedEmail + " ✓\nCreate a strong new password (6+ characters).");
        emailField.setVisibility(View.GONE);
        otpField.setVisibility(View.GONE);
        resendBtn.setVisibility(View.GONE);
        changeEmailBtn.setVisibility(View.GONE);
        newPassField.setVisibility(View.VISIBLE);
        confirmPassField.setVisibility(View.VISIBLE);
        newPassField.setText("");
        confirmPassField.setText("");
        newPassField.requestFocus();
        primaryBtn.setEnabled(true);
        primaryBtn.setText("Set New Password");
        showStatus("✅ OTP verified! Enter your new password below.", Ui.GREEN);
    }

    private void handleUpdatePassword() {
        String p1 = newPassField.getText().toString();
        String p2 = confirmPassField.getText().toString();
        if (p1.length() < 6) {
            Ui.toast(this, "Password must be at least 6 characters");
            return;
        }
        if (!p1.equals(p2)) {
            Ui.toast(this, "Passwords do not match");
            return;
        }
        Ui.hideKeyboard(this);
        primaryBtn.setEnabled(false);
        primaryBtn.setText("Updating Password…");
        showStatus("Updating your password…", Ui.BLUE);

        AuthOtpVault.applyNewPassword(this, verifiedEmail, p1, new AuthOtpVault.PasswordUpdateCallback() {
            @Override
            public void onSuccess(boolean signedInReady, String infoMessage) {
                Ui.toast(ForgotPasswordActivity.this, infoMessage);
                if (signedInReady && Fb.signedIn()) {
                    Prefs.setUnlockedSession(ForgotPasswordActivity.this, true);
                    SessionsActivity.record();
                    Fb.startMeListener();
                    Presence.init();
                    Intent i = new Intent(ForgotPasswordActivity.this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finishAffinity();
                } else {
                    newPassField.setVisibility(View.GONE);
                    confirmPassField.setVisibility(View.GONE);
                    showStatus("✅ " + infoMessage, Ui.GREEN);
                    primaryBtn.setEnabled(true);
                    primaryBtn.setText("Back to Log In");
                    primaryBtn.setOnClickListener(v -> {
                        Intent li = new Intent(ForgotPasswordActivity.this, LoginActivity.class);
                        li.putExtra("prefill_email", verifiedEmail);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(li);
                        finish();
                    });
                }
            }

            @Override
            public void onError(String message) {
                primaryBtn.setEnabled(true);
                primaryBtn.setText("Set New Password");
                showStatus("❌ " + message, 0xFFE41E3F);
            }
        });
    }

    private void stopTimer() {
        if (resendTimer != null) {
            resendTimer.cancel();
            resendTimer = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopTimer();
        super.onDestroy();
    }
}
