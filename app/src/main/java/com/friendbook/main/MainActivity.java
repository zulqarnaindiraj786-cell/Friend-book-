package com.friendbook.main;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.friendbook.App;
import com.friendbook.core.Fb;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;
import com.friendbook.chat.ConversationsActivity;
import com.friendbook.friends.SearchActivity;
import com.friendbook.post.CreatePostActivity;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/** Home shell: FB-Lite top bar, bottom nav with 5 tabs, realtime badges. */
public class MainActivity extends Activity {

    public static final int TAB_HOME = 0, TAB_FRIENDS = 1, TAB_REELS = 2, TAB_NOTIFS = 3, TAB_MENU = 4;

    /** Facebook-Lite v525 outline icons: Home, Friends, Video, Notifications, Menu. */
    private static final int[] TAB_ICON_RES = {
            com.friendbook.core.Icons.HOME,
            com.friendbook.core.Icons.FRIENDS,
            com.friendbook.core.Icons.VIDEO,
            com.friendbook.core.Icons.NOTIFICATIONS,
            com.friendbook.core.Icons.MENU};

    private FrameLayout container;
    private LinearLayout navRow;
    private final android.widget.ImageView[] navIcons = new android.widget.ImageView[5];
    private final View[] navIndicators = new View[5];
    private TextView msgBadge, notifBadge, friendsBadge;
    private int active = -1;
    private ListenerRegistration notifReg, chatReg, reqReg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Fb.signedIn()) { finish(); return; }
        Ui.statusBarBlue(this);
        Fb.ensureMeFresh();
        Presence.init();

        // 📢 AdMob init (once per process) + live banner config + Remote Config kill switch
        try {
            com.google.android.gms.ads.MobileAds.initialize(this, status -> { });
        } catch (Throwable ignored) { }
        com.friendbook.core.AdsConfig.startWatching();
        try {
            com.google.firebase.remoteconfig.FirebaseRemoteConfig rc =
                    com.google.firebase.remoteconfig.FirebaseRemoteConfig.getInstance();
            rc.setConfigSettingsAsync(new com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(3600).build());
            rc.setDefaultsAsync(java.util.Collections.singletonMap("ads_enabled", true));
            rc.fetchAndActivate().addOnCompleteListener(t ->
                    com.friendbook.core.AdsConfig.setRemoteAllowed(rc.getBoolean("ads_enabled")));
        } catch (Throwable ignored) { }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // ---- Top bar ----
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.card(this));
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        bar.setElevation(Ui.dp(this, 2));

        TextView logo = Ui.tvBold(this, "friendbook", 24, Ui.BLUE);
        bar.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));

        bar.addView(topIcon(com.friendbook.core.Icons.PLUS,
                v -> startActivity(new Intent(this, CreatePostActivity.class))));
        bar.addView(topIcon(com.friendbook.core.Icons.SEARCH,
                v -> startActivity(new Intent(this, SearchActivity.class))));
        bar.addView(messengerIcon());
        root.addView(bar);
        root.addView(Ui.divider(this));

        // ---- Fragment container ----
        container = new FrameLayout(this);
        container.setId(View.generateViewId());
        root.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- Bottom nav (thin OUTLINE icons, 2px stroke — Facebook Lite v525 style) ----
        navRow = Ui.row(this);
        navRow.setBackgroundColor(Ui.card(this));
        navRow.setElevation(Ui.dp(this, 8));
        navRow.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        for (int i = 0; i < 5; i++) {
            final int tab = i;
            LinearLayout cell = Ui.col(this);
            cell.setGravity(Gravity.CENTER);
            View ind = new View(this);
            ind.setBackgroundColor(com.friendbook.core.Icons.BLUE);
            ind.setVisibility(View.GONE);
            cell.addView(ind, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 3)));
            navIndicators[i] = ind;

            FrameLayout iconWrap = new FrameLayout(this);
            // outline icon: gray when idle, blue when active (2dp stroke vector)
            android.widget.ImageView ic = com.friendbook.core.Icons.icon(
                    this, TAB_ICON_RES[i], 24, com.friendbook.core.Icons.GRAY);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                    Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER);
            iconWrap.addView(ic, ip);
            iconWrap.setMinimumHeight(Ui.dp(this, 40));
            if (i == TAB_NOTIFS) {
                notifBadge = Ui.badge(this);
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                iconWrap.addView(notifBadge, fp);
            }
            if (i == TAB_FRIENDS) {
                friendsBadge = Ui.badge(this);
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                iconWrap.addView(friendsBadge, fp);
            }
            cell.addView(iconWrap);
            navIcons[i] = ic;
            cell.setOnClickListener(v -> select(tab));
            navRow.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(Ui.divider(this));
        root.addView(navRow);

        setContentView(root);
        select(TAB_HOME);
        startBadgeListeners();
        watchReferralsForFreeTick();
        askNotifPermission();

        // Secret: shake to report a problem.
        App.instance.setShakeListener(this::feedbackDialog);
    }

    /** Round gray chip with an outline icon (like Facebook Lite's top-right buttons). */
    private View topIcon(int iconRes, View.OnClickListener l) {
        LinearLayout chip = com.friendbook.core.Icons.chipIcon(
                this, iconRes, com.friendbook.core.Icons.GRAY);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        p.leftMargin = Ui.dp(this, 8);
        chip.setLayoutParams(p);
        chip.setOnClickListener(l);
        return chip;
    }

    private View messengerIcon() {
        FrameLayout wrap = new FrameLayout(this);
        LinearLayout chip = com.friendbook.core.Icons.chipIcon(
                this, com.friendbook.core.Icons.MESSAGES,
                com.friendbook.core.Icons.GRAY);
        wrap.addView(chip, new FrameLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        msgBadge = Ui.badge(this);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        wrap.addView(msgBadge, fp);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = Ui.dp(this, 8);
        wrap.setLayoutParams(p);
        wrap.setOnClickListener(v -> startActivity(new Intent(this, ConversationsActivity.class)));
        return wrap;
    }

    public void select(int tab) {
        if (tab == active) return;
        active = tab;
        Fragment f;
        switch (tab) {
            case TAB_FRIENDS: f = new com.friendbook.friends.FriendsFragment(); break;
            case TAB_REELS: f = new com.friendbook.reels.ReelsFragment(); break;
            case TAB_NOTIFS: f = new NotificationsFragment(); break;
            case TAB_MENU: f = new MenuFragment(); break;
            default: f = new HomeFragment(); break;
        }
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(container.getId(), f);
        ft.commitAllowingStateLoss();
        for (int i = 0; i < 5; i++) {
            boolean on = i == tab;
            navIndicators[i].setVisibility(on ? View.VISIBLE : View.GONE);
            // Facebook Lite: active = blue outline, idle = gray outline
            com.friendbook.core.Icons.setTint(navIcons[i],
                    on ? com.friendbook.core.Icons.BLUE : com.friendbook.core.Icons.GRAY);
        }
    }

    /**
     * 💙 FREE BLUE TICK: whenever my completed referrals reach 5, grant isVerified
     * automatically (self-write — allowed by rules) and celebrate once.
     */
    private ListenerRegistration referralReg;

    private void watchReferralsForFreeTick() {
        String me = Fb.uid();
        if (me == null) return;
        referralReg = Fb.db().collection("referrals")
                .whereEqualTo("refBy", me)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null) return;
                    // 5 invites → blue ✓ · 20 → gold ✓ · 50 → diamond 💎 (auto, upgrades only)
                    com.friendbook.core.Admin.autoGrantByReferrals(this, snap.size());
                });
    }

    private void startBadgeListeners() {
        String me = Fb.uid();
        if (me == null) return;
        if (notifReg != null) notifReg.remove();
        notifReg = Fb.db().collection("notifications")
                .whereEqualTo("toUserId", me).whereEqualTo("isRead", false)
                .addSnapshotListener((snap, err) -> {
                    Ui.setBadge(notifBadge, snap == null ? 0 : snap.size());
                });
        if (chatReg != null) chatReg.remove();
        chatReg = Fb.db().collection("chats")
                .whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    int total = 0;
                    if (snap != null) {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                            Object u = d.get("unreadCount");
                            if (u instanceof Map) {
                                Object n = ((Map<?, ?>) u).get(me);
                                if (n instanceof Number) total += ((Number) n).intValue();
                            }
                        }
                    }
                    Ui.setBadge(msgBadge, total);
                });
        if (reqReg != null) reqReg.remove();
        reqReg = Fb.db().collection("friend_requests")
                .whereEqualTo("toUid", me).whereEqualTo("status", "pending")
                .addSnapshotListener((snap, err) -> {
                    Ui.setBadge(friendsBadge, snap == null ? 0 : snap.size());
                });
    }

    private void askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 44);
        }
    }

    private void feedbackDialog() {
        if (active != TAB_HOME) return; // avoid interrupting videos/stories
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        TextView t = Ui.tv(this, "Shook your phone? Tell us what went wrong.", 14, Ui.sub(this));
        box.addView(t);
        EditText e = Ui.multi(this, "Describe the problem…", 3);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = Ui.dp(this, 10);
        box.addView(e, p);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Report a problem")
                .setView(box)
                .setPositiveButton("Send", (d, w) -> {
                    String txt = e.getText().toString().trim();
                    if (txt.isEmpty()) return;
                    Map<String, Object> m = new HashMap<>();
                    m.put("uid", Fb.uid());
                    m.put("name", Fb.myName());
                    m.put("text", txt);
                    m.put("app", "friendbook-android");
                    m.put("createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("feedback").add(m);
                    Ui.toast(this, "Thanks for your feedback!");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (active != TAB_HOME) select(TAB_HOME);
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Fb.ensureMeFresh();
        Presence.init();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (referralReg != null) referralReg.remove();
        if (notifReg != null) notifReg.remove();
        if (chatReg != null) chatReg.remove();
        if (reqReg != null) reqReg.remove();
        App.instance.setShakeListener(null);
    }

}
