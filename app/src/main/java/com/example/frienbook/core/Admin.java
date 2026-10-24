package com.example.frienbook.core;

import android.content.Context;

import com.example.frienbook.models.Models;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.WriteBatch;

/** Admin gating + tick/boost management. Owner: zulqarnaindiraj786@gmail.com */
public class Admin {

    public static final String OWNER_EMAIL = "zulqarnaindiraj786@gmail.com";

    /** How many completed referrals earn FREE ticks. */
    public static final int REFERRALS_BLUE = 5;
    public static final int REFERRALS_GOLD = 20;
    public static final int REFERRALS_DIAMOND = 50;

    /** Paid prices in PKR. */
    public static final int PRICE_BLUE = 100;
    public static final int PRICE_GOLD = 500;
    public static final int PRICE_DIAMOND = 1000;
    public static final int PRICE_BOOST_PROFILE = 50; // 24h
    public static final int PRICE_BOOST_POST = 30;
    public static final int STAR_VALUE_PKR = 10;      // 1 star = Rs 10

    /** Friendly label for a tick level. */
    public static String tickLabel(String level) {
        if ("blue".equals(level)) return "Blue tick ✓";
        if ("gold".equals(level)) return "Gold tick ✓";
        if ("diamond".equals(level)) return "Diamond tick 💎";
        return "No tick";
    }

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

    /**
     * Give ANY tick level to any user (admin's choice, instant).
     * level: none | blue | gold | diamond.
     */
    public static void setTickLevel(Context c, String uid, String displayName, String level) {
        if (!isAdmin()) { Ui.toast(c, "Admins only"); return; }
        Fb.db().collection("users").document(uid)
                .set(Fb.map("tickLevel", level, "isVerified", Icons.tickRank(level) > 0),
                        com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(x -> {
                    syncAuthorVerified(uid, Icons.tickRank(level) > 0);
                    java.util.Map<String, Object> n = Fb.map(
                            "toUserId", uid,
                            "fromUserId", Fb.uid(),
                            "fromUserName", "friendbook Team",
                            "fromUserPhotoUrl", "",
                            "type", "tick",
                            "text", Icons.tickRank(level) > 0
                                    ? "✓ Congratulations! Your account now has the " + tickLabel(level) + "."
                                    : "The tick on your account has been removed.",
                            "isRead", false,
                            "createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("notifications").add(n);
                    Ui.toast(c, tickLabel(level) + " → " + displayName);
                })
                .addOnFailureListener(e -> Ui.toast(c, "Failed: " + e.getMessage()));
    }

    /** Back-compat shortcuts (blue / none). */
    public static void grantBlueTick(Context c, String uid, String displayName) {
        setTickLevel(c, uid, displayName, "blue");
    }

    public static void removeBlueTick(Context c, String uid, String displayName) {
        setTickLevel(c, uid, displayName, "none");
    }

    /** Public wrapper used by admin payment approvals. */
    public static void syncAuthorVerifiedPublic(String uid, boolean value) {
        syncAuthorVerified(uid, value);
    }

    /** Push the author's tick state onto their recent content so feed badges stay in sync. */
    private static void syncAuthorVerified(String uid, boolean value) {
        String level = value ? null : "none"; // unknown level here; callers use syncAuthorTickLevel for the exact one
        String[] cols = {"posts", "comments", "reels", "stories"};
        for (String col : cols) {
            Fb.db().collection(col).whereEqualTo("authorId", uid).limit(300).get()
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

    /** Push the exact tick level (blue/gold/diamond) onto the user's content. */
    public static void syncAuthorTickLevel(String uid, String level) {
        String[] cols = {"posts", "comments", "reels", "stories"};
        for (String col : cols) {
            Fb.db().collection(col).whereEqualTo("authorId", uid).limit(300).get()
                    .addOnSuccessListener(snap -> {
                        if (snap.isEmpty()) return;
                        WriteBatch b = Fb.db().batch();
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap)
                            b.set(d.getReference(), Fb.map("authorTickLevel", level,
                                            "authorVerified", Icons.tickRank(level) > 0),
                                    com.google.firebase.firestore.SetOptions.merge());
                        b.commit();
                    });
        }
    }

    /**
     * Auto-grant the highest referral-earned tick (called when a user's referral count changes).
     * Only ever upgrades (or confirms), never downgrades a higher tick earned some other way.
     */
    public static void autoGrantByReferrals(final Context c, int referralCount) {
        final String uid = Fb.uid();
        if (uid == null) return;
        final String level = referralCount >= REFERRALS_DIAMOND ? "diamond"
                : referralCount >= REFERRALS_GOLD ? "gold"
                : referralCount >= REFERRALS_BLUE ? "blue" : "none";
        if ("none".equals(level)) return;
        Models.User me = Fb.me;
        int currentRank = me != null ? me.tickRank() : 0;
        if (currentRank >= Icons.tickRank(level)) return; // already this good or better
        Fb.db().collection("users").document(uid)
                .set(Fb.map("tickLevel", level, "isVerified", true),
                        com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(x -> {
                    java.util.Map<String, Object> n = Fb.map(
                            "toUserId", uid,
                            "fromUserId", uid,
                            "fromUserName", "friendbook",
                            "fromUserPhotoUrl", "",
                            "type", "tick",
                            "text", "🎉 Congratulations! " + referralCount + " friends joined from your invite — you earned the " + tickLabel(level) + " for FREE!",
                            "isRead", false,
                            "createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("notifications").add(n);
                    Ui.toast(c, "🎉 You earned the " + tickLabel(level) + "!");
                });
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

    /** "yyyy-MM" bucket for earnings charts. */
    public static String monthKey() {
        return new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
