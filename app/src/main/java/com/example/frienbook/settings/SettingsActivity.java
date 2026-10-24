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
        body.addView(row("🔑", "Change password", "Update your login password", this::changePassword));
        body.addView(div());
        body.addView(row("📧", "Email", Fb.auth().getCurrentUser() != null
                ? Fb.auth().getCurrentUser().getEmail() : "", null));
        body.addView(div());
        body.addView(row("🗑", "Delete account", "Permanently remove your account and data", this::deleteAccount));
        body.addView(cardEnd());

        // ============ PREFERENCES ============
        body.addView(header("Preferences"));
        body.addView(card());
        body.addView(switchRow("🌙", "Dark mode", Prefs.dark(this), (btn, on) -> {
            Prefs.setDark(this, on);
            recreate();
        }));
        body.addView(div());
        body.addView(row("🔤", "Font size", fontLabel(), this::fontPicker));
        body.addView(div());
        body.addView(switchRow("📶", "Data Saver", Prefs.dataSaver(this),
                (btn, on) -> Prefs.setDataSaver(this, on)));
        body.addView(div());
        body.addView(switchRow("🤫", "Quiet mode", Prefs.quiet(this),
                (btn, on) -> Prefs.setQuiet(this, on)));
        body.addView(cardEnd());

        // ============ PRIVACY ============
        body.addView(header("Privacy"));
        body.addView(card());
        body.addView(row("🌐", "Default audience", Prefs.defaultPrivacy(this), this::audiencePicker));
        body.addView(div());
        body.addView(row("👥", "Who can send friend requests", Prefs.friendRequestPrivacy(this), this::requestPicker));
        body.addView(div());
        body.addView(switchRow("🟢", "Active status", Prefs.activeStatus(this), (btn, on) -> {
            Prefs.setActiveStatus(this, on);
            if (on) Presence.init(); else Presence.goOffline();
        }));
        body.addView(div());
        body.addView(switchRow("✓✓", "Read receipts", Prefs.readReceipts(this),
                (btn, on) -> Prefs.setReadReceipts(this, on)));
        body.addView(div());
        body.addView(row("🚫", "Blocked users", "Manage your block list",
                () -> startActivity(new Intent(this, BlockedUsersActivity.class))));
        body.addView(cardEnd());

        // ============ SECURITY ============
        body.addView(header("Security"));
        body.addView(card());
        body.addView(row("🔒", "App lock", Prefs.pin(this).isEmpty() ? "Off — set a PIN to lock FriendBook"
                : "On — PIN required to open", this::pinDialog));
        body.addView(div());
        body.addView(row("📳", "Shake to report", "Enabled — shake phone to report a problem",
                () -> Ui.info(this, "Shake to report",
                        "Any time something looks wrong, shake your phone and a feedback form will open.")));
        body.addView(cardEnd());

        // ============ NOTIFICATIONS ============
        body.addView(header("Notifications"));
        body.addView(card());
        body.addView(switchRow("👍", "Reactions on my posts", Prefs.notifLikes(this),
                (btn, on) -> Prefs.setNotifLikes(this, on)));
        body.addView(div());
        body.addView(switchRow("💬", "Comments & replies", Prefs.notifComments(this),
                (btn, on) -> Prefs.setNotifComments(this, on)));
        body.addView(div());
        body.addView(switchRow("👥", "Friend requests", Prefs.notifRequests(this),
                (btn, on) -> Prefs.setNotifRequests(this, on)));
        body.addView(div());
        body.addView(switchRow("✉️", "Messages", Prefs.notifMessages(this),
                (btn, on) -> Prefs.setNotifMessages(this, on)));
        body.addView(cardEnd());

        // ============ ABOUT ============
        body.addView(header("About"));
        body.addView(card());
        body.addView(row("📄", "Terms & policies", "Community standards", () -> Ui.info(this,
                "Terms & policies",
                "1. Be kind and respectful.\n2. No spam, hate speech or harassment.\n3. Respect others' privacy.\n4. Content you post is yours; you grant FriendBook a license to display it.\n5. Violating accounts may be removed.")));
        body.addView(div());
        body.addView(row("ℹ️", "About", "FriendBook Lite 2.0", () -> Ui.info(this, "About",
                "FriendBook Lite 2.0\n\nA fast, light social app.\nRealtime feed, stories, reels, messenger and more — powered by Firebase.\n\nPackage: com.example.frienbook")));
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

    private View row(String emoji, String title, String sub, Runnable action) {
        LinearLayout rowV = Ui.row(this);
        rowV.setBackgroundColor(Ui.card(this));
        Ui.pad(rowV, 14, 12, 14, 12);
        TextView e = Ui.tv(this, emoji, 18, Ui.txt(this));
        rowV.addView(e);
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
        wrap.setBackground(Ui.round(this, Ui.card(this), 0));
        wrap.addView(rowV);
        return wrap;
    }

    private View switchRow(String emoji, String title, boolean on,
                           CompoundButton.OnCheckedChangeListener l) {
        LinearLayout rowV = Ui.row(this);
        rowV.setBackgroundColor(Ui.card(this));
        Ui.pad(rowV, 14, 6, 14, 6);
        TextView e = Ui.tv(this, emoji, 18, Ui.txt(this));
        rowV.addView(e);
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
                        : "Lock FriendBook behind a PIN every time it opens.")
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
