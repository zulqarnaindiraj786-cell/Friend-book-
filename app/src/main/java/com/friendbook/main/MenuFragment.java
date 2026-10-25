package com.friendbook.main;

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

import com.friendbook.auth.LoginActivity;
import com.friendbook.core.Fb;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.post.SavedPostsActivity;
import com.friendbook.profile.ProfileActivity;
import com.friendbook.settings.SettingsActivity;
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

        // shortcuts (Facebook-Lite outline icons, gray chips)
        body.addView(item(a, com.friendbook.core.Icons.VERIFIED, "✓ Tick levels", "Blue · Gold · Diamond — FREE with invites or paid",
                () -> startActivity(new Intent(a, com.friendbook.bluetick.BlueTickActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.BOOKMARK, "Saved posts", "Everything you bookmarked",
                () -> startActivity(new Intent(a, SavedPostsActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.VIDEO, "Watch", "All public videos in one feed",
                () -> startActivity(new Intent(a, com.friendbook.post.WatchActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.MARKETPLACE, "Marketplace", "Buy & sell with people near you",
                () -> startActivity(new Intent(a, com.friendbook.settings.MarketplaceActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.GROUPS, "Groups", "Communities with their own feeds",
                () -> startActivity(new Intent(a, com.friendbook.groups.GroupsActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.CAKE, "Birthdays", "See whose birthday is coming",
                () -> startActivity(new Intent(a, com.friendbook.friends.BirthdaysActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.ACTIVE, "Events", "Create events, RSVP, see what's on",
                () -> startActivity(new Intent(a, com.friendbook.social.EventsActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.FLAG, "Pages", "Your pages & pages to follow",
                () -> startActivity(new Intent(a, com.friendbook.social.PagesActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.SNOOZE, "On this day", "Your memories from past years",
                () -> startActivity(new Intent(a, com.friendbook.post.MemoriesActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.GIFT, "🎁 Daily bonus", "Claim 5 ⭐ every day — keep your streak 🔥",
                () -> claimDailyBonus(a)));
        body.addView(item(a, com.friendbook.core.Icons.USER_PLUS, "🖐 Pokes", "Who poked you — poke them back!",
                () -> startActivity(new Intent(a, com.friendbook.social.PokesActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.CHART, "🏆 Leaderboard", "Top friends by ⭐ stars",
                () -> startActivity(new Intent(a, com.friendbook.social.LeaderboardActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.LIST, "Activity log", "Your posts & comments in one place",
                () -> startActivity(new Intent(a, com.friendbook.settings.ActivityLogActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.FRIENDS, "Friends", "Requests, suggestions, all friends",
                () -> ((MainActivity) a).select(MainActivity.TAB_FRIENDS)));
        body.addView(item(a, com.friendbook.core.Icons.VIDEO, "Reels", "Watch short videos",
                () -> ((MainActivity) a).select(MainActivity.TAB_REELS)));
        body.addView(item(a, com.friendbook.core.Icons.NOTIFICATIONS, "Notifications", "Your latest activity",
                () -> ((MainActivity) a).select(MainActivity.TAB_NOTIFS)));

        // ===== ADMIN ONLY (zulqarnaindiraj786@gmail.com / flagged admins) =====
        if (com.friendbook.core.Admin.isAdmin()) {
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
                    startActivity(new Intent(a, com.friendbook.admin.AdminPanelActivity.class)));
            LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(-1, -2);
            acp.setMargins(0, Ui.dp(a, 8), 0, 0);
            adminCard.setLayoutParams(acp);
            body.addView(adminCard);
        }
        body.addView(spacer(a, 10));

        // quick preferences
        body.addView(switchItem(a, com.friendbook.core.Icons.MOON, "Dark mode", Prefs.dark(a), (btn, on) -> {
            Prefs.setDark(a, on);
            a.recreate();
        }));
        body.addView(switchItem(a, com.friendbook.core.Icons.DATA_SAVER, "Data Saver", Prefs.dataSaver(a), (btn, on) -> {
            Prefs.setDataSaver(a, on);
            Ui.toast(a, on ? "Data Saver on — lighter images, tap-to-load video" : "Data Saver off");
        }));
        body.addView(switchItem(a, com.friendbook.core.Icons.QUIET, "Quiet mode", Prefs.quiet(a), (btn, on) -> {
            Prefs.setQuiet(a, on);
            Ui.toast(a, on ? "Quiet mode on — badges hidden" : "Quiet mode off");
        }));
        body.addView(spacer(a, 10));

        // settings & more
        body.addView(item(a, com.friendbook.core.Icons.SETTINGS, "Settings & privacy", "Account, privacy, notifications, security",
                () -> startActivity(new Intent(a, SettingsActivity.class))));
        body.addView(item(a, com.friendbook.core.Icons.HELP, "Help & feedback", "Shake your phone anytime to report a problem",
                () -> Ui.info(a, "Help & feedback",
                        "• Shake your phone to report a problem\n• Pull down the feed to refresh\n• Long-press 👍 for reactions\n• Long-press a chat for options")));
        body.addView(item(a, com.friendbook.core.Icons.INFO, "About friendbook", "Version and info",
                () -> Ui.info(a, "friendbook", "Version 525.0.0.102\nA fast, light Facebook-Lite style community app powered by Firebase.")));
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

        TextView ver = Ui.tv(a, "friendbook 525.0.0.102", 11, Ui.sub(a));
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

    /** Menu row with Facebook-Lite outline icon in a gray circle chip.
     *  The verified badge keeps its native look (blue circle, white check). */
    private View item(android.app.Activity a, int iconRes, String title, String sub, Runnable action) {
        LinearLayout row = Ui.row(a);
        row.setBackgroundColor(Ui.card(a));
        Ui.pad(row, 12, 12, 12, 12);
        if (iconRes == com.friendbook.core.Icons.VERIFIED) {
            LinearLayout wrap0 = new LinearLayout(a);
            wrap0.setGravity(Gravity.CENTER);
            wrap0.setBackground(Ui.circle(Ui.inputBg(a)));
            int s0 = Ui.dp(a, 38);
            wrap0.setLayoutParams(new LinearLayout.LayoutParams(s0, s0));
            ImageView iv = new ImageView(a);
            iv.setImageResource(iconRes); // untinted: official blue badge with white check
            int s1 = Ui.dp(a, 20);
            wrap0.addView(iv, new LinearLayout.LayoutParams(s1, s1));
            row.addView(wrap0);
        } else {
            row.addView(com.friendbook.core.Icons.chipIcon(a, iconRes, com.friendbook.core.Icons.GRAY));
        }
        LinearLayout mid = Ui.col(a);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(a, 12);
        TextView t = Ui.tvBold(a, title, 15, Ui.txt(a));
        TextView st = Ui.tv(a, sub, 12, Ui.sub(a));
        mid.addView(t);
        mid.addView(st);
        row.addView(mid, mp);
        // chevron (decorative glyph, kept as text like Facebook Lite)
        TextView ch = Ui.tv(a, "›", 20, Ui.sub(a));
        row.addView(ch);
        row.setOnClickListener(v -> action.run());
        LinearLayout wrap = Ui.col(a);
        wrap.addView(row);
        wrap.addView(Ui.divider(a));
        return wrap;
    }

    private View switchItem(android.app.Activity a, int iconRes, String title, boolean on,
                            CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = Ui.row(a);
        row.setBackgroundColor(Ui.card(a));
        Ui.pad(row, 12, 8, 12, 8);
        row.addView(com.friendbook.core.Icons.chipIcon(a, iconRes, com.friendbook.core.Icons.GRAY));
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

    /**
     * 🎁 Daily bonus — claim +5 ⭐ once per day; streak grows on consecutive days.
     * Writes users/{me}: stars += 5, dailyStreak, lastBonusAt (own-doc update).
     */
    private void claimDailyBonus(final android.app.Activity a) {
        final String me = com.friendbook.core.Fb.uid();
        if (me == null) return;
        com.friendbook.core.Fb.db().collection("users").document(me).get()
                .addOnSuccessListener(d -> {
                    long now = System.currentTimeMillis();
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
                    cal.set(java.util.Calendar.MINUTE, 0);
                    cal.set(java.util.Calendar.SECOND, 0);
                    cal.set(java.util.Calendar.MILLISECOND, 0);
                    long todayStart = cal.getTimeInMillis();

                    Object lb = d.get("lastBonusAt");
                    long last = lb instanceof Number ? ((Number) lb).longValue()
                            : lb instanceof com.google.firebase.Timestamp
                            ? ((com.google.firebase.Timestamp) lb).toDate().getTime() : 0;
                    Object st = d.get("dailyStreak");
                    int streak = st instanceof Number ? ((Number) st).intValue() : 0;

                    if (last >= todayStart) {
                        Ui.info(a, "🎁 Daily bonus",
                                "Already claimed today!\n\n🔥 Streak: " + streak + " day(s)\n"
                                        + "Come back tomorrow for +5 ⭐");
                        return;
                    }
                    int newStreak = (last >= todayStart - 24L * 3600_000L) ? streak + 1 : 1;
                    int bonus = 5 + Math.min(newStreak - 1, 5); // streak day 6+ → 10 ⭐
                    boolean verified = com.friendbook.core.Fb.me != null
                            && com.friendbook.core.Icons.tickRank(
                                    com.friendbook.core.Fb.me.tickLevel) > 0;
                    if (verified) bonus *= 2; // ✓ verified perk: double daily bonus
                    final int bonusF = bonus;
                    java.util.Map<String, Object> upd = new java.util.HashMap<>();
                    upd.put("stars", com.google.firebase.firestore.FieldValue.increment(bonus));
                    upd.put("dailyStreak", newStreak);
                    upd.put("lastBonusAt", now);
                    com.friendbook.core.Fb.db().collection("users").document(me).update(upd)
                            .addOnSuccessListener(x -> Ui.info(a, "🎁 Bonus claimed!",
                                    "+" + bonusF + " ⭐ added to your balance!\n\n"
                                            + "🔥 Streak: " + newStreak + " day(s)\n"
                                            + (verified ? "✓ Verified ×2 bonus applied 💙\n" : "")
                                            + (newStreak >= 5 ? "Max bonus unlocked — keep it going! 👑"
                                            : "Come back tomorrow — streak rewards grow!")))
                            .addOnFailureListener(e -> Ui.toast(a, e.getMessage()));
                })
                .addOnFailureListener(e -> Ui.toast(a, e.getMessage()));
    }
}
