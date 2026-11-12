package com.friendbook.social.main;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.graphics.Color;
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

import com.friendbook.social.auth.LoginActivity;
import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Presence;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.SavedPostsActivity;
import com.friendbook.social.profile.ProfileActivity;
import com.friendbook.social.settings.ArchiveTrashActivity;
import com.friendbook.social.settings.SettingsActivity;
import com.friendbook.social.settings.TimeSpentActivity;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ☰ Menu tab — Authentic friendbook v2.2.0 layout:
 * Profile card + Multi-Account Quick Switcher, 2-Column Shortcut Tiles Grid,
 * Quick Lite toggles (Dark / Data Saver / Quiet), Settings & Privacy, Help & Logout.
 */
public class MenuFragment extends Fragment {

    private ImageView avatar, tickIv;
    private TextView name, bio;
    private ListenerRegistration meReg;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        Activity a = getActivity();
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));

        LinearLayout head = Ui.row(a);
        Ui.pad(head, 14, 12, 14, 6);
        head.addView(Ui.tvBold(a, "Menu", 22, Ui.txt(a)), new LinearLayout.LayoutParams(0, -2, 1));
        ImageView gearTop = Icons.icon(a, Icons.SETTINGS, 22, Icons.GRAY);
        gearTop.setOnClickListener(v -> startActivity(new Intent(a, SettingsActivity.class)));
        head.addView(gearTop);
        ImageView searchTop = Icons.icon(a, Icons.SEARCH, 22, Icons.GRAY);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22));
        sp.leftMargin = Ui.dp(a, 14);
        searchTop.setOnClickListener(v -> startActivity(new Intent(a, com.friendbook.social.friends.SearchActivity.class)));
        head.addView(searchTop, sp);
        root.addView(head);

        ScrollView sv = new ScrollView(a);
        LinearLayout body = Ui.col(a);
        Ui.pad(body, 10, 4, 10, 20);

        // ---- Profile + Multi-Account Switcher Card ----
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.round(a, Ui.card(a), 12));
        Ui.pad(card, 12, 12, 12, 10);
        LinearLayout row = Ui.row(a);
        avatar = new ImageView(a);
        int s = Ui.dp(a, 50);
        row.addView(avatar, new LinearLayout.LayoutParams(s, s));

        LinearLayout ncol = Ui.col(a);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
        np.leftMargin = Ui.dp(a, 12);
        LinearLayout nameRow = Ui.row(a);
        name = Ui.tvBold(a, "", 17, Ui.txt(a));
        tickIv = new ImageView(a);
        tickIv.setVisibility(View.GONE);
        LinearLayout.LayoutParams tkp = new LinearLayout.LayoutParams(Ui.dp(a, 17), Ui.dp(a, 17));
        tkp.leftMargin = Ui.dp(a, 5);
        nameRow.addView(name);
        nameRow.addView(tickIv, tkp);
        bio = Ui.tv(a, "View your profile", 12, Ui.sub(a));
        ncol.addView(nameRow);
        ncol.addView(bio);
        row.addView(ncol, np);

        TextView switchAcc = Ui.btnOutline(a, "⇄ Switch", Ui.BLUE);
        switchAcc.setOnClickListener(v -> switchAccountDialog(a));
        row.addView(switchAcc);
        card.addView(row);

        View.OnClickListener goProfile = v -> {
            Intent i = new Intent(a, ProfileActivity.class);
            i.putExtra("uid", Fb.uid());
            startActivity(i);
        };
        avatar.setOnClickListener(goProfile);
        ncol.setOnClickListener(goProfile);
        body.addView(card);

        // ⚡ 0.0ms immediate profile card render from cached Fb.me!
        if (Fb.me != null) {
            name.setText(Fb.me.displayName);
            bio.setText(Fb.me.bio == null || Fb.me.bio.isEmpty() ? "View your profile" : Fb.me.bio);
            Ui.avatar(a, avatar, Fb.me.photoUrl);
            Icons.setTickBadge(tickIv, Fb.me.tickLevel, false);
        }

        // ---- Admin Zone (owner / promoted FULL admins ONLY — never Global Moderators) ----
        if (Admin.isAdmin()) {
            LinearLayout adminCard = Ui.col(a);
            adminCard.setBackground(Ui.roundStroke(a, Ui.card(a), 12, Ui.ORANGE, 2));
            TextView crown = Ui.tvBold(a, "🛡 ADMIN SUPER DASHBOARD", 12, Ui.ORANGE);
            crown.setGravity(Gravity.CENTER);
            Ui.pad(crown, 0, 8, 0, 0);
            adminCard.addView(crown);
            TextView open = Ui.tvBold(a, "⚡ Manage users · moderators · ticks · live · broadcast", 13, Ui.txt(a));
            open.setGravity(Gravity.CENTER);
            Ui.pad(open, 12, 6, 12, 10);
            adminCard.addView(open);
            adminCard.setOnClickListener(v ->
                    startActivity(new Intent(a, com.friendbook.social.admin.AdminPanelActivity.class)));
            LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(-1, -2);
            acp.topMargin = Ui.dp(a, 10);
            body.addView(adminCard, acp);
        } else if (Admin.isModerator()) {
            // Global Moderator ONLY gets Moderator Desk (Delete Posts & Ban Users) — NO Admin Panel!
            LinearLayout modCard = Ui.col(a);
            modCard.setBackground(Ui.roundStroke(a, Ui.card(a), 12, Ui.BLUE, 2));
            TextView badge = Ui.tvBold(a, "🛡 FRIENDBOOK MODERATOR DESK", 12, Ui.BLUE);
            badge.setGravity(Gravity.CENTER);
            Ui.pad(badge, 0, 8, 0, 0);
            modCard.addView(badge);
            TextView desc = Ui.tvBold(a, "🗑 Delete rule-breaking posts · 🚫 Ban abusive accounts", 13, Ui.txt(a));
            desc.setGravity(Gravity.CENTER);
            Ui.pad(desc, 12, 6, 12, 10);
            modCard.addView(desc);
            modCard.setOnClickListener(v ->
                    startActivity(new Intent(a, com.friendbook.social.admin.ModeratorDeskActivity.class)));
            LinearLayout.LayoutParams mcp = new LinearLayout.LayoutParams(-1, -2);
            mcp.topMargin = Ui.dp(a, 10);
            body.addView(modCard, mcp);
        }

        // ---- All Shortcuts (Authentic friendbook 2-Column Grid) ----
        TextView scHead = Ui.tvBold(a, "All shortcuts", 13, Ui.sub(a));
        scHead.setPadding(Ui.dp(a, 4), Ui.dp(a, 12), 0, Ui.dp(a, 6));
        body.addView(scHead);

        addGridRow(body, a,
                tile(a, Icons.VIDEO, 0xFFE41E3F, "🔴 Live Videos", "Go Live or watch live",
                        () -> startActivity(new Intent(a, com.friendbook.social.live.LiveHubActivity.class))),
                tile(a, Icons.CHART, Ui.BLUE, "Creator Dashboard", "Reach, likes & level",
                        () -> com.friendbook.social.profile.CreatorDashboardDialog.show(a, null)));

        addGridRow(body, a,
                tile(a, Icons.DATA_SAVER, 0xFF0E5FC7, "Data mode & 2G", "Free text / Saver",
                        () -> com.friendbook.social.core.FastCache.showLiteModeDialog(a, () -> {
                            if (a instanceof MainActivity) ((MainActivity) a).refreshLiteUi();
                        })),
                tile(a, Icons.GLOBE, 0xFFE41E3F, "🎥 Go Live Studio", "Start camera & voice live",
                        () -> {
                            Intent li = new Intent(a, com.friendbook.social.live.LiveActivity.class);
                            li.putExtra("host", true);
                            startActivity(li);
                        }));

        addGridRow(body, a,
                tile(a, Icons.FRIENDS, Ui.BLUE, "Friends", "Requests & PYMK",
                        () -> ((MainActivity) a).select(MainActivity.TAB_FRIENDS)),
                tile(a, Icons.MARKETPLACE, 0xFF00A400, "Marketplace", "Buy & sell nearby",
                        () -> startActivity(new Intent(a, com.friendbook.social.settings.MarketplaceActivity.class))));

        addGridRow(body, a,
                tile(a, Icons.GROUPS, Ui.BLUE, "Groups", "Communities & feeds",
                        () -> startActivity(new Intent(a, com.friendbook.social.groups.GroupsActivity.class))),
                tile(a, Icons.VIDEO, 0xFFE41E3F, "Reels", "Short vertical videos",
                        () -> ((MainActivity) a).select(MainActivity.TAB_REELS)));

        addGridRow(body, a,
                tile(a, Icons.VIDEO, 0xFF1877F2, "Watch", "Public videos feed",
                        () -> startActivity(new Intent(a, com.friendbook.social.post.WatchActivity.class))),
                tile(a, Icons.SNOOZE, 0xFF0E76A8, "Memories", "On this day",
                        () -> startActivity(new Intent(a, com.friendbook.social.post.MemoriesActivity.class))));

        addGridRow(body, a,
                tile(a, Icons.BOOKMARK, 0xFF9360F7, "Saved", "Posts, videos & items",
                        () -> startActivity(new Intent(a, SavedPostsActivity.class))),
                tile(a, Icons.FLAG, 0xFFE66A23, "Pages", "Follow & post as Page",
                        () -> startActivity(new Intent(a, com.friendbook.social.social.PagesActivity.class))));

        addGridRow(body, a,
                tile(a, Icons.ACTIVE, 0xFFE41E3F, "Events", "Upcoming & RSVP",
                        () -> startActivity(new Intent(a, com.friendbook.social.social.EventsActivity.class))),
                tile(a, Icons.CAKE, 0xFFFF4081, "Birthdays", "Wish friends today",
                        () -> startActivity(new Intent(a, com.friendbook.social.friends.BirthdaysActivity.class))));

        addGridRow(body, a,
                tile(a, Icons.LIST, 0xFF2ABBA7, "Archive & Trash", "Stories, posts & bin",
                        () -> startActivity(new Intent(a, ArchiveTrashActivity.class))),
                tile(a, Icons.SNOOZE, 0xFF1877F2, "Time Spent", "Today: " + Prefs.todayMinutes(a) + "m",
                        () -> startActivity(new Intent(a, TimeSpentActivity.class))));

        addGridRow(body, a,
                tile(a, Icons.VERIFIED, Ui.BLUE, "✓ Tick Levels", "Free via 5 invites",
                        () -> startActivity(new Intent(a, com.friendbook.social.bluetick.BlueTickActivity.class))),
                tile(a, Icons.GIFT, 0xFFF7B928, "🎁 Daily Bonus", "Claim free ⭐ daily",
                        () -> claimDailyBonus(a)));

        addGridRow(body, a,
                tile(a, Icons.USER_PLUS, 0xFF1877F2, "🖐 Pokes", "Poke friends back",
                        () -> startActivity(new Intent(a, com.friendbook.social.social.PokesActivity.class))),
                tile(a, Icons.CHART, 0xFFF7B928, "🏆 Leaderboard", "Top ⭐ creators",
                        () -> startActivity(new Intent(a, com.friendbook.social.social.LeaderboardActivity.class))));

        addGridRow(body, a,
                tile(a, Icons.DATA_SAVER, 0xFF00A400, "⚡ 2G Turbo Mode",
                        com.friendbook.social.core.FastCache.modeBadgeLabel(a),
                        () -> com.friendbook.social.core.FastCache.showLiteModeDialog(a, () -> {
                            if (a instanceof MainActivity) ((MainActivity) a).refreshLiteUi();
                        })),
                tile(a, Icons.GLOBE, Ui.BLUE, "📡 Find Wi-Fi",
                        com.friendbook.social.core.FastCache.netLabel(a).replace("📡 ", "").replace("⚡ ", ""),
                        () -> {
                            try {
                                startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                            } catch (Throwable t) {
                                Ui.toast(a, com.friendbook.social.core.FastCache.netLabel(a));
                            }
                        }));

        addGridRow(body, a,
                tile(a, Icons.VIDEO, 0xFFE41E3F, "🎬 Reels Pro", "Watch, Like, Comment & Share",
                        () -> startActivity(new Intent(a, com.friendbook.social.reels.ReelPlayerActivity.class))),
                tile(a, Icons.LOCK, Ui.BLUE, "🛡️ DP Guard",
                        (Fb.me != null && Fb.me.profilePicGuard) ? "Protected ON ✓" : "Protect your DP",
                        () -> {
                            boolean next = !(Fb.me != null && Fb.me.profilePicGuard);
                            java.util.Map<String, Object> up = new java.util.HashMap<>();
                            up.put("profilePicGuard", next);
                            Fb.patchMe(up);
                            if (Fb.me != null) Fb.me.profilePicGuard = next;
                            Ui.toast(a, next ? "🛡️ Profile Picture Guard ON!" : "Profile Picture Guard OFF");
                        }));

        body.addView(spacer(a, 10));

        // ---- Quick Lite Controls ----
        TextView qHead = Ui.tvBold(a, "Lite Pro quick controls", 13, Ui.sub(a));
        qHead.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), 0, Ui.dp(a, 6));
        body.addView(qHead);

        body.addView(switchItem(a, Icons.MOON, "Dark mode", Prefs.dark(a), (btn, on) -> {
            Prefs.setDark(a, on);
            a.recreate();
        }));
        body.addView(switchItem(a, Icons.MESSAGES, "💬 Inline Feed Comments (all users)",
                Prefs.inlineFeedComments(a), (btn, on) -> {
                    Prefs.setInlineFeedComments(a, on);
                    if (a instanceof MainActivity) ((MainActivity) a).refreshLiteUi();
                    Ui.toast(a, on ? "💬 Inline multi-user comments ON!" : "Inline feed comments hidden");
                }));
        body.addView(switchItem(a, Icons.IMAGE, "🖼 Full Uncropped HD Photos & DP",
                Prefs.fullPhotoDisplay(a), (btn, on) -> {
                    Prefs.setFullPhotoDisplay(a, on);
                    if (a instanceof MainActivity) ((MainActivity) a).refreshLiteUi();
                    Ui.toast(a, on ? "🖼 Full uncropped HD photos & DP ON!" : "Compact crop mode");
                }));
        body.addView(switchItem(a, Icons.DATA_SAVER, "📵 Text-Only 2G Mode (0 KB auto photos)",
                Prefs.textOnlyMode(a), (btn, on) -> {
                    Prefs.setTextOnlyMode(a, on);
                    if (a instanceof MainActivity) ((MainActivity) a).refreshLiteUi();
                    Ui.toast(a, on ? "📵 Text-Only 2G Mode ON — tap photos to load!" : "Photos auto-loading restored");
                }));
        body.addView(switchItem(a, Icons.DATA_SAVER, "Data Saver (" + Ui.formatBytes(Prefs.dataBytesSaved(a)) + " saved)",
                Prefs.dataSaver(a), (btn, on) -> {
                    Prefs.setDataSaver(a, on);
                    if (a instanceof MainActivity) ((MainActivity) a).refreshLiteUi();
                    Ui.toast(a, on ? "Data Saver ON — ~14 KB WebP photos & manual video" : "Data Saver OFF");
                }));
        body.addView(switchItem(a, Icons.QUIET, "Quiet mode", Prefs.quiet(a), (btn, on) -> {
            Prefs.setQuiet(a, on);
            Ui.toast(a, on ? "Quiet mode ON — badges muted" : "Quiet mode OFF");
        }));
        body.addView(item(a, Icons.BOOKMARK, "📥 Preload 30 Posts for Offline / 2G",
                "Cache latest feed posts & mini thumbnails for instant 0ms offline reading",
                () -> com.friendbook.social.core.FastCache.preloadFeedForOffline(a, null)));

        body.addView(spacer(a, 10));

        // ---- Settings, Activity & Help ----
        body.addView(item(a, Icons.SETTINGS, "Settings & privacy",
                "Full friendbook settings: security, privacy, media, language, 2FA, archive",
                () -> startActivity(new Intent(a, SettingsActivity.class))));
        body.addView(item(a, Icons.LIST, "Activity log",
                "Review your posts, comments & likes",
                () -> startActivity(new Intent(a, com.friendbook.social.settings.ActivityLogActivity.class))));
        body.addView(item(a, Icons.HELP, "Help & support",
                "Help Center, FAQs & shake-to-report",
                () -> Ui.info(a, "Help & Support",
                        "• Shake your phone anytime on Home to send a bug report\n"
                                + "• Open Settings & privacy to search 40+ controls\n"
                                + "• Past stories & deleted posts live in Archive & Trash")));
        final String vName = Ui.appVersionName(a);
        final int vCode = Ui.appVersionCode(a);
        final String pkg = a.getPackageName();
        body.addView(item(a, Icons.INFO, "About friendbook",
                "Version " + vName + " · " + pkg,
                () -> Ui.info(a, "About friendbook",
                        "friendbook for Android\nVersion " + vName + " (Build " + vCode + ")\nPackage: " + pkg + "\n\n"
                                + "Fast, lightweight social network with Live Video & Voice Broadcasts, Reels, Stories, Groups & Messenger.")));

        body.addView(spacer(a, 12));

        TextView logout = Ui.btn(a, "🚪 Log out");
        logout.setBackground(Ui.round(a, Ui.RED, 8));
        logout.setOnClickListener(v -> Ui.confirm(a, "Log out?",
                "Your account stays in Quick Switch on this phone for 1-tap sign-in.", "Log out", () -> {
                    if (Fb.auth().getCurrentUser() != null) {
                        Prefs.saveAccount(a, Fb.uid(), Fb.auth().getCurrentUser().getEmail(),
                                Fb.myName(), Fb.myPhoto());
                    }
                    Presence.goOffline();
                    com.friendbook.social.core.FastCache.clearAll(a);
                    com.friendbook.social.auth.GoogleAuthHelper.signOutGoogle(a);
                    com.friendbook.social.push.PushManager.unregister(com.friendbook.social.App.instance);
                    Fb.auth().signOut();
                    Fb.me = null;
                    Prefs.setUnlockedSession(a, false);
                    Intent i = new Intent(a, LoginActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    a.finish();
                }));
        body.addView(logout);

        TextView ver = Ui.tv(a, "friendbook for Android · v" + vName, 11, Ui.sub(a));
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, Ui.dp(a, 14), 0, 0);
        body.addView(ver);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void addGridRow(LinearLayout body, Activity a, View left, View right) {
        LinearLayout row = Ui.row(a);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.rightMargin = Ui.dp(a, 4);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1);
        lp2.leftMargin = Ui.dp(a, 4);
        row.addView(left, lp1);
        row.addView(right, lp2);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = Ui.dp(a, 8);
        body.addView(row, rp);
    }

    private View tile(Activity a, int iconRes, int tint, String title, String sub, Runnable click) {
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.round(a, Ui.card(a), 12));
        Ui.pad(card, 12, 10, 12, 10);
        card.addView(Icons.icon(a, iconRes, 24, tint));
        TextView t = Ui.tvBold(a, title, 14, Ui.txt(a));
        t.setSingleLine(true);
        t.setPadding(0, Ui.dp(a, 6), 0, 0);
        card.addView(t);
        TextView s = Ui.tv(a, sub, 11, Ui.sub(a));
        s.setSingleLine(true);
        card.addView(s);
        card.setOnClickListener(v -> click.run());
        return card;
    }

    private void switchAccountDialog(Activity a) {
        if (Fb.auth().getCurrentUser() != null) {
            Prefs.saveAccount(a, Fb.uid(), Fb.auth().getCurrentUser().getEmail(),
                    Fb.myName(), Fb.myPhoto());
        }
        List<Prefs.SavedAccount> list = Prefs.savedAccounts(a);
        String[] opts = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) {
            Prefs.SavedAccount sa = list.get(i);
            boolean cur = Fb.auth().getCurrentUser() != null
                    && sa.email.equalsIgnoreCase(Fb.auth().getCurrentUser().getEmail());
            opts[i] = (cur ? "🟢 " : "👤 ") + sa.name + " (" + sa.email + ")";
        }
        opts[list.size()] = "＋ Log into another account";
        Ui.choose(a, "Switch account", opts, w -> {
            String prefill = w < list.size() ? list.get(w).email : "";
            if (w < list.size() && Fb.auth().getCurrentUser() != null
                    && prefill.equalsIgnoreCase(Fb.auth().getCurrentUser().getEmail())) {
                Ui.toast(a, "Already using this account ✓");
                return;
            }
            Presence.goOffline();
            com.friendbook.social.core.FastCache.clearAll(a);
            com.friendbook.social.auth.GoogleAuthHelper.signOutGoogle(a);
            com.friendbook.social.push.PushManager.unregister(com.friendbook.social.App.instance);
                    Fb.auth().signOut();
            Fb.me = null;
            Prefs.setUnlockedSession(a, false);
            Intent i = new Intent(a, LoginActivity.class);
            if (!prefill.isEmpty()) i.putExtra("prefill_email", prefill);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            a.finish();
        });
    }

    private View spacer(Activity a, int h) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(a, h)));
        return v;
    }

    private View item(Activity a, int iconRes, String title, String sub, Runnable click) {
        LinearLayout row = Ui.row(a);
        row.setBackgroundColor(Ui.card(a));
        Ui.pad(row, 12, 11, 12, 11);
        row.addView(Icons.chipIcon(a, iconRes, Icons.GRAY));
        LinearLayout mid = Ui.col(a);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(a, 12);
        mid.addView(Ui.tvBold(a, title, 15, Ui.txt(a)));
        mid.addView(Ui.tv(a, sub, 12, Ui.sub(a)));
        row.addView(mid, mp);
        row.addView(Ui.tv(a, "›", 18, Ui.sub(a)));
        row.setOnClickListener(v -> click.run());
        LinearLayout wrap = Ui.col(a);
        wrap.addView(row);
        wrap.addView(Ui.divider(a));
        return wrap;
    }

    private View switchItem(Activity a, int iconRes, String title, boolean on,
                            CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = Ui.row(a);
        row.setBackgroundColor(Ui.card(a));
        Ui.pad(row, 12, 8, 12, 8);
        row.addView(Icons.chipIcon(a, iconRes, Icons.GRAY));
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
        // READ = Cloudflare first: this build writes the profile row to D1, so the card is filled from
        // there before any pre-migration listener runs.
        com.friendbook.social.core.CloudflareDocs.get("users", me, row -> {
            if (row != null && getActivity() != null) {
                Models.User u = Models.User.fromMap(me, row);
                if (u != null) renderMe(u);
            }
        });
        meReg = Fb.db().collection("users").document(me)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists() || getActivity() == null) return;
                    Models.User u = Models.User.fromDoc(d);
                    renderMe(u);
                });
    }

    /** Fills the profile card in the drawer for whichever source answered. */
    private void renderMe(Models.User u) {
        if (u == null || getActivity() == null) return;
        name.setText(u.displayName);
        Icons.setTickBadge(tickIv, u.tickLevel, false);
        String sub = (u.professionalMode ? "📊 Creator · " : "")
                + (u.bio == null || u.bio.isEmpty() ? "Tap to view your profile" : u.bio);
        bio.setText(sub);
        Ui.avatar(getActivity(), avatar, u.photoUrl);
        if (Fb.auth().getCurrentUser() != null) {
            Prefs.saveAccount(getActivity(), u.uid,
                    Fb.auth().getCurrentUser().getEmail(), u.displayName, u.photoUrl);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (meReg != null) meReg.remove();
    }

    private void claimDailyBonus(final Activity a) {
        final String me = Fb.uid();
        if (me == null) return;
        Fb.db().collection("users").document(me).get()
                .addOnSuccessListener(d -> {
                    long now = System.currentTimeMillis();
                    Calendar cal = Calendar.getInstance();
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    long todayStart = cal.getTimeInMillis();

                    Object lb = d.get("lastBonusAt");
                    long last = lb instanceof Number ? ((Number) lb).longValue()
                            : lb instanceof Timestamp ? ((Timestamp) lb).toDate().getTime() : 0;
                    Object st = d.get("dailyStreak");
                    int streak = st instanceof Number ? ((Number) st).intValue() : 0;

                    if (last >= todayStart) {
                        Ui.info(a, "🎁 Daily bonus",
                                "Already claimed today!\n\n🔥 Streak: " + streak + " day(s)\n"
                                        + "Come back tomorrow for +5 ⭐");
                        return;
                    }
                    int newStreak = (last >= todayStart - 24L * 3600_000L) ? streak + 1 : 1;
                    int bonus = 5 + Math.min(newStreak - 1, 5);
                    boolean verified = Fb.me != null && Icons.tickRank(Fb.me.tickLevel) > 0;
                    if (verified) bonus *= 2;
                    final int bonusF = bonus;
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("stars", FieldValue.increment(bonus));
                    upd.put("dailyStreak", newStreak);
                    upd.put("lastBonusAt", now);
                    // WRITE = Cloudflare (own profile row), pre-migration only when it is off.
                    com.friendbook.social.core.CloudflareDocs.writeDocument("users", me, upd,
                            () -> Ui.info(a, "🎁 Bonus claimed!",
                                    "+" + bonusF + " ⭐ added to your balance!\n\n"
                                            + "🔥 Streak: " + newStreak + " day(s)\n"
                                            + (verified ? "✓ Verified ×2 bonus applied 💙\n" : "")
                                            + (newStreak >= 5 ? "Max bonus unlocked — keep it going! 👑"
                                            : "Come back tomorrow — streak rewards grow!")),
                            reason -> Ui.toast(a, "Bonus saved locally; it will sync shortly"),
                            () -> Fb.db().collection("users").document(me).update(upd)
                                    .addOnSuccessListener(x -> Ui.info(a, "🎁 Bonus claimed!",
                                            "+" + bonusF + " ⭐ added to your balance!"))
                                    .addOnFailureListener(e -> Ui.toast(a, e.getMessage())));
                })
                .addOnFailureListener(e -> Ui.toast(a, e.getMessage()));
    }
}
