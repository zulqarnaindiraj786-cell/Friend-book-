package com.example.frienbook.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.example.frienbook.App;
import com.example.frienbook.LockActivity;
import com.example.frienbook.auth.LoginActivity;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Icons;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Presence;
import com.example.frienbook.core.Ui;
import com.google.firebase.firestore.FieldValue;

/** Settings & privacy: account, preferences, privacy, notifications, security, about. */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Settings & privacy"));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 8, 12, 20);

        // ============ ACCOUNT ============
        body.addView(header("Account"));
        body.addView(card());
        body.addView(rowI(Icons.KEY, "Change password", "Update your login password", this::changePassword));
        body.addView(div());
        body.addView(rowI(Icons.MAIL, "Email", Fb.auth().getCurrentUser() != null
                ? Fb.auth().getCurrentUser().getEmail() : "", null));
        body.addView(div());
        body.addView(rowI(Icons.TRASH, "Delete account", "Permanently remove your account and data", this::deleteAccount));
        body.addView(cardEnd());

        // ============ PREFERENCES ============
        body.addView(header("Preferences"));
        body.addView(card());
        body.addView(switchRowI(Icons.MOON, "Dark mode", Prefs.dark(this), (btn, on) -> {
            Prefs.setDark(this, on);
            recreate();
        }));
        body.addView(div());
        body.addView(switchRowI(Icons.MOON, "AMOLED black theme", Prefs.amoled(this), (btn, on) -> {
            Prefs.setAmoled(this, on);
            Ui.toast(this, on ? "Pure black on 🔳 (with dark mode)" : "AMOLED off");
            recreate();
        }));
        body.addView(div());
        body.addView(rowI(Icons.FONT, "Font size", fontLabel(), this::fontPicker));
        body.addView(div());
        body.addView(switchRowI(Icons.DATA_SAVER, "Data Saver", Prefs.dataSaver(this),
                (btn, on) -> Prefs.setDataSaver(this, on)));
        body.addView(div());
        body.addView(switchRowI(Icons.VIDEO, "Autoplay videos", Prefs.autoplay(this),
                (btn, on) -> Prefs.setAutoplay(this, on)));
        body.addView(div());
        body.addView(switchRowI(Icons.QUIET, "Quiet mode", Prefs.quiet(this),
                (btn, on) -> Prefs.setQuiet(this, on)));
        body.addView(cardEnd());

        // ============ PRIVACY ============
        body.addView(header("Privacy"));
        body.addView(card());
        body.addView(rowI(Icons.GLOBE, "Default audience", Prefs.defaultPrivacy(this), this::audiencePicker));
        body.addView(div());
        body.addView(rowI(Icons.USER_PLUS, "Who can send friend requests", Prefs.friendRequestPrivacy(this), this::requestPicker));
        body.addView(div());
        body.addView(switchRowI(Icons.ACTIVE, "Active status", Prefs.activeStatus(this), (btn, on) -> {
            Prefs.setActiveStatus(this, on);
            if (on) Presence.init(); else Presence.goOffline();
        }));
        body.addView(div());
        body.addView(switchRowI(Icons.READ_RECEIPTS, "Read receipts", Prefs.readReceipts(this),
                (btn, on) -> Prefs.setReadReceipts(this, on)));
        body.addView(div());
        body.addView(rowI(Icons.BAN, "Blocked users", "Manage your block list",
                () -> startActivity(new Intent(this, BlockedUsersActivity.class))));
        body.addView(div());
        body.addView(switchRowI(Icons.LOCK, "Profile locking",
                Fb.me != null && Fb.me.profileLocked,
                (btn, on) -> {
                    btn.setChecked(on);
                    Fb.db().collection("users").document(Fb.uid())
                            .set(Fb.map("profileLocked", on),
                                    com.google.firebase.firestore.SetOptions.merge())
                            .addOnSuccessListener(x -> {
                                if (Fb.me != null) Fb.me.profileLocked = on;
                                Ui.toast(SettingsActivity.this,
                                        on ? "🔒 Profile locked — sirf friends dekh sakte hain"
                                                : "Profile unlocked");
                            });
                }));
        body.addView(div());
        body.addView(rowI(Icons.SHIELD, "Privacy checkup", "3-step review — audience, blocks, profile lock",
                this::privacyCheckup));
        body.addView(div());
        body.addView(rowI(Icons.STAR, "Feed preferences", "Favorites — unki posts feed ke top pe",
                () -> startActivity(new Intent(this, FeedPreferencesActivity.class))));
        body.addView(cardEnd());

        // ============ YOUR ACTIVITY ============
        body.addView(header("Your activity"));
        body.addView(card());
        body.addView(rowI(Icons.LIST, "Activity log", "Your posts & comments timeline",
                () -> startActivity(new Intent(this, ActivityLogActivity.class))));
        body.addView(div());
        body.addView(rowI(Icons.EYE_OFF, "Posts you've hidden", "Restore hidden feed posts", () -> {
            int n = Prefs.hiddenPosts(this).size();
            if (n == 0) { Ui.toast(this, "No hidden posts"); return; }
            Ui.confirm(this, "Unhide " + n + " post(s)?",
                    "Hidden posts will appear in your feed again.", "Unhide all", () -> {
                        Prefs.unhideAllPosts(this);
                        Ui.toast(this, "All posts restored ✓");
                    });
        }));
        body.addView(div());
        body.addView(rowI(Icons.SNOOZE, "Snoozed people", "Who you're hiding for 30 days", this::snoozedDialog));
        body.addView(div());
        body.addView(rowI(Icons.TRASH, "Clear recent searches", "Wipe your search history", () -> {
            int n = com.example.frienbook.core.Prefs.searchHistory(this).size();
            if (n == 0) { Ui.toast(this, "Search history already clean ✓"); return; }
            Ui.confirm(this, "Clear " + n + " recent search(es)?", "", "Clear", () -> {
                com.example.frienbook.core.Prefs.clearSearch(this);
                Ui.toast(this, "Search history cleared 🧹");
            });
        }));
        body.addView(div());
        body.addView(rowI(Icons.IMAGE, "Clear image cache", "Free up space (photos re-download)", () -> {
            new Thread(() -> {
                try {
                    com.bumptech.glide.Glide.get(this).clearDiskCache();
                } catch (Throwable ignored) { }
                runOnUiThread(() -> Ui.toast(this, "Image cache cleared 🧹"));
            }).start();
        }));
        body.addView(div());
        body.addView(rowI(Icons.EXPORT, "Export your data", "Your profile data as text (share/save)", this::exportData));
        body.addView(div());
        body.addView(rowI(Icons.CHART, "Monetization", "In-stream ads, Stars, fan subscriptions",
                () -> startActivity(new Intent(this,
                        com.example.frienbook.bluetick.MonetizationActivity.class))));
        body.addView(div());
        body.addView(rowI(Icons.BANKNOTE, "✓ Tick levels", "Blue / Gold / Diamond — invites se FREE ya paid",
                () -> startActivity(new Intent(this,
                        com.example.frienbook.bluetick.BlueTickActivity.class))));
        body.addView(cardEnd());

        // ============ ✓ VERIFIED TOOLS (tick holders only) ============
        if (Fb.me != null && Icons.tickRank(Fb.me.tickLevel) > 0) {
            body.addView(header("✓ Verified tools"));
            body.addView(card());
            body.addView(rowI(Icons.VERIFIED, "Priority support",
                    "Verified accounts jump the queue", this::verifiedSupport));
            body.addView(div());
            body.addView(rowI(Icons.CHART, "Profile insights",
                    "Your posts, likes received, followers & stars", this::verifiedInsights));
            body.addView(cardEnd());
        }

        // ============ ADMIN (owner only) ============
        if (com.example.frienbook.core.Admin.isAdmin()) {
            body.addView(header("Admin"));
            body.addView(card());
            body.addView(rowI(Icons.SHIELD, "Admin Panel", "Dashboard, users, content, reports, broadcast",
                    () -> startActivity(new Intent(this,
                            com.example.frienbook.admin.AdminPanelActivity.class))));
            body.addView(div());
            body.addView(rowI(Icons.CHART, "AdMob Settings", "Banner ad unit ID, on/off — live for everyone",
                    () -> startActivity(new Intent(this,
                            com.example.frienbook.admin.AdMobSettingsActivity.class))));
            body.addView(cardEnd());
        }

        // ============ SECURITY ============
        body.addView(header("Security"));
        body.addView(card());
        body.addView(rowI(Icons.LOCK, "App lock", Prefs.pin(this).isEmpty() ? "Off — set a PIN to lock friendbook"
                : "On — PIN required to open", this::pinDialog));
        body.addView(div());
        body.addView(rowI(Icons.SHAKE, "Shake to report", "Enabled — shake phone to report a problem",
                () -> Ui.info(this, "Shake to report",
                        "Any time something looks wrong, shake your phone and a feedback form will open.")));
        body.addView(div());
        body.addView(rowI(Icons.ACTIVE, "Where you're logged in", "Devices & sessions — log out anywhere",
                () -> startActivity(new Intent(this, SessionsActivity.class))));
        body.addView(cardEnd());

        // ============ NOTIFICATIONS ============
        body.addView(header("Notifications"));
        body.addView(card());
        body.addView(switchRowI(Icons.LIKE, "Reactions on my posts", Prefs.notifLikes(this),
                (btn, on) -> Prefs.setNotifLikes(this, on)));
        body.addView(div());
        body.addView(switchRowI(Icons.MESSAGES, "Comments & replies", Prefs.notifComments(this),
                (btn, on) -> Prefs.setNotifComments(this, on)));
        body.addView(div());
        body.addView(switchRowI(Icons.USER_PLUS, "Friend requests", Prefs.notifRequests(this),
                (btn, on) -> Prefs.setNotifRequests(this, on)));
        body.addView(div());
        body.addView(switchRowI(Icons.MAIL, "Messages", Prefs.notifMessages(this),
                (btn, on) -> Prefs.setNotifMessages(this, on)));
        body.addView(cardEnd());

        // ============ ABOUT ============
        body.addView(header("About"));
        body.addView(card());
        body.addView(rowI(Icons.TERMS, "Terms & policies", "Community standards", () -> Ui.info(this,
                "Terms & policies",
                "1. Be kind and respectful.\n2. No spam, hate speech or harassment.\n3. Respect others' privacy.\n4. Content you post is yours; you grant friendbook a license to display it.\n5. Violating accounts may be removed.")));
        body.addView(div());
        body.addView(rowI(Icons.HELP, "Help & support", "FAQs · contact us", this::helpDialog));
        body.addView(div());
        body.addView(rowI(Icons.INFO, "About", "friendbook lite 525.0.0.102", () -> Ui.info(this, "About",
                "friendbook lite 525.0.0.102\n\nA fast, light social app — Facebook-Lite style.\n" +
                "Feed • Stories • Reels • Watch • Groups • Events • Pages • Marketplace\n" +
                "Messenger (voice + stickers) • Pokes • Leaderboard • Daily bonus\n" +
                "Media on Cloudinary • Realtime on Firebase.\n\nPackage: com.example.frienbook")));
        body.addView(cardEnd());

        // ============ LOG OUT ============
        TextView logout = Ui.btn(this, "🚪 Log out");
        logout.setBackground(Ui.round(this, Ui.RED, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        lp.topMargin = Ui.dp(this, 18);
        body.addView(logout, lp);
        logout.setOnClickListener(v -> Ui.confirm(this, "Log out?", "", "Log out", () -> {
            Presence.goOffline();
            Fb.auth().signOut();
            Fb.me = null;
            Prefs.setUnlockedSession(this, false);
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finishAffinity();
        }));

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    // ---------- rows ----------
    /** 🛡 FB-style guided privacy checkup — 3 quick steps. */
    /** ❓ Help & support — offline FAQs + email support (FB Lite help center style). */
    /** ✓ Verified perk: flagged support email to the owner. */
    private void verifiedSupport() {
        Intent i = new Intent(Intent.ACTION_SENDTO,
                android.net.Uri.parse("mailto:zulqarnaindiraj786@gmail.com"));
        i.putExtra(Intent.EXTRA_SUBJECT, "✓ Verified support request");
        i.putExtra(Intent.EXTRA_TEXT,
                "Verified account: " + (Fb.me != null ? Fb.me.displayName : "") + "\n\n");
        try { startActivity(i); } catch (Exception e) { Ui.toast(this, "No email app installed"); }
    }

    /** ✓ Verified perk: quick stats about your own account (real Firestore data). */
    private void verifiedInsights() {
        Ui.toast(this, "Crunching your numbers…");
        Fb.db().collection("posts").whereEqualTo("authorId", Fb.uid()).limit(300).get()
                .addOnSuccessListener(snap -> {
                    int posts = snap.size();
                    int likes = 0;
                    for (com.google.firebase.firestore.DocumentSnapshot d : snap.getDocuments()) {
                        Long l = d.getLong("likes");
                        if (l != null) likes += l;
                    }
                    int friends = Fb.me != null ? Fb.me.friendsCount : 0;
                    int followers = Fb.me != null && Fb.me.followers != null
                            ? Fb.me.followers.size() : 0;
                    int stars = Fb.me != null ? Fb.me.stars : 0;
                    Ui.info(this, "✓ Profile insights",
                            "📝 Posts: " + posts + (posts >= 300 ? "+" : "") + "\n"
                            + "❤️ Likes received: " + likes + "\n"
                            + "👥 Friends: " + friends + "\n"
                            + "👀 Followers: " + followers + "\n"
                            + "⭐ Stars balance: " + stars + "\n"
                            + "🔥 Daily streak: " + (Fb.me != null ? Fb.me.dailyStreak : 0)
                            + " day(s)\n\n"
                            + "Tip: keep your streak alive for up to 20⭐/day (verified ×2).");
                })
                .addOnFailureListener(e -> Ui.toast(this, e.getMessage()));
    }

    private void helpDialog() {        String[] opts = {
                "🔐 Login / account problems",
                "📮 Post not showing on feed?",
                "⭐ Stars, ticks & payments",
                "📤 How uploads work (Cloudinary)",
                "📧 Email support"};
        Ui.choose(this, "Help & support", opts, w -> {
            if (w == 0) Ui.info(this, "🔐 Login & account",
                    "• Sign in with the SAME email you registered with.\n"
                            + "• Forgot password? Use 'Forgot password' — a 6-digit code goes to your email.\n"
                            + "• Banned or suspended accounts get signed out automatically.\n"
                            + "• Sessions: Settings → Where you're logged in.");
            else if (w == 1) Ui.info(this, "📮 Posts & feed",
                    "• Pull down on home to refresh.\n"
                            + "• Check your post's audience: ⋮ → Who can see this?\n"
                            + "• 'Only me' posts are private, 'Friends' need a friendship.\n"
                            + "• If a post shows ⚠, shake your phone to send a report.");
            else if (w == 2) Ui.info(this, "⭐ Stars & ticks",
                    "• Earn ⭐ from live gifts, referrals and the daily bonus.\n"
                            + "• 5 invites → free ✓ blue tick. 20 → gold. 50 → diamond.\n"
                            + "• Paid options upload a payment screenshot — admin approves.\n"
                            + "• Track everything in Settings → Monetization.");
            else if (w == 3) Ui.info(this, "📤 Media uploads",
                    "• Photos, videos, reels, stories and voice notes upload to Cloudinary.\n"
                            + "• If Cloudinary is unreachable, uploads retry via Firebase automatically.\n"
                            + "• Large videos may take a while on slow networks — stay on the screen.");
            else {
                Intent i = new Intent(Intent.ACTION_SENDTO);
                i.setData(android.net.Uri.parse("mailto:zulqarnaindiraj786@gmail.com"));
                i.putExtra(Intent.EXTRA_SUBJECT, "friendbook lite — support request");
                try { startActivity(i); } catch (Exception e) { Ui.toast(this, "No email app installed"); }
            }
        });
    }

    private void privacyCheckup() {

        boolean locked = Fb.me != null && Fb.me.profileLocked;
        Ui.choose(this, "Privacy checkup ✓\n\n"
                        + "1. Default audience: " + Prefs.defaultPrivacy(this) + "\n"
                        + "2. Blocked people: " + (Fb.me != null ? Fb.me.blocked.size() : 0) + "\n"
                        + "3. Profile lock: " + (locked ? "ON 🔒" : "OFF"),
                new String[]{"🌐 Change audience", "🚫 Manage blocked", locked ? "🔓 Unlock profile" : "🔒 Lock profile"},
                w -> {
                    if (w == 0) audiencePicker();
                    else if (w == 1) startActivity(new Intent(this, BlockedUsersActivity.class));
                    else {
                        boolean on = !locked;
                        Fb.db().collection("users").document(Fb.uid())
                                .set(Fb.map("profileLocked", on),
                                        com.google.firebase.firestore.SetOptions.merge());
                        if (Fb.me != null) Fb.me.profileLocked = on;
                        Ui.toast(this, on ? "🔒 Profile locked" : "Profile unlocked");
                    }
                });
    }

    private TextView header(String t) {
        TextView h = Ui.tvBold(this, t.toUpperCase(), 12, Ui.sub(this));
        h.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        return h;
    }

    /** Settings row with Facebook-Lite outline icon (2px stroke, gray 65676B). */
    private View rowI(int iconRes, String title, String sub, Runnable action) {
        LinearLayout rowV = Ui.row(this);
        rowV.setBackgroundColor(Ui.card(this));
        Ui.pad(rowV, 14, 12, 14, 12);
        rowV.addView(Icons.icon(this, iconRes, 21, Icons.GRAY));
        LinearLayout mid = Ui.col(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(this, 14);
        TextView t = Ui.tvBold(this, title, 15, Ui.txt(this));
        mid.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.tv(this, sub, 12, Ui.sub(this));
            mid.addView(s);
        }
        rowV.addView(mid, mp);
        if (action != null) {
            TextView arrow = Ui.tv(this, "›", 18, Ui.sub(this));
            rowV.addView(arrow);
            rowV.setOnClickListener(v -> action.run());
        }
        LinearLayout wrap = Ui.col(this);
        wrap.addView(rowV);
        return wrap;
    }

    private View switchRowI(int iconRes, String title, boolean on,
                            CompoundButton.OnCheckedChangeListener l) {
        LinearLayout rowV = Ui.row(this);
        rowV.setBackgroundColor(Ui.card(this));
        Ui.pad(rowV, 14, 6, 14, 6);
        rowV.addView(Icons.icon(this, iconRes, 21, Icons.GRAY));
        TextView t = Ui.tvBold(this, title, 15, Ui.txt(this));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = Ui.dp(this, 14);
        rowV.addView(t, tp);
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(l);
        rowV.addView(sw);
        LinearLayout wrap = Ui.col(this);
        wrap.addView(rowV);
        return wrap;
    }

    private View card() {
        View v = new View(this);
        return v; // opening marker (no-op, kept for clarity)
    }

    private View cardEnd() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, 6)));
        return v;
    }

    private View div() { return Ui.divider(this); }



    // ---------- actions ----------
    private String fontLabel() {
        switch (Prefs.fontSize(this)) {
            case 0: return "Small";
            case 2: return "Large";
            default: return "Default";
        }
    }

    private void fontPicker() {
        Ui.choose(this, "Font size", new String[]{"Small", "Default", "Large"}, w -> {
            Prefs.setFontSize(this, w);
            recreate();
        });
    }

    private void audiencePicker() {
        Ui.choose(this, "Default audience for new posts", new String[]{"🌐 Public", "👥 Friends", "🔒 Only me"}, w -> {
            Prefs.setDefaultPrivacy(this, w == 1 ? "Friends" : w == 2 ? "Only me" : "Public");
            recreate();
        });
    }

    private void requestPicker() {
        Ui.choose(this, "Who can send you friend requests?", new String[]{"Everyone", "Friends of friends"}, w -> {
            Prefs.setFriendRequestPrivacy(this, w == 1 ? "Friends of friends" : "Everyone");
            recreate();
        });
    }

    private void snoozedDialog() {
        StringBuilder sb = new StringBuilder();
        java.util.List<String> uids = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (String e : getSharedPreferences("fb_prefs", MODE_PRIVATE)
                .getStringSet("snoozed", new java.util.HashSet<>())) {
            int i = e.indexOf(':');
            if (i <= 0) continue;
            String uid = e.substring(0, i);
            long until;
            try { until = Long.parseLong(e.substring(i + 1)); } catch (Exception ex) { continue; }
            if (until <= now) continue;
            uids.add(uid);
            long days = (until - now) / 86400_000L + 1;
            sb.append(uid.substring(0, Math.min(8, uid.length()))).append("…  ·  ")
                    .append(days).append(" day(s) left\n");
        }
        if (uids.isEmpty()) { Ui.toast(this, "Nobody snoozed 😊"); return; }
        Ui.confirm(this, "Snoozed people", sb.toString().trim(), "Unsnooze everyone", () -> {
            for (String uid : uids) Prefs.unsnooze(this, uid);
            Ui.toast(this, "All unsnoozed ✓");
        });
    }

    private void exportData() {
        Ui.toast(this, "Collecting your data…");
        String me = Fb.uid();
        if (me == null) return;
        Fb.db().collection("users").document(me).get().addOnSuccessListener(d -> {
            StringBuilder sb = new StringBuilder();
            sb.append("friendbook data export — ").append(java.text.DateFormat.getDateTimeInstance()
                    .format(new java.util.Date())).append("\n\n");
            java.util.Map<String, Object> m = d.getData();
            if (m != null) for (java.util.Map.Entry<String, Object> en : m.entrySet()) {
                sb.append(en.getKey()).append(": ").append(String.valueOf(en.getValue())).append("\n");
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, sb.toString());
            startActivity(Intent.createChooser(share, "Export your data"));
        }).addOnFailureListener(e -> Ui.toast(this, "Export failed"));
    }

    private void changePassword() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText p1 = Ui.input(this, "New password (6+ characters)");
        p1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(p1, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        EditText p2 = Ui.input(this, "Confirm new password");
        p2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        lp.topMargin = Ui.dp(this, 10);
        box.addView(p2, lp);
        new AlertDialog.Builder(this)
                .setTitle("Change password")
                .setView(box)
                .setPositiveButton("Update", (d, w) -> {
                    String a = p1.getText().toString();
                    if (a.length() < 6) { Ui.toast(this, "Password must be 6+ characters"); return; }
                    if (!a.equals(p2.getText().toString())) { Ui.toast(this, "Passwords don't match"); return; }
                    if (Fb.auth().getCurrentUser() == null) return;
                    Fb.auth().getCurrentUser().updatePassword(a)
                            .addOnSuccessListener(x -> Ui.toast(this, "Password updated ✓"))
                            .addOnFailureListener(e -> Ui.info(this, "Couldn't update",
                                    "For security, log out and log back in, then try again."));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pinDialog() {
        boolean has = !Prefs.pin(this).isEmpty();
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText pin = Ui.input(this, has ? "Enter new PIN (or leave empty to disable)" : "Choose a PIN (4+ digits)");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(pin, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        new AlertDialog.Builder(this)
                .setTitle("App lock")
                .setMessage(has ? "App lock is ON. Enter a new PIN to change it, or leave empty to turn it off."
                        : "Lock friendbook behind a PIN every time it opens.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String p = pin.getText().toString().trim();
                    if (p.isEmpty()) {
                        Prefs.setPin(this, "");
                        Prefs.setUnlockedSession(this, true);
                        Ui.toast(this, "App lock disabled");
                    } else if (p.length() < 4) {
                        Ui.toast(this, "PIN must be at least 4 digits");
                    } else {
                        Prefs.setPin(this, p);
                        Prefs.setUnlockedSession(this, true);
                        Ui.toast(this, "App lock enabled 🔒");
                    }
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAccount() {
        Ui.confirm(this, "Delete account permanently?",
                "Your profile will be removed. Posts, comments and messages may remain visible to others until cleaned up. This cannot be undone.",
                "Delete forever", () -> {
                    if (Fb.auth().getCurrentUser() == null) return;
                    String uid = Fb.uid();
                    Fb.db().collection("users").document(uid).delete();
                    Fb.auth().getCurrentUser().delete()
                            .addOnSuccessListener(x -> {
                                Fb.me = null;
                                Prefs.setUnlockedSession(this, false);
                                Intent i = new Intent(this, LoginActivity.class);
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(i);
                                finishAffinity();
                            })
                            .addOnFailureListener(e -> Ui.info(this, "Couldn't delete",
                                    "For security, log out, log back in, and try again."));
                });
    }
}
