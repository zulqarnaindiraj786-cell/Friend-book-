package com.example.frienbook.main;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.example.frienbook.auth.LoginActivity;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Presence;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.post.SavedPostsActivity;
import com.example.frienbook.profile.ProfileActivity;
import com.example.frienbook.settings.SettingsActivity;
import com.google.firebase.firestore.ListenerRegistration;

/** Menu tab: profile shortcut, shortcuts, preferences, help, log out. */
public class MenuFragment extends Fragment {

    private ImageView avatar;
    private TextView name, bio;
    private ListenerRegistration meReg;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));

        TextView title = Ui.tvBold(a, "Menu", 22, Ui.txt(a));
        Ui.pad(title, 14, 12, 14, 6);
        root.addView(title);

        ScrollView sv = new ScrollView(a);
        LinearLayout body = Ui.col(a);
        Ui.pad(body, 10, 0, 10, 16);

        // profile card
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.round(a, Ui.card(a), 12));
        Ui.pad(card, 12, 12, 12, 12);
        LinearLayout row = Ui.row(a);
        avatar = new ImageView(a);
        int s = Ui.dp(a, 52);
        row.addView(avatar, new LinearLayout.LayoutParams(s, s));
        LinearLayout ncol = Ui.col(a);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
        np.leftMargin = Ui.dp(a, 12);
        name = Ui.tvBold(a, "", 17, Ui.txt(a));
        bio = Ui.tv(a, "", 13, Ui.sub(a));
        ncol.addView(name);
        ncol.addView(bio);
        row.addView(ncol, np);
        card.addView(row);
        card.addView(divSpace(a));
        TextView viewProf = Ui.tvBold(a, "View your profile", 14, Ui.BLUE);
        viewProf.setGravity(Gravity.CENTER);
        viewProf.setPadding(0, Ui.dp(a, 8), 0, 0);
        card.addView(viewProf);
        View.OnClickListener goProfile = v -> {
            Intent i = new Intent(a, ProfileActivity.class);
            i.putExtra("uid", Fb.uid());
            startActivity(i);
        };
        card.setOnClickListener(goProfile);
        viewProf.setOnClickListener(goProfile);
        body.addView(card);
        body.addView(spacer(a, 12));

        // shortcuts
        body.addView(item(a, "💾", "Saved posts", "Everything you bookmarked",
                () -> startActivity(new Intent(a, SavedPostsActivity.class))));
        body.addView(item(a, "📺", "Watch", "All public videos in one feed",
                () -> startActivity(new Intent(a, com.example.frienbook.post.WatchActivity.class))));
        body.addView(item(a, "👨‍👩‍👧‍👦", "Groups", "Communities with their own feeds",
                () -> startActivity(new Intent(a, com.example.frienbook.groups.GroupsActivity.class))));
        body.addView(item(a, "🎂", "Birthdays", "See whose birthday is coming",
                () -> startActivity(new Intent(a, com.example.frienbook.friends.BirthdaysActivity.class))));
        body.addView(item(a, "📋", "Activity log", "Your posts & comments in one place",
                () -> startActivity(new Intent(a, com.example.frienbook.settings.ActivityLogActivity.class))));
        body.addView(item(a, "👥", "Friends", "Requests, suggestions, all friends",
                () -> ((MainActivity) a).select(MainActivity.TAB_FRIENDS)));
        body.addView(item(a, "🎞️", "Reels", "Watch short videos",
                () -> ((MainActivity) a).select(MainActivity.TAB_REELS)));
        body.addView(item(a, "🔔", "Notifications", "Your latest activity",
                () -> ((MainActivity) a).select(MainActivity.TAB_NOTIFS)));

        // ===== ADMIN ONLY (zulqarnaindiraj786@gmail.com / flagged admins) =====
        if (com.example.frienbook.core.Admin.isAdmin()) {
            body.addView(spacer(a, 10));
            LinearLayout adminCard = Ui.col(a);
            adminCard.setBackground(Ui.roundStroke(a, Ui.card(a), 12, Ui.ORANGE, 2));
            TextView crown = Ui.tvBold(a, "🛡 ADMIN ZONE", 12, Ui.ORANGE);
            crown.setGravity(Gravity.CENTER);
            Ui.pad(crown, 0, 8, 0, 0);
            adminCard.addView(crown);
            TextView open = Ui.tvBold(a, "⚡ Open Admin Panel — stats · users · content · reports · broadcast", 14, Ui.txt(a));
            Ui.pad(open, 14, 8, 14, 12);
            adminCard.addView(open);
            adminCard.setOnClickListener(v ->
                    startActivity(new Intent(a, com.example.frienbook.admin.AdminPanelActivity.class)));
            LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(-1, -2);
            acp.setMargins(0, Ui.dp(a, 8), 0, 0);
            adminCard.setLayoutParams(acp);
            body.addView(adminCard);
        }
        body.addView(spacer(a, 10));

        // quick preferences
        body.addView(switchItem(a, "🌙", "Dark mode", Prefs.dark(a), (btn, on) -> {
            Prefs.setDark(a, on);
            a.recreate();
        }));
        body.addView(switchItem(a, "📶", "Data Saver", Prefs.dataSaver(a), (btn, on) -> {
            Prefs.setDataSaver(a, on);
            Ui.toast(a, on ? "Data Saver on — lighter images, tap-to-load video" : "Data Saver off");
        }));
        body.addView(switchItem(a, "🤫", "Quiet mode", Prefs.quiet(a), (btn, on) -> {
            Prefs.setQuiet(a, on);
            Ui.toast(a, on ? "Quiet mode on — badges hidden" : "Quiet mode off");
        }));
        body.addView(spacer(a, 10));

        // settings & more
        body.addView(item(a, "⚙️", "Settings & privacy", "Account, privacy, notifications, security",
                () -> startActivity(new Intent(a, SettingsActivity.class))));
        body.addView(item(a, "❓", "Help & feedback", "Shake your phone anytime to report a problem",
                () -> Ui.info(a, "Help & feedback",
                        "• Shake your phone to report a problem\n• Pull down the feed to refresh\n• Long-press 👍 for reactions\n• Long-press a chat for options")));
        body.addView(item(a, "ℹ️", "About FriendBook", "Version and info",
                () -> Ui.info(a, "FriendBook Lite", "Version 2.0\nA fast, light Facebook-Lite style community app powered by Firebase.")));
        body.addView(spacer(a, 12));

        TextView logout = Ui.btn(a, "🚪 Log out");
        logout.setBackground(Ui.round(a, Ui.RED, 8));
        logout.setOnClickListener(v -> {
            Ui.confirm(a, "Log out?", "You'll need your email and password to log back in.", "Log out", () -> {
                Presence.goOffline();
                Fb.auth().signOut();
                Fb.me = null;
                Prefs.setUnlockedSession(a, false);
                Intent i = new Intent(a, LoginActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                a.finish();
            });
        });
        body.addView(logout);

        TextView ver = Ui.tv(a, "FriendBook Lite 2.0", 11, Ui.sub(a));
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, Ui.dp(a, 14), 0, 0);
        body.addView(ver);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private View spacer(android.app.Activity a, int h) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(a, h)));
        return v;
    }

    private View divSpace(android.app.Activity a) {
        LinearLayout w = Ui.col(a);
        w.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 4));
        w.addView(Ui.divider(a));
        return w;
    }

    private View item(android.app.Activity a, String emoji, String title, String sub, Runnable action) {
        LinearLayout row = Ui.row(a);
        row.setBackgroundColor(Ui.card(a));
        Ui.pad(row, 12, 12, 12, 12);
        TextView e = Ui.tv(a, emoji, 20, Ui.txt(a));
        e.setGravity(Gravity.CENTER);
        e.setBackground(Ui.circle(Ui.inputBg(a)));
        int s = Ui.dp(a, 38);
        row.addView(e, new LinearLayout.LayoutParams(s, s));
        LinearLayout mid = Ui.col(a);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(a, 12);
        TextView t = Ui.tvBold(a, title, 15, Ui.txt(a));
        TextView st = Ui.tv(a, sub, 12, Ui.sub(a));
        mid.addView(t);
        mid.addView(st);
        row.addView(mid, mp);
        TextView arrow = Ui.tv(a, "›", 20, Ui.sub(a));
        row.addView(arrow);
        row.setOnClickListener(v -> action.run());
        LinearLayout wrap = Ui.col(a);
        wrap.addView(row);
        wrap.addView(Ui.divider(a));
        return wrap;
    }

    private View switchItem(android.app.Activity a, String emoji, String title, boolean on,
                            CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = Ui.row(a);
        row.setBackgroundColor(Ui.card(a));
        Ui.pad(row, 12, 8, 12, 8);
        TextView e = Ui.tv(a, emoji, 20, Ui.txt(a));
        e.setGravity(Gravity.CENTER);
        e.setBackground(Ui.circle(Ui.inputBg(a)));
        int s = Ui.dp(a, 38);
        row.addView(e, new LinearLayout.LayoutParams(s, s));
        TextView t = Ui.tvBold(a, title, 15, Ui.txt(a));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = Ui.dp(a, 12);
        row.addView(t, tp);
        Switch sw = new Switch(a);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(null);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        LinearLayout wrap = Ui.col(a);
        wrap.addView(row);
        wrap.addView(Ui.divider(a));
        return wrap;
    }

    @Override
    public void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) return;
        meReg = Fb.db().collection("users").document(me)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists() || getActivity() == null) return;
                    Models.User u = Models.User.fromDoc(d);
                    name.setText(u.displayName);
                    bio.setText(u.bio == null || u.bio.isEmpty() ? "Tap to view your profile" : u.bio);
                    Ui.avatar(getActivity(), avatar, u.photoUrl);
                });
    }

    @Override
    public void onStop() {
        super.onStop();
        if (meReg != null) meReg.remove();
    }
}
