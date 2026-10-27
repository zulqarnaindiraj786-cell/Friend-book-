package com.friendbook.auth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.friendbook.core.Admin;
import com.friendbook.core.Fb;
import com.friendbook.core.Notify;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;
import com.friendbook.main.MainActivity;
import com.friendbook.settings.SessionsActivity;
import com.google.android.gms.auth.api.phone.SmsRetriever;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import com.google.firebase.FirebaseException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 📱 Complete Firebase Phone Authentication (`PhoneAuthProvider.verifyPhoneNumber`):
 * - Supports both Login & Auto-Signup dynamically for ALL phone numbers worldwide.
 * - 3-Layer SMS OTP Auto-Fill:
 *   1. Firebase `onVerificationCompleted` automatic SMS retrieval (`credential.getSmsCode()`).
 *   2. Google Play Services `SmsRetriever.startSmsUserConsent` (Play-Store compliant, zero READ_SMS permission).
 *   3. Android System Keyboard OTP Autofill (`smsOTPCode` / `oneTimeCode`).
 * - Saves `phoneNumber` (and `phone`) in Firestore `users/{uid}` collection on login or auto-signup.
 */
public class PhoneAuthActivity extends Activity {

    private static final int REQ_SMS_CONSENT = 9401;
    private static final Pattern SIX_DIGIT_OTP = Pattern.compile("\\b(\\d{6})\\b");

    private static final String[][] COUNTRIES = {
            {"🇵🇰 Pakistan (+92)", "+92"},
            {"🇮🇳 India (+91)", "+91"},
            {"🇸🇦 Saudi Arabia (+966)", "+966"},
            {"🇦🇪 UAE (+971)", "+971"},
            {"🇬🇧 United Kingdom (+44)", "+44"},
            {"🇺🇸 USA / Canada (+1)", "+1"},
            {"🇩🇪 Germany (+49)", "+49"},
            {"🇧🇩 Bangladesh (+880)", "+880"},
            {"🇹🇷 Turkey (+90)", "+90"},
            {"🇲🇾 Malaysia (+60)", "+60"},
            {"🇮🇩 Indonesia (+62)", "+62"},
            {"🇳🇬 Nigeria (+234)", "+234"},
            {"🌍 Custom Country Code…", "CUSTOM"}
    };

    private boolean isSignUpMode;
    private String selectedDialCode = "+92";
    private String activeE164Phone = "";
    private String verificationId;
    private PhoneAuthProvider.ForceResendingToken resendToken;
    private boolean verifyingInFlight;

    private LinearLayout phoneStepBox, otpStepBox;
    private TextView titleTv, subTv, statusTv, countryChip, sendOtpBtn, verifyOtpBtn, resendOtpBtn, editPhoneBtn;
    private EditText nameField, phoneField, refField, otpField;
    private CheckBox termsCheck;
    private CountDownTimer resendTimer;
    private boolean smsReceiverRegistered;

    private final BroadcastReceiver smsConsentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !SmsRetriever.SMS_RETRIEVED_ACTION.equals(intent.getAction())) return;
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            Status status = (Status) extras.get(SmsRetriever.EXTRA_STATUS);
            if (status != null && status.getStatusCode() == CommonStatusCodes.SUCCESS) {
                Intent consentIntent = extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT);
                if (consentIntent != null) {
                    try {
                        startActivityForResult(consentIntent, REQ_SMS_CONSENT);
                    } catch (Throwable ignored) {}
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        isSignUpMode = "signup".equalsIgnoreCase(getIntent().getStringExtra("mode"));
        selectedDialCode = detectDefaultDialCode();

        LinearLayout screen = Ui.col(this);
        screen.setBackgroundColor(Ui.bg(this));
        screen.addView(Ui.pageBar(this, isSignUpMode ? "Sign Up with Phone" : "Login with Phone"));

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

        titleTv = Ui.tvBold(this,
                isSignUpMode ? "Create account with Phone" : "Login with Phone Number",
                22, Ui.txt(this));
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(titleTv);

        subTv = Ui.tv(this,
                "We'll send a real 6-digit SMS verification code to verify your mobile number.",
                13, Ui.sub(this));
        subTv.setGravity(Gravity.CENTER);
        subTv.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 18));
        body.addView(subTv);

        // ==================== STEP 1: PHONE INPUT BOX ====================
        phoneStepBox = Ui.col(this);
        body.addView(phoneStepBox, new LinearLayout.LayoutParams(-1, -2));

        nameField = Ui.input(this, isSignUpMode ? "Full name" : "Your name (for new account, optional)");
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (!isSignUpMode) nameField.setVisibility(View.GONE);
        phoneStepBox.addView(nameField, fieldLp(0));

        // Country Code + Phone Number Row
        LinearLayout phoneRow = Ui.row(this);
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
        prlp.topMargin = Ui.dp(this, isSignUpMode ? 12 : 0);
        phoneRow.setGravity(Gravity.CENTER_VERTICAL);

        countryChip = Ui.tvBold(this, selectedDialCode + " ▾", 15, Ui.txt(this));
        countryChip.setGravity(Gravity.CENTER);
        countryChip.setBackground(Ui.round(this, Ui.card(this), 10));
        Ui.pad(countryChip, 14, 0, 14, 0);
        LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(-2, -1);
        cclp.rightMargin = Ui.dp(this, 8);
        countryChip.setOnClickListener(v -> showCountryPicker());
        phoneRow.addView(countryChip, cclp);

        phoneField = Ui.input(this, "300 1234567");
        phoneField.setInputType(InputType.TYPE_CLASS_PHONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            phoneField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            phoneField.setAutofillHints(View.AUTOFILL_HINT_PHONE);
        }
        String prefillPhone = getIntent().getStringExtra("prefill_phone");
        if (prefillPhone != null && !prefillPhone.trim().isEmpty()) {
            applyPrefillPhone(prefillPhone.trim());
        }
        phoneRow.addView(phoneField, new LinearLayout.LayoutParams(0, -1, 1));
        phoneStepBox.addView(phoneRow, prlp);

        TextView fmtHint = Ui.tv(this,
                "Tip: Select your country code or enter full international format (e.g. +923001234567).",
                11, Ui.sub(this));
        fmtHint.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), 0, 0);
        phoneStepBox.addView(fmtHint);

        refField = Ui.input(this, "Invite code (optional)");
        String pendingRef = Prefs.pendingReferral(this);
        if (!pendingRef.isEmpty()) refField.setText(Admin.inviteLink(pendingRef));
        if (isSignUpMode || !pendingRef.isEmpty()) {
            phoneStepBox.addView(refField, fieldLp(12));
        }

        // UGC & Child Safety Consent for new accounts
        LinearLayout termsRow = Ui.row(this);
        termsRow.setGravity(Gravity.TOP);
        termsRow.setPadding(0, Ui.dp(this, 12), 0, 0);
        termsCheck = new CheckBox(this);
        termsCheck.setChecked(!isSignUpMode);
        termsRow.addView(termsCheck);
        TextView termsTv = Ui.tv(this,
                "I am 13+ and agree to friendbook's Terms, Privacy Policy & Zero-Tolerance Safety Rules.",
                11.5f, Ui.sub(this));
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(0, -2, 1);
        ttlp.leftMargin = Ui.dp(this, 6);
        termsTv.setOnClickListener(v -> termsCheck.setChecked(!termsCheck.isChecked()));
        termsRow.addView(termsTv, ttlp);
        phoneStepBox.addView(termsRow);

        sendOtpBtn = Ui.btn(this, "📲 Send OTP Code");
        sendOtpBtn.setOnClickListener(v -> startPhoneVerification(false));
        phoneStepBox.addView(sendOtpBtn, fieldLp(16));

        // ==================== STEP 2: ENTER / AUTO-FILL OTP BOX ====================
        otpStepBox = Ui.col(this);
        otpStepBox.setVisibility(View.GONE);
        body.addView(otpStepBox, new LinearLayout.LayoutParams(-1, -2));

        otpField = Ui.input(this, "• • • • • •");
        otpField.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpField.setGravity(Gravity.CENTER);
        otpField.setTextSize(26f);
        otpField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            otpField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            otpField.setAutofillHints("smsOTPCode", "oneTimeCode");
        }
        otpField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (s != null && s.toString().trim().length() == 6
                        && verificationId != null && !verifyingInFlight) {
                    verifyEnteredCode(s.toString().trim());
                }
            }
        });
        otpStepBox.addView(otpField, fieldLp(0));

        verifyOtpBtn = Ui.btn(this, "✓ Verify & Login");
        verifyOtpBtn.setOnClickListener(v -> {
            String code = otpField.getText().toString().trim();
            if (code.length() != 6) {
                Ui.toast(this, "Enter the 6-digit SMS OTP");
                return;
            }
            verifyEnteredCode(code);
        });
        otpStepBox.addView(verifyOtpBtn, fieldLp(14));

        resendOtpBtn = Ui.btnGrey(this, "Resend SMS OTP");
        resendOtpBtn.setTextColor(Ui.BLUE);
        resendOtpBtn.setOnClickListener(v -> startPhoneVerification(true));
        otpStepBox.addView(resendOtpBtn, fieldLp(10));

        editPhoneBtn = Ui.tvBold(this, "← Change phone number", 13, Ui.sub(this));
        editPhoneBtn.setGravity(Gravity.CENTER);
        editPhoneBtn.setPadding(0, Ui.dp(this, 16), 0, 0);
        editPhoneBtn.setOnClickListener(v -> showPhoneInputStep());
        otpStepBox.addView(editPhoneBtn);

        statusTv = Ui.tv(this, "", 13, Ui.GREEN);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, Ui.dp(this, 14), 0, 0);
        statusTv.setVisibility(View.GONE);
        body.addView(statusTv);

        sv.addView(body);
        screen.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(screen);

        registerSmsConsentReceiver();
    }

    private LinearLayout.LayoutParams fieldLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private String detectDefaultDialCode() {
        String iso = Locale.getDefault().getCountry();
        if ("PK".equalsIgnoreCase(iso)) return "+92";
        if ("IN".equalsIgnoreCase(iso)) return "+91";
        if ("SA".equalsIgnoreCase(iso)) return "+966";
        if ("AE".equalsIgnoreCase(iso)) return "+971";
        if ("GB".equalsIgnoreCase(iso)) return "+44";
        if ("US".equalsIgnoreCase(iso) || "CA".equalsIgnoreCase(iso)) return "+1";
        if ("DE".equalsIgnoreCase(iso)) return "+49";
        if ("BD".equalsIgnoreCase(iso)) return "+880";
        if ("TR".equalsIgnoreCase(iso)) return "+90";
        return "+92";
    }

    private void applyPrefillPhone(String raw) {
        if (raw.startsWith("+")) {
            for (String[] c : COUNTRIES) {
                if (!"CUSTOM".equals(c[1]) && raw.startsWith(c[1])) {
                    selectedDialCode = c[1];
                    countryChip.setText(selectedDialCode + " ▾");
                    phoneField.setText(raw.substring(c[1].length()));
                    return;
                }
            }
        }
        phoneField.setText(raw);
    }

    private void showCountryPicker() {
        String[] labels = new String[COUNTRIES.length];
        for (int i = 0; i < COUNTRIES.length; i++) labels[i] = COUNTRIES[i][0];
        Ui.choose(this, "Select Country Code", labels, which -> {
            String code = COUNTRIES[which][1];
            if ("CUSTOM".equals(code)) {
                EditText customIn = Ui.input(this, "+92");
                customIn.setInputType(InputType.TYPE_CLASS_PHONE);
                customIn.setText("+");
                LinearLayout box = Ui.col(this);
                Ui.pad(box, 20, 10, 20, 0);
                box.addView(customIn, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
                new AlertDialog.Builder(this)
                        .setTitle("Enter Country Dial Code")
                        .setView(box)
                        .setPositiveButton("Apply", (d, w) -> {
                            String typed = customIn.getText().toString().trim().replaceAll("[^+0-9]", "");
                            if (!typed.startsWith("+")) typed = "+" + typed;
                            if (typed.length() >= 2) {
                                selectedDialCode = typed;
                                countryChip.setText(selectedDialCode + " ▾");
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                selectedDialCode = code;
                countryChip.setText(selectedDialCode + " ▾");
            }
        });
    }

    private String buildE164Phone() {
        String raw = phoneField.getText().toString().trim().replaceAll("[\\s\\-()]", "");
        if (raw.isEmpty()) return "";
        if (raw.startsWith("00")) {
            raw = "+" + raw.substring(2);
        }
        if (raw.startsWith("+")) {
            return raw.replaceAll("[^+0-9]", "");
        }
        // Strip leading trunk '0' when combining with international dial code (e.g. 0300... -> +92300...)
        while (raw.startsWith("0")) {
            raw = raw.substring(1);
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "";
        return selectedDialCode + digits;
    }

    private void showStatus(String msg, int color) {
        statusTv.setTextColor(color);
        statusTv.setText(msg);
        statusTv.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showPhoneInputStep() {
        stopTimer();
        verifyingInFlight = false;
        phoneStepBox.setVisibility(View.VISIBLE);
        otpStepBox.setVisibility(View.GONE);
        sendOtpBtn.setEnabled(true);
        sendOtpBtn.setText("📲 Send OTP Code");
        titleTv.setText(isSignUpMode ? "Create account with Phone" : "Login with Phone Number");
        subTv.setText("We'll send a real 6-digit SMS verification code to verify your mobile number.");
        showStatus("", Ui.GREEN);
    }

    private void startPhoneVerification(boolean isResend) {
        if (isSignUpMode) {
            String n = nameField.getText().toString().trim();
            if (n.length() < 2) {
                Ui.toast(this, "Please enter your full name");
                return;
            }
        }
        if (termsCheck != null && !termsCheck.isChecked()) {
            Ui.toast(this, "Please accept the Terms & Safety Policy to continue");
            return;
        }

        String e164 = isResend && !activeE164Phone.isEmpty() ? activeE164Phone : buildE164Phone();
        if (!e164.startsWith("+") || e164.length() < 8 || e164.length() > 16) {
            Ui.toast(this, "Enter a valid phone number with country code");
            return;
        }
        activeE164Phone = e164;
        Ui.hideKeyboard(this);

        sendOtpBtn.setEnabled(false);
        sendOtpBtn.setText("Sending SMS OTP…");
        if (resendOtpBtn != null) resendOtpBtn.setEnabled(false);
        showStatus("Requesting real SMS OTP for " + activeE164Phone + "…", Ui.BLUE);

        // Start Google Play Services SMS User Consent listener for automatic OTP fill
        try {
            SmsRetriever.getClient(this).startSmsUserConsent(null);
        } catch (Throwable ignored) {}

        PhoneAuthOptions.Builder builder = PhoneAuthOptions.newBuilder(Fb.auth())
                .setPhoneNumber(activeE164Phone)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(callbacks);
        if (isResend && resendToken != null) {
            builder.setForceResendingToken(resendToken);
        }
        PhoneAuthProvider.verifyPhoneNumber(builder.build());
    }

    private final PhoneAuthProvider.OnVerificationStateChangedCallbacks callbacks =
            new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                @Override
                public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                    if (isFinishing()) return;
                    // Auto-fill 6-digit SMS code visually when retrieved automatically
                    String autoCode = credential.getSmsCode();
                    if (autoCode != null && !autoCode.isEmpty() && otpField != null) {
                        verifyingInFlight = true;
                        otpField.setText(autoCode);
                        otpField.setSelection(autoCode.length());
                    }
                    showStatus("✅ SMS OTP auto-verified! Signing you in…", Ui.GREEN);
                    signInWithPhoneCredential(credential);
                }

                @Override
                public void onVerificationFailed(@NonNull FirebaseException e) {
                    if (isFinishing()) return;
                    verifyingInFlight = false;
                    sendOtpBtn.setEnabled(true);
                    sendOtpBtn.setText("📲 Send OTP Code");
                    if (resendOtpBtn != null) resendOtpBtn.setEnabled(true);
                    showStatus("❌ " + humanPhoneError(e), 0xFFE41E3F);
                }

                @Override
                public void onCodeSent(@NonNull String verId,
                                       @NonNull PhoneAuthProvider.ForceResendingToken token) {
                    if (isFinishing()) return;
                    verificationId = verId;
                    resendToken = token;
                    verifyingInFlight = false;

                    phoneStepBox.setVisibility(View.GONE);
                    otpStepBox.setVisibility(View.VISIBLE);
                    otpField.setText("");
                    otpField.requestFocus();
                    verifyOtpBtn.setEnabled(true);
                    verifyOtpBtn.setText("✓ Verify & Continue");

                    titleTv.setText("Enter 6-Digit SMS OTP");
                    subTv.setText("Code sent to " + activeE164Phone + "\nOTP will auto-fill when the SMS arrives.");
                    showStatus("📲 SMS OTP sent to " + activeE164Phone + "!", Ui.GREEN);
                    startResendCountdown();
                }
            };

    private void startResendCountdown() {
        stopTimer();
        resendOtpBtn.setEnabled(false);
        resendTimer = new CountDownTimer(60_000L, 1_000L) {
            @Override
            public void onTick(long ms) {
                resendOtpBtn.setText(String.format(Locale.US, "Resend SMS OTP (%ds)", ms / 1000L + 1));
            }

            @Override
            public void onFinish() {
                resendOtpBtn.setEnabled(true);
                resendOtpBtn.setText("Resend SMS OTP");
            }
        }.start();
    }

    private void verifyEnteredCode(String code) {
        if (verificationId == null || verificationId.isEmpty()) {
            Ui.toast(this, "Please request an OTP code first");
            return;
        }
        verifyingInFlight = true;
        Ui.hideKeyboard(this);
        verifyOtpBtn.setEnabled(false);
        verifyOtpBtn.setText("Verifying…");
        showStatus("Verifying 6-digit OTP…", Ui.BLUE);

        PhoneAuthCredential credential = PhoneAuthProvider.getCredential(verificationId, code);
        signInWithPhoneCredential(credential);
    }

    private void signInWithPhoneCredential(PhoneAuthCredential credential) {
        verifyingInFlight = true;
        Fb.auth().signInWithCredential(credential)
                .addOnSuccessListener(result -> {
                    FirebaseUser fbUser = result.getUser();
                    if (fbUser == null) {
                        verifyingInFlight = false;
                        verifyOtpBtn.setEnabled(true);
                        verifyOtpBtn.setText("✓ Verify & Continue");
                        showStatus("❌ Authentication failed — please try again", 0xFFE41E3F);
                        return;
                    }
                    String uid = fbUser.getUid();
                    String resolvedPhone = fbUser.getPhoneNumber() != null && !fbUser.getPhoneNumber().isEmpty()
                            ? fbUser.getPhoneNumber() : activeE164Phone;

                    Fb.db().collection("users").document(uid).get()
                            .addOnSuccessListener(doc -> handlePostPhoneSignIn(uid, resolvedPhone, doc))
                            .addOnFailureListener(err -> createOrMergePhoneUser(uid, resolvedPhone, null, true));
                })
                .addOnFailureListener(err -> {
                    verifyingInFlight = false;
                    if (verifyOtpBtn != null) {
                        verifyOtpBtn.setEnabled(true);
                        verifyOtpBtn.setText("✓ Verify & Continue");
                    }
                    String msg = err instanceof FirebaseAuthInvalidCredentialsException
                            ? "Invalid OTP code — please check the 6-digit SMS code."
                            : (err.getMessage() == null ? "OTP verification failed" : err.getMessage());
                    showStatus("❌ " + msg, 0xFFE41E3F);
                });
    }

    private void handlePostPhoneSignIn(String uid, String phoneNumber, DocumentSnapshot doc) {
        if (doc != null && doc.exists()) {
            // Existing user login!
            if (Boolean.TRUE.equals(doc.getBoolean("banned"))) {
                Fb.auth().signOut();
                verifyingInFlight = false;
                Ui.info(this, "Account suspended",
                        "This account has been suspended by the friendbook admin.");
                return;
            }
            if (Boolean.TRUE.equals(doc.getBoolean("deactivated"))) {
                Fb.db().collection("users").document(uid)
                        .set(Fb.map("deactivated", false), SetOptions.merge());
            }
            // Always ensure phoneNumber is saved on the user's document in `users` collection
            Map<String, Object> patch = new HashMap<>();
            patch.put("phoneNumber", phoneNumber);
            patch.put("phone", phoneNumber);
            patch.put("isOnline", true);
            patch.put("lastSeen", FieldValue.serverTimestamp());
            Fb.db().collection("users").document(uid).set(patch, SetOptions.merge());

            if (Boolean.TRUE.equals(doc.getBoolean("loginAlerts"))) {
                Notify.send(uid, "security", "logged in via Phone on " + Build.MODEL, "");
            }

            String existingName = doc.getString("displayName");
            String existingPhoto = doc.getString("photoUrl");
            Prefs.saveAccount(this, uid, phoneNumber,
                    existingName == null || existingName.isEmpty() ? phoneNumber : existingName,
                    existingPhoto == null ? "" : existingPhoto);
            finishIntoMain();
        } else {
            // Brand-new phone user -> Auto-Signup & save `phoneNumber` in `users` collection!
            String typedName = nameField != null ? nameField.getText().toString().trim() : "";
            if (typedName.length() >= 2) {
                createOrMergePhoneUser(uid, phoneNumber, typedName, true);
            } else {
                // Save `users/{uid}` immediately with `phoneNumber` so auto-signup is guaranteed,
                // then let user optionally personalize their display name in 1 tap!
                String defaultName = "User " + (phoneNumber.length() >= 4
                        ? phoneNumber.substring(phoneNumber.length() - 4) : "New");
                createOrMergePhoneUser(uid, phoneNumber, defaultName, false);
                promptNewUserDisplayName(uid, phoneNumber, defaultName);
            }
        }
    }

    private void createOrMergePhoneUser(String uid, String phoneNumber, String displayName, boolean launchNow) {
        String cleanName = (displayName == null || displayName.trim().isEmpty())
                ? ("User " + (phoneNumber.length() >= 4 ? phoneNumber.substring(phoneNumber.length() - 4) : ""))
                : displayName.trim();

        Map<String, Object> u = new HashMap<>();
        u.put("displayName", cleanName);
        u.put("phoneNumber", phoneNumber);
        u.put("phone", phoneNumber);
        u.put("email", "");
        u.put("photoUrl", "");
        u.put("bio", "");
        u.put("coverUrl", "");
        u.put("authProvider", "phone");
        u.put("isOnline", true);
        u.put("friendsCount", 0);
        u.put("followers", new ArrayList<String>());
        u.put("following", new ArrayList<String>());
        u.put("blockedUsers", new ArrayList<String>());
        u.put("createdAt", FieldValue.serverTimestamp());

        Fb.db().collection("users").document(uid).set(u, SetOptions.merge())
                .addOnSuccessListener(v -> {
                    recordReferralIfPresent(uid, cleanName);
                    Prefs.saveAccount(this, uid, phoneNumber, cleanName, "");
                    if (launchNow) finishIntoMain();
                })
                .addOnFailureListener(e -> {
                    Prefs.saveAccount(this, uid, phoneNumber, cleanName, "");
                    if (launchNow) finishIntoMain();
                });
    }

    private void promptNewUserDisplayName(String uid, String phoneNumber, String fallbackName) {
        if (isFinishing()) { finishIntoMain(); return; }
        EditText nameIn = Ui.input(this, "Enter your full name");
        nameIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        box.addView(nameIn, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

        new AlertDialog.Builder(this)
                .setTitle("👋 Welcome to friendbook!")
                .setMessage("Your phone number (" + phoneNumber + ") is verified. What name should friends see on your profile?")
                .setCancelable(false)
                .setView(box)
                .setPositiveButton("Continue", (d, w) -> {
                    String entered = nameIn.getText().toString().trim();
                    String finalName = entered.length() >= 2 ? entered : fallbackName;
                    Fb.db().collection("users").document(uid)
                            .set(Fb.map("displayName", finalName, "phoneNumber", phoneNumber, "phone", phoneNumber),
                                    SetOptions.merge())
                            .addOnCompleteListener(t -> {
                                Prefs.saveAccount(this, uid, phoneNumber, finalName, "");
                                finishIntoMain();
                            });
                })
                .setNegativeButton("Skip", (d, w) -> finishIntoMain())
                .show();
    }

    private void recordReferralIfPresent(String newUid, String newName) {
        String code = Admin.extractRefCode(refField != null ? refField.getText().toString() : Prefs.pendingReferral(this));
        if (code == null || code.isEmpty() || code.equals(newUid)) return;
        Prefs.setPendingReferral(this, "");
        Fb.db().collection("users").document(code).get().addOnSuccessListener(d -> {
            if (d == null || !d.exists()) return;
            Map<String, Object> r = new HashMap<>();
            r.put("refBy", code);
            r.put("refTo", newUid);
            r.put("status", "completed");
            r.put("date", FieldValue.serverTimestamp());
            r.put("createdAt", FieldValue.serverTimestamp());
            Fb.db().collection("referrals").document(code + "_" + newUid).set(r);

            Map<String, Object> n = new HashMap<>();
            n.put("toUserId", code);
            n.put("fromUserId", newUid);
            n.put("fromUserName", newName);
            n.put("fromUserPhotoUrl", "");
            n.put("type", "referral");
            n.put("text", "joined friendbook via your invite 🎉 (referral credit +1)");
            n.put("isRead", false);
            n.put("createdAt", FieldValue.serverTimestamp());
            Fb.db().collection("notifications").add(n);
        });
    }

    private void finishIntoMain() {
        Prefs.setUnlockedSession(this, true);
        SessionsActivity.record();
        Fb.startMeListener();
        Presence.init();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finishAffinity();
    }

    private String humanPhoneError(FirebaseException e) {
        if (e instanceof FirebaseAuthInvalidCredentialsException) {
            return "Invalid phone number format. Include your country code (e.g. +923001234567).";
        }
        if (e instanceof FirebaseTooManyRequestsException) {
            return "SMS quota or rate limit reached for this number — please try again shortly.";
        }
        String m = e.getMessage() == null ? "Phone verification failed" : e.getMessage();
        if (m.contains("BILLING_NOT_ENABLED")) {
            return "Firebase Phone SMS limit reached or Phone provider needs enabling in Firebase Console → Authentication → Sign-in method → Phone.";
        }
        if (m.contains("missing a valid app identifier") || m.contains("app-not-authorized")) {
            return "Add release SHA-1 & SHA-256 fingerprints in Firebase Console → Project Settings → com.friendbook.";
        }
        return m;
    }

    private void registerSmsConsentReceiver() {
        if (smsReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(smsConsentReceiver, filter, SmsRetriever.SEND_PERMISSION, null,
                        Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(smsConsentReceiver, filter, SmsRetriever.SEND_PERMISSION, null);
            }
            smsReceiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SMS_CONSENT && resultCode == RESULT_OK && data != null) {
            String message = data.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE);
            if (message != null && otpField != null) {
                Matcher matcher = SIX_DIGIT_OTP.matcher(message);
                if (matcher.find()) {
                    String otp = matcher.group(1);
                    otpField.setText(otp);
                    otpField.setSelection(otp.length());
                }
            }
        }
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
        if (smsReceiverRegistered) {
            try { unregisterReceiver(smsConsentReceiver); } catch (Throwable ignored) {}
            smsReceiverRegistered = false;
        }
        super.onDestroy();
    }
}
