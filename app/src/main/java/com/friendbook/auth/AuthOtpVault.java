package com.friendbook.auth;

import android.app.Activity;
import android.util.Base64;
import android.util.Patterns;

import com.friendbook.core.Fb;
import com.friendbook.core.Prefs;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.SetOptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 🔐 Dynamic Friendbook OTP & Spark-Plan Password Reset Engine:
 * - Works dynamically for ANY email entered by ANY user (zero hardcoded emails, zero Cloud Functions).
 * - Writes 6-digit OTP to Firestore `otps` collection (`email`, `otp`, `createdAt`, `expiresAt` +10 min).
 * - Dispatches real OTP email via Firebase Trigger Email extension (`mail` collection).
 * - Enables direct in-app `FirebaseUser.updatePassword(newPassword)` after OTP verification on Spark plan.
 */
public final class AuthOtpVault {

    public static final long OTP_TTL_MS = 10L * 60L * 1000L; // 10 minutes
    private static final String VAULT_SECRET = "FriendbookSparkAuthVault_v2026_#91a3f75c";
    private static final SecureRandom RNG = new SecureRandom();

    private AuthOtpVault() {}

    public interface SendCallback {
        void onSent(String normalizedEmail);
        void onError(String message);
    }

    public interface VerifyCallback {
        void onVerified();
        void onError(String message);
    }

    public interface PasswordUpdateCallback {
        /** @param signedInReady true if FirebaseUser password was updated in-app and user is signed in */
        void onSuccess(boolean signedInReady, String infoMessage);
        void onError(String message);
    }

    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.US);
    }

    public static boolean isValidEmail(String raw) {
        String e = normalizeEmail(raw);
        return !e.isEmpty() && Patterns.EMAIL_ADDRESS.matcher(e).matches();
    }

    /**
     * Generates a 6-digit OTP for ANY email, saves to Firestore `otps`, and enqueues
     * the email in Firestore `mail` for the Firebase Trigger Email extension.
     */
    public static void sendEmailOtp(String rawEmail, SendCallback cb) {
        final String email = normalizeEmail(rawEmail);
        if (!isValidEmail(email)) {
            if (cb != null) cb.onError("Please enter a valid email address");
            return;
        }

        final String otp = String.format(Locale.US, "%06d", 100000 + RNG.nextInt(900000));
        final long nowMs = System.currentTimeMillis();
        final Timestamp createdAt = new Timestamp(new Date(nowMs));
        final Timestamp expiresAt = new Timestamp(new Date(nowMs + OTP_TTL_MS));

        Map<String, Object> otpDoc = new HashMap<>();
        otpDoc.put("email", email);
        otpDoc.put("otp", otp);
        otpDoc.put("createdAt", createdAt);
        otpDoc.put("expiresAt", expiresAt);

        Fb.db().collection("otps").add(otpDoc)
                .addOnSuccessListener(otpRef -> {
                    Map<String, Object> message = new HashMap<>();
                    message.put("subject", "Friendbook OTP");
                    message.put("html", "<h1>OTP: " + otp + "</h1><p>Valid 10 min, check spam</p>");

                    Map<String, Object> mailDoc = new HashMap<>();
                    mailDoc.put("to", Collections.singletonList(email));
                    mailDoc.put("message", message);

                    Fb.db().collection("mail").add(mailDoc)
                            .addOnSuccessListener(mailRef -> {
                                if (cb != null) cb.onSent(email);
                            })
                            .addOnFailureListener(err -> {
                                if (cb != null) cb.onError(err.getMessage() == null
                                        ? "Failed to queue OTP email" : err.getMessage());
                            });
                })
                .addOnFailureListener(err -> {
                    if (cb != null) cb.onError(err.getMessage() == null
                            ? "Failed to generate OTP" : err.getMessage());
                });
    }

    /**
     * Verifies the 6-digit OTP against Firestore `otps` collection (`email`, `otp`, `expiresAt`).
     * Uses single-field equality query on `email` so zero composite index is required on Spark plan.
     */
    public static void verifyEmailOtp(String rawEmail, String rawOtp, VerifyCallback cb) {
        final String email = normalizeEmail(rawEmail);
        final String enteredOtp = rawOtp == null ? "" : rawOtp.trim();
        if (!isValidEmail(email)) {
            if (cb != null) cb.onError("Invalid email address");
            return;
        }
        if (enteredOtp.length() != 6) {
            if (cb != null) cb.onError("Please enter the 6-digit OTP");
            return;
        }

        Fb.db().collection("otps")
                .whereEqualTo("email", email)
                .get()
                .addOnSuccessListener(snap -> {
                    if (snap == null || snap.isEmpty()) {
                        if (cb != null) cb.onError("No OTP found for this email. Tap Resend Code.");
                        return;
                    }

                    long nowMs = System.currentTimeMillis();
                    DocumentSnapshot matchedDoc = null;
                    long newestCreatedMs = Long.MIN_VALUE;
                    boolean foundExpiredMatch = false;

                    for (DocumentSnapshot d : snap.getDocuments()) {
                        if (Boolean.TRUE.equals(d.getBoolean("used"))) continue;
                        String savedOtp = d.getString("otp");
                        if (savedOtp == null || !savedOtp.trim().equals(enteredOtp)) continue;

                        long expMs = extractMillis(d.get("expiresAt"));
                        long crtMs = extractMillis(d.get("createdAt"));
                        if (expMs > 0 && nowMs > expMs + 15_000L) {
                            foundExpiredMatch = true;
                            continue;
                        }
                        if (crtMs >= newestCreatedMs) {
                            newestCreatedMs = crtMs;
                            matchedDoc = d;
                        }
                    }

                    if (matchedDoc == null) {
                        if (foundExpiredMatch) {
                            if (cb != null) cb.onError("OTP expired (valid 10 min). Please request a new code.");
                        } else {
                            if (cb != null) cb.onError("Invalid OTP code. Please check your inbox or spam folder.");
                        }
                        return;
                    }

                    // Consume verified OTP and clean up older OTP docs for this email
                    final String matchedId = matchedDoc.getId();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        if (d.getId().equals(matchedId)) {
                            d.getReference().delete();
                        } else {
                            long expMs = extractMillis(d.get("expiresAt"));
                            if (expMs > 0 && nowMs > expMs) {
                                d.getReference().delete();
                            }
                        }
                    }
                    if (cb != null) cb.onVerified();
                })
                .addOnFailureListener(err -> {
                    if (cb != null) cb.onError(err.getMessage() == null
                            ? "OTP verification failed" : err.getMessage());
                });
    }

    /**
     * Applies the new password via Firebase Auth `FirebaseUser.updatePassword(newPassword)` on Spark plan!
     */
    public static void applyNewPassword(Activity act, String rawEmail, String newPassword, PasswordUpdateCallback cb) {
        final String email = normalizeEmail(rawEmail);
        if (newPassword == null || newPassword.length() < 6) {
            if (cb != null) cb.onError("Password must be at least 6 characters");
            return;
        }

        // Case 1: User is already authenticated with this email
        FirebaseUser current = Fb.auth().getCurrentUser();
        if (current != null && current.getEmail() != null
                && normalizeEmail(current.getEmail()).equals(email)) {
            current.updatePassword(newPassword)
                    .addOnSuccessListener(v -> {
                        saveEncryptedSync(email, newPassword);
                        ensureUserProfileDoc(act, current.getUid(), email);
                        if (cb != null) cb.onSuccess(true, "Password updated successfully!");
                    })
                    .addOnFailureListener(e -> reauthViaVaultAndUpdate(act, email, newPassword, cb));
            return;
        }

        // Case 2: Authenticate via encrypted Spark-plan vault & call FirebaseUser.updatePassword(newPassword)
        reauthViaVaultAndUpdate(act, email, newPassword, cb);
    }

    private static void reauthViaVaultAndUpdate(Activity act, String email, String newPassword, PasswordUpdateCallback cb) {
        String keyId = sha256(email);
        Fb.db().collection("authVault").document(keyId).get()
                .addOnSuccessListener(doc -> {
                    String cipherBlob = doc != null && doc.exists() ? doc.getString("v") : null;
                    String syncedCred = cipherBlob != null ? decrypt(email, cipherBlob) : null;
                    if (syncedCred != null && !syncedCred.isEmpty()) {
                        Fb.auth().signInWithEmailAndPassword(email, syncedCred)
                                .addOnSuccessListener(res -> {
                                    FirebaseUser u = res.getUser();
                                    if (u == null) {
                                        if (cb != null) cb.onError("Could not load user session");
                                        return;
                                    }
                                    u.updatePassword(newPassword)
                                            .addOnSuccessListener(x -> {
                                                saveEncryptedSync(email, newPassword);
                                                ensureUserProfileDoc(act, u.getUid(), email);
                                                if (cb != null) cb.onSuccess(true, "Password updated successfully!");
                                            })
                                            .addOnFailureListener(err -> {
                                                if (cb != null) cb.onError(err.getMessage() == null
                                                        ? "Failed to update password" : err.getMessage());
                                            });
                                })
                                .addOnFailureListener(err -> provisionOrFallbackReset(act, email, newPassword, cb));
                    } else {
                        provisionOrFallbackReset(act, email, newPassword, cb);
                    }
                })
                .addOnFailureListener(err -> provisionOrFallbackReset(act, email, newPassword, cb));
    }

    private static void provisionOrFallbackReset(Activity act, String email, String newPassword, PasswordUpdateCallback cb) {
        // If this email does not yet have a Firebase Auth record, create it with the verified email + newPassword
        Fb.auth().createUserWithEmailAndPassword(email, newPassword)
                .addOnSuccessListener(res -> {
                    FirebaseUser u = res.getUser();
                    saveEncryptedSync(email, newPassword);
                    if (u != null) ensureUserProfileDoc(act, u.getUid(), email);
                    if (cb != null) cb.onSuccess(true, "Password set & account ready!");
                })
                .addOnFailureListener(err -> {
                    if (err instanceof FirebaseAuthUserCollisionException
                            || (err.getMessage() != null && err.getMessage().toLowerCase(Locale.US).contains("already in use"))) {
                        // Try signing in directly in case newPassword already matches current Firebase password
                        Fb.auth().signInWithEmailAndPassword(email, newPassword)
                                .addOnSuccessListener(res -> {
                                    FirebaseUser u = res.getUser();
                                    if (u != null) {
                                        u.updatePassword(newPassword);
                                        saveEncryptedSync(email, newPassword);
                                        ensureUserProfileDoc(act, u.getUid(), email);
                                    }
                                    if (cb != null) cb.onSuccess(true, "Password verified & updated!");
                                })
                                .addOnFailureListener(signInErr -> {
                                    // Legacy pre-vault account fallback: trigger Firebase Auth password reset link
                                    Fb.auth().sendPasswordResetEmail(email)
                                            .addOnSuccessListener(v -> {
                                                if (cb != null) cb.onSuccess(false,
                                                        "OTP verified! Because this older account predates direct OTP sync, a 1-tap Firebase password confirmation link was also sent to "
                                                                + email + " (check inbox/spam).");
                                            })
                                            .addOnFailureListener(re -> {
                                                if (cb != null) cb.onError(re.getMessage() == null
                                                        ? "Could not finalize password update" : re.getMessage());
                                            });
                                });
                    } else {
                        if (cb != null) cb.onError(err.getMessage() == null
                                ? "Failed to set password" : err.getMessage());
                    }
                });
    }

    private static void ensureUserProfileDoc(Activity act, String uid, String email) {
        if (uid == null) return;
        Fb.db().collection("users").document(uid).get().addOnSuccessListener(d -> {
            String fallbackName = email.contains("@") ? email.substring(0, email.indexOf('@')) : "Friend";
            if (d == null || !d.exists()) {
                Map<String, Object> u = new HashMap<>();
                u.put("displayName", fallbackName);
                u.put("email", email);
                u.put("photoUrl", "");
                u.put("bio", "");
                u.put("coverUrl", "");
                u.put("isOnline", true);
                u.put("friendsCount", 0);
                u.put("followers", new ArrayList<String>());
                u.put("following", new ArrayList<String>());
                u.put("blockedUsers", new ArrayList<String>());
                u.put("createdAt", FieldValue.serverTimestamp());
                Fb.db().collection("users").document(uid).set(u, SetOptions.merge());
                if (act != null) Prefs.saveAccount(act, uid, email, fallbackName, "");
            } else if (act != null) {
                String n = d.getString("displayName");
                String ph = d.getString("photoUrl");
                Prefs.saveAccount(act, uid, email,
                        n == null || n.isEmpty() ? fallbackName : n,
                        ph == null ? "" : ph);
            }
        });
    }

    /** Stores AES-256-GCM encrypted credential sync so Spark-plan `updatePassword` works after OTP verification. */
    public static void saveEncryptedSync(String rawEmail, String plainPassword) {
        String email = normalizeEmail(rawEmail);
        if (!isValidEmail(email) || plainPassword == null || plainPassword.isEmpty()) return;
        String encrypted = encrypt(email, plainPassword);
        if (encrypted == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("v", encrypted);
        data.put("updatedAt", FieldValue.serverTimestamp());
        Fb.db().collection("authVault").document(sha256(email)).set(data, SetOptions.merge());
    }

    private static long extractMillis(Object val) {
        if (val instanceof Timestamp) return ((Timestamp) val).toDate().getTime();
        if (val instanceof Date) return ((Date) val).getTime();
        if (val instanceof Number) return ((Number) val).longValue();
        return 0L;
    }

    private static SecretKey deriveKey(String email) throws Exception {
        byte[] salt = sha256("fbk_salt::" + email).getBytes(StandardCharsets.UTF_8);
        KeySpec spec = new PBEKeySpec(
                (VAULT_SECRET + "::" + email).toCharArray(),
                salt, 12000, 256);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] raw = f.generateSecret(spec).getEncoded();
        return new SecretKeySpec(raw, "AES");
    }

    private static String encrypt(String email, String plain) {
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, deriveKey(email), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String decrypt(String email, String blob) {
        try {
            byte[] combined = Base64.decode(blob, Base64.NO_WRAP);
            if (combined.length <= 12) return null;
            byte[] iv = new byte[12];
            byte[] ct = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, deriveKey(email), new GCMParameterSpec(128, iv));
            byte[] pt = c.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
