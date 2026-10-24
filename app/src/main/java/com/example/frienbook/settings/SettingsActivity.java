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
        body.addView(rowI(Icons.FONT, "Font size", fontLabel(), this::fontPicker));
        body.addView(div());
        body.addView(switchRowI(Icons.DATA_SAVER, "Data Saver", Prefs.dataSaver(this),
                (btn, on) -> Prefs.setDataSaver(this, on)));
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

        // ============ ADMIN (owner only) ============
        if (com.example.frienbook.core.Admin.isAdmin()) {
            body.addView(header("Admin"));
            body.addView(card());
            body.addView(rowI(Icons.SHIELD, "Admin Panel", "Dashboard, users, content, reports, broadcast",
                    () -> startActivity(new Intent(this,
                            com.example.frienbook.admin.AdminPanelActivity.class))));
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
        body.addView(rowI(Icons.INFO, "About", "friendbook lite 3.0", () -> Ui.info(this, "About",
                "friendbook lite 3.0\n\nA fast, light social app.\nRealtime feed, stories, reels, messenger and more — powered by Firebase.\n\nPackage: com.example.frienbook")));
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
