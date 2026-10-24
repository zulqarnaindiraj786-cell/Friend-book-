package com.example.frienbook.core;

import android.content.Context;

import com.example.frienbook.models.Models;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.WriteBatch;

/** Admin gating + blue-tick management. Owner: zulqarnaindiraj786@gmail.com */
public class Admin {

    public static final String OWNER_EMAIL = "zulqarnaindiraj786@gmail.com";

    /** How many completed referrals earn a FREE blue tick. */
    public static final int REFERRALS_FOR_TICK = 5;

    /**
     * Admin access: ONLY the owner email (checked straight from Firebase Auth,
     * so it works even before the profile doc loads) or promoted admins.
     * Normal users NEVER see the admin panel.
     */
    public static boolean isAdmin() {
        if (Fb.auth().getCurrentUser() == null) return false;
        String email = Fb.auth().getCurrentUser().getEmail();
        if (isOwnerEmail(email)) return true;
        Models.User me = Fb.me;
        if (me != null) {
            if (me.email != null && isOwnerEmail(me.email)) return true;
            return me.isAdmin;
        }
        return false;
    }

    /** Firestore rules-side check uses this email too (see firebase/firestore.rules). */
    public static boolean isOwnerEmail(String email) {
        return email != null && email.equalsIgnoreCase(OWNER_EMAIL);
    }

    /** Give the blue tick to any user (admin's choice, instant). */
    public static void grantBlueTick(Context c, String uid, String displayName) {
        if (!isAdmin()) { Ui.toast(c, "Admins only"); return; }
        Fb.db().collection("users").document(uid)
                .update("isVerified", true)
                .addOnSuccessListener(x -> {
                    syncAuthorVerified(uid, true);
                    Ui.toast(c, "✓ Blue tick given to " + displayName);
                })
                .addOnFailureListener(e -> Ui.toast(c, "Failed: " + e.getMessage()));
    }

    /** Remove the blue tick. */
    public static void removeBlueTick(Context c, String uid, String displayName) {
        if (!isAdmin()) { Ui.toast(c, "Admins only"); return; }
        Fb.db().collection("users").document(uid)
                .update("isVerified", false)
                .addOnSuccessListener(x -> {
                    syncAuthorVerified(uid, false);
                    Ui.toast(c, "Blue tick removed from " + displayName);
                })
                .addOnFailureListener(e -> Ui.toast(c, "Failed: " + e.getMessage()));
    }

    /** Public wrapper used by admin payment approvals. */
    public static void syncAuthorVerifiedPublic(String uid, boolean value) {
        syncAuthorVerified(uid, value);
    }

    /** Push the verified flag onto the user's recent content so feed ticks stay in sync. */
    private static void syncAuthorVerified(String uid, boolean value) {
        String[] cols = {"posts", "comments", "reels", "stories"};
        for (String col : cols) {
            String field = col.equals("posts") ? "authorId" : "authorId";
            Fb.db().collection(col).whereEqualTo(field, uid).limit(300).get()
                    .addOnSuccessListener(snap -> {
                        if (snap.isEmpty()) return;
                        WriteBatch b = Fb.db().batch();
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap)
                            b.set(d.getReference(), Fb.map("authorVerified", value),
                                    com.google.firebase.firestore.SetOptions.merge());
                        b.commit();
                    });
        }
    }

    /** Referral invite link for a user (friendbook.app deep link). */
    public static String inviteLink(String uid) {
        return "https://friendbook.app/invite?ref=" + uid;
    }

    /** Extract the referral code from an invite link or raw code. Returns null when empty. */
    public static String extractRefCode(String input) {
        if (input == null) return null;
        input = input.trim();
        if (input.isEmpty()) return null;
        int idx = input.indexOf("ref=");
        if (idx >= 0) {
            String code = input.substring(idx + 4);
            int amp = code.indexOf('&');
            if (amp > 0) code = code.substring(0, amp);
            return code.trim().isEmpty() ? null : code.trim();
        }
        return input; // plain uid pasted
    }
}
