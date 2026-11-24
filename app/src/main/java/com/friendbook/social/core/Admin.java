package com.friendbook.social.core;

import android.content.Context;

import com.friendbook.social.models.Models;
import com.friendbook.social.d1.documents.WriteBatch;

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

    /** UI gate only; the backend independently verifies the authenticated UID. */
    public static final String OWNER_UID = "ZlCw7oeKitPbhAcEryjnz2Do2Ky2";

    public static boolean isAdmin() {
        return Fb.auth().getCurrentUser() != null
                && OWNER_UID.equals(Fb.auth().getCurrentUser().getUid());
    }

    /**
     * Global App Moderator (`users/{uid}.isModerator == true`):
     * Can ONLY delete regular users' posts & ban/unban regular users.
     * NEVER gets access to AdminPanelActivity (`isAdmin()` stays false!).
     */
    public static boolean isModerator() {
        if (Fb.auth().getCurrentUser() == null) return false;
        Models.User me = Fb.me;
        return me != null && me.isModerator;
    }

    /** True if user is either Full Admin OR Global App Moderator. */
    public static boolean canModerate() {
        return isAdmin() || isModerator();
    }

    /** Protects Owner & Full Admins so a Global Moderator can never ban them or delete their posts. */
    public static boolean isProtectedStaff(Models.User u) {
        if (u == null) return false;
        return isOwnerEmail(u.email) || u.isAdmin;
    }

    /** True when the currently signed-in user has an active posting/commenting/video suspension. */
    public static boolean isCurrentUserSuspended() {
        Models.User me = Fb.me;
        return me != null && me.isSuspendedNow();
    }

    /** Human-readable message explaining why posting, commenting & video upload are blocked. */
    public static String getSuspensionMessage() {
        Models.User me = Fb.me;
        if (me == null || !me.isSuspendedNow()) return "";
        String rem = me.suspensionStatusLabel();
        if ("Lifetime".equalsIgnoreCase(rem)) {
            return "⚠️ Your account is suspended (Lifetime). You can view posts & videos, but cannot post, comment, or upload videos.";
        }
        return "⚠️ Your account is suspended for " + rem + ". You can view posts & videos, but cannot post, comment, or upload videos.";
    }

    /** Returns true if allowed to post/comment/upload; shows toast and returns false if suspended. */
    public static boolean checkNotSuspended(Context c) {
        if (isCurrentUserSuspended()) {
            if (c != null) Ui.toast(c, getSuspensionMessage());
            return false;
        }
        return true;
    }

    /**
     * Dialog for Admin or Moderator to suspend a user's ID (1 Day, 1 Week, 30 Days, 60 Days, Lifetime)
     * or unsuspend them. Suspended users can view posts & videos, but cannot post, comment, or upload videos.
     */
    public static void showSuspendUserDialog(final Context c, final Models.User target, final Runnable onDone) {
        if (c == null || target == null || target.uid == null || target.uid.isEmpty()) return;
        if (!canModerate()) {
            Ui.toast(c, "Admins and Moderators only");
            return;
        }
        if (isOwnerEmail(target.email) || (!isAdmin() && isProtectedStaff(target))) {
            Ui.toast(c, "Admin accounts cannot be suspended");
            return;
        }
        final String name = (target.displayName != null && !target.displayName.isEmpty())
                ? target.displayName : "User";
        String statusHdr = target.isSuspendedNow()
                ? ("Currently Suspended (" + target.suspensionStatusLabel() + ")")
                : "Active Account";
        final String[] options = new String[]{
                "⏳ Suspend for 1 Day (24 Hours)",
                "⏳ Suspend for 1 Week (7 Days)",
                "⏳ Suspend for 30 Days",
                "⏳ Suspend for 60 Days",
                "⛔ Suspend for Lifetime",
                "✅ Unsuspend Account (Restore All Access)"
        };
        Ui.choose(c, "🛡 Suspend / Unsuspend " + name + "\n" + statusHdr, options, which -> {
            long dayMs = 24L * 3600_000L;
            if (which == 0) {
                applyUserSuspension(c, target, dayMs, false, "1 Day", onDone);
            } else if (which == 1) {
                applyUserSuspension(c, target, 7L * dayMs, false, "1 Week (7 Days)", onDone);
            } else if (which == 2) {
                applyUserSuspension(c, target, 30L * dayMs, false, "30 Days", onDone);
            } else if (which == 3) {
                applyUserSuspension(c, target, 60L * dayMs, false, "60 Days", onDone);
            } else if (which == 4) {
                applyUserSuspension(c, target, 0L, true, "Lifetime", onDone);
            } else if (which == 5) {
                applyUserSuspension(c, target, 0L, false, "Unsuspended", onDone);
            }
        });
    }

    public static void applyUserSuspension(final Context c, final Models.User target,
                                           final long durationMs, final boolean lifetime,
                                           final String label, final Runnable onDone) {
        if (target == null || target.uid == null || target.uid.isEmpty()) return;
        final boolean suspending = lifetime || durationMs > 0L;
        final long until = lifetime ? 9_999_999_999_999L : (suspending ? (System.currentTimeMillis() + durationMs) : 0L);
        final String name = (target.displayName != null && !target.displayName.isEmpty()) ? target.displayName : "User";
        Object policy = lifetime ? "lifetime" : (int) (durationMs / 86_400_000L);
        Fb.db().moderate("set_suspension", "users", target.uid, policy)
                .addOnSuccessListener(x -> {
                    target.suspendedUntil = until;
                    target.suspendedLifetime = lifetime;
                    if (onDone != null) onDone.run();
                    Ui.toast(c, suspending ? "Suspended " + name + " (" + label + ")" : "Unsuspended " + name);
                })
                .addOnFailureListener(e -> Ui.toast(c, "Suspension could not be updated"));
    }

    public static void setGlobalModerator(Context c, String uid, String displayName, boolean makeMod) {
        if (!isAdmin()) { Ui.toast(c, "Admins only"); return; }
        Fb.db().moderate("set_moderator", "users", uid, makeMod)
                .addOnSuccessListener(x -> Ui.toast(c, (makeMod ? "Moderator appointed: " : "Moderator removed: ") + displayName))
                .addOnFailureListener(e -> Ui.toast(c, "Moderator role could not be updated"));
    }

    public static boolean isOwnerEmail(String email) {
        return email != null && email.equalsIgnoreCase(OWNER_EMAIL);
    }

    public static void setTickLevel(Context c, String uid, String displayName, String level) {
        if (!isAdmin()) { Ui.toast(c, "Admins only"); return; }
        Fb.db().moderate("set_tick", "users", uid, level)
                .addOnSuccessListener(x -> {
                    TickCache.put(uid, level);
                    Ui.toast(c, tickLabel(level) + " → " + displayName);
                })
                .addOnFailureListener(e -> Ui.toast(c, "Tick could not be updated"));
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
                        for (com.friendbook.social.d1.documents.DocumentSnapshot d : snap)
                            b.set(d.getReference(), Fb.map("authorVerified", value),
                                    com.friendbook.social.d1.documents.SetOptions.merge());
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
                        for (com.friendbook.social.d1.documents.DocumentSnapshot d : snap)
                            b.set(d.getReference(), Fb.map("authorTickLevel", level,
                                            "authorVerified", Icons.tickRank(level) > 0),
                                    com.friendbook.social.d1.documents.SetOptions.merge());
                        b.commit();
                    });
        }
    }

    /** Referral invite link for a user (official Google Play Store URL). */
    public static String inviteLink(String uid) {
        return AdsConfig.PLAY_STORE_URL + "&referrer=ref_" + uid;
    }

    /** Extract the referral code from an invite link or raw code. Returns null when empty. */
    public static String extractRefCode(String input) {
        if (input == null) return null;
        input = input.trim();
        if (input.isEmpty()) return null;
        int refIdx = input.indexOf("ref_");
        if (refIdx >= 0) {
            String code = input.substring(refIdx + 4);
            int amp = code.indexOf('&');
            if (amp > 0) code = code.substring(0, amp);
            return code.trim().isEmpty() ? null : code.trim();
        }
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
