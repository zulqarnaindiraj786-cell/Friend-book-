package com.friendbook.main;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.friendbook.App;
import com.friendbook.R;
import com.friendbook.chat.ConversationsActivity;
import com.friendbook.core.AdsConfig;
import com.friendbook.core.FastCache;
import com.friendbook.core.Fb;
import com.friendbook.core.Icons;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;
import com.friendbook.friends.SearchActivity;
import com.friendbook.groups.GroupsActivity;
import com.friendbook.live.LiveActivity;
import com.friendbook.post.CreatePostActivity;
import com.friendbook.profile.CreatorDashboardDialog;
import com.friendbook.reels.AddReelActivity;
import com.friendbook.social.PagesActivity;
import com.friendbook.story.AddStoryActivity;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/**
 * ⚡ Authentic Facebook-Lite v525 Pro Home Shell:
 * - Top "Data mode" + "📡 Buy data" strip (exact FB Lite top bar!)
 * - Bold blue "friendbook" wordmark + 3 circular gray buttons: [ ＋ ] [ 🔍 ] [ ☰ ]
 * - 6-Icon Top Navigation Bar directly below header:
 *     🏠 Home | 👥 Friends | 💬 Messages | 🎬 Reels | 🔔 Notifications | 📊 Creator Dashboard
 * - Retained 5-tab fragments (`add` + `hide`/`show`) for 0.0ms instant tab switching!
 */
public class MainActivity extends Activity {

    public static final int TAB_HOME = 0, TAB_FRIENDS = 1, TAB_REELS = 2, TAB_NOTIFS = 3, TAB_MENU = 4;

    private static final int[] TOP_SLOT_ICONS = {
            Icons.HOME,
            Icons.FRIENDS,
            Icons.MESSAGES,
            Icons.VIDEO,
            Icons.NOTIFICATIONS,
            Icons.CHART
    };

    private FrameLayout container;
    private AdView adView;
    private FrameLayout bottomAdWrap;
    private LinearLayout dataStripWrap;
    private LinearLayout brandBar;
    private LinearLayout navRow;
    private LinearLayout modeStrip;
    private TextView modeStripText, modeStripAction;
    private ImageView menuBtnIcon;
    private LinearLayout menuBtnCircle;
    private final ImageView[] slotIcons = new ImageView[6];
    private final View[] slotIndicators = new View[6];
    private final Fragment[] tabFragments = new Fragment[5];
    private TextView homeBadge, msgBadge, notifBadge, friendsBadge;
    private int active = -1;
    private ListenerRegistration notifReg, chatReg, reqReg, referralReg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Fb.signedIn()) { finish(); return; }
        Ui.statusBarLight(this);
        setContentView(R.layout.activity_main);

        LinearLayout root = findViewById(R.id.mainContentRoot);
        root.setBackgroundColor(Ui.bg(this));
        bottomAdWrap = findViewById(R.id.bottomAdWrap);
        if (bottomAdWrap != null) bottomAdWrap.setBackgroundColor(Ui.card(this));
        adView = findViewById(R.id.adView);

        // 1. Authentic Facebook Lite Top "Data mode" + "[ 📡 Buy data ]" Strip
        dataStripWrap = Ui.dataModeStrip(this, this::refreshLiteUi);
        root.addView(dataStripWrap);

        // 2. Crisp White Brand & Circular Action Header: friendbook   [ ＋ ] [ 🔍 ] [ ☰ ]
        brandBar = Ui.row(this);
        brandBar.setBackgroundColor(Ui.card(this));
        brandBar.setGravity(Gravity.CENTER_VERTICAL);
        brandBar.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 6));

        TextView logo = Ui.tvBold(this, "friendbook", 27, Ui.BLUE);
        logo.setLetterSpacing(-0.03f);
        logo.setOnClickListener(v -> {
            if (active != TAB_HOME) select(TAB_HOME);
            else if (tabFragments[TAB_HOME] instanceof HomeFragment) {
                ((HomeFragment) tabFragments[TAB_HOME]).scrollToTop();
            }
        });
        brandBar.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));

        brandBar.addView(circleHeaderIcon(Icons.PLUS, v -> showUniversalCreateSheet()));
        brandBar.addView(circleHeaderIcon(Icons.SEARCH,
                v -> startActivity(new Intent(this, SearchActivity.class))));

        menuBtnCircle = Ui.row(this);
        menuBtnCircle.setGravity(Gravity.CENTER);
        menuBtnCircle.setBackground(Ui.circle(Ui.inputBg(this)));
        menuBtnIcon = Icons.icon(this, Icons.MENU, 20, Ui.txt(this));
        menuBtnCircle.addView(menuBtnIcon);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        mlp.leftMargin = Ui.dp(this, 8);
        menuBtnCircle.setLayoutParams(mlp);
        menuBtnCircle.setOnClickListener(v -> select(active == TAB_MENU ? TAB_HOME : TAB_MENU));
        brandBar.addView(menuBtnCircle);
        root.addView(brandBar);

        // 3. Signature 6-Icon Facebook Lite Top Navigation Bar directly below Header!
        navRow = Ui.row(this);
        navRow.setBackgroundColor(Ui.card(this));
        navRow.setPadding(0, Ui.dp(this, 2), 0, 0);
        for (int s = 0; s < 6; s++) {
            final int slot = s;
            LinearLayout cell = Ui.col(this);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);

            FrameLayout iconWrap = new FrameLayout(this);
            ImageView ic = Icons.icon(this, TOP_SLOT_ICONS[s], 24, Icons.GRAY);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                    Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER);
            iconWrap.addView(ic, ip);
            iconWrap.setMinimumHeight(Ui.dp(this, 42));

            if (s == 0) {
                homeBadge = Ui.badge(this);
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                fp.topMargin = Ui.dp(this, 3);
                iconWrap.addView(homeBadge, fp);
            } else if (s == 1) {
                friendsBadge = Ui.badge(this);
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                fp.topMargin = Ui.dp(this, 3);
                iconWrap.addView(friendsBadge, fp);
            } else if (s == 2) {
                msgBadge = Ui.badge(this);
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                fp.topMargin = Ui.dp(this, 3);
                iconWrap.addView(msgBadge, fp);
            } else if (s == 4) {
                notifBadge = Ui.badge(this);
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                fp.topMargin = Ui.dp(this, 3);
                iconWrap.addView(notifBadge, fp);
            }

            cell.addView(iconWrap, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 42)));

            View ind = new View(this);
            ind.setBackgroundColor(Icons.BLUE);
            ind.setVisibility(View.INVISIBLE);
            cell.addView(ind, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2.5f)));

            slotIcons[s] = ic;
            slotIndicators[s] = ind;
            cell.setOnClickListener(v -> onTopSlotClicked(slot));
            navRow.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(navRow);
        root.addView(Ui.divider(this));

        // 4. Smart Offline / 2G Strip (only shown when Offline or 2G network active)
        modeStrip = Ui.row(this);
        modeStrip.setBackgroundColor(0xFF1A2942);
        Ui.pad(modeStrip, 12, 5, 10, 5);
        modeStripText = Ui.tvBold(this, "", 11, 0xFFFFFFFF);
        modeStrip.addView(modeStripText, new LinearLayout.LayoutParams(0, -2, 1));
        modeStripAction = Ui.roundChip(this, "Switch Mode", 0x33FFFFFF, 0xFFFFFFFF, 10);
        Ui.pad(modeStripAction, 8, 3, 8, 3);
        modeStrip.addView(modeStripAction);
        modeStrip.setVisibility(View.GONE);
        root.addView(modeStrip);

        // 5. Full-Bleed Fragment Container
        container = new FrameLayout(this);
        container.setId(View.generateViewId());
        root.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));

        select(TAB_HOME);
        refreshLiteUi();

        // 6. Initialize REAL Google AdMob Production SDK & Load Real Bottom Banner Ad
        initAndLoadProductionBannerAd();

        // ⚡ Defer non-critical SDK/network listeners until AFTER Frame #1 paints!
        Looper.myQueue().addIdleHandler(() -> {
            Fb.ensureMeFresh();
            Presence.init();
            startBadgeListeners();
            watchReferralsForFreeTick();
            askNotifPermission();
            AdsConfig.startWatching();
            AdsConfig.preloadInterstitial(MainActivity.this);
            try {
                com.google.firebase.remoteconfig.FirebaseRemoteConfig rc =
                        com.google.firebase.remoteconfig.FirebaseRemoteConfig.getInstance();
                rc.setConfigSettingsAsync(new com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings.Builder()
                        .setMinimumFetchIntervalInSeconds(3600).build());
                rc.setDefaultsAsync(java.util.Collections.singletonMap("ads_enabled", true));
                rc.fetchAndActivate().addOnCompleteListener(t ->
                        AdsConfig.setRemoteAllowed(rc.getBoolean("ads_enabled")));
            } catch (Throwable ignored) { }
            return false;
        });

        App.instance.setShakeListener(this::feedbackDialog);
    }

    private void initAndLoadProductionBannerAd() {
        try {
            MobileAds.initialize(this, initializationStatus -> { });
            if (adView != null) {
                adView.setAdListener(new AdListener() {
                    @Override
                    public void onAdLoaded() {
                        if (bottomAdWrap != null) bottomAdWrap.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onAdFailedToLoad(@androidx.annotation.NonNull LoadAdError adError) {
                        // Retry loading real ad after 25s if network was momentarily unavailable
                        if (adView != null && !isFinishing()) {
                            adView.postDelayed(() -> {
                                if (adView != null && !isFinishing() && AdsConfig.bannersEnabled()) {
                                    adView.loadAd(new AdRequest.Builder().build());
                                }
                            }, 25_000L);
                        }
                    }
                });
                AdRequest adRequest = new AdRequest.Builder().build();
                adView.loadAd(adRequest);
            }
        } catch (Throwable ignored) { }
    }

    private View circleHeaderIcon(int iconRes, View.OnClickListener l) {
        LinearLayout circle = Ui.row(this);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(Ui.circle(Ui.inputBg(this)));
        circle.addView(Icons.icon(this, iconRes, 20, Ui.txt(this)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        p.leftMargin = Ui.dp(this, 8);
        circle.setLayoutParams(p);
        circle.setOnClickListener(l);
        return circle;
    }

    private void onTopSlotClicked(int slot) {
        switch (slot) {
            case 0:
                Ui.setBadge(homeBadge, 0);
                select(TAB_HOME);
                break;
            case 1:
                select(TAB_FRIENDS);
                break;
            case 2:
                startActivity(new Intent(this, ConversationsActivity.class));
                break;
            case 3:
                select(TAB_REELS);
                break;
            case 4:
                select(TAB_NOTIFS);
                break;
            case 5:
                CreatorDashboardDialog.show(this, null);
                break;
        }
    }

    /** Pro Facebook Lite '+' Universal Create Menu. */
    private void showUniversalCreateSheet() {
        String[] opts = {
                "📝 Create Post — text, photos, poll, feeling or check-in",
                "📖 Add to Story — photo or colorful Aa text story",
                "🎬 Create Reel — short vertical video",
                "🔴 Go Live — start live broadcast",
                "💭 Share 24h Note — status bubble on Home",
                "🚩 Create / Manage Pages",
                "👨‍👩‍👧‍👦 Create / Browse Groups",
                "✨ Feed View & Filter (Top / Recent / Favorites)"
        };
        Ui.choose(this, "Create & Explore", opts, which -> {
            switch (which) {
                case 0:
                    startActivity(new Intent(this, CreatePostActivity.class));
                    break;
                case 1:
                    startActivity(new Intent(this, AddStoryActivity.class));
                    break;
                case 2:
                    startActivity(new Intent(this, AddReelActivity.class));
                    break;
                case 3:
                    startActivity(new Intent(this, LiveActivity.class));
                    break;
                case 4:
                    select(TAB_HOME);
                    if (tabFragments[TAB_HOME] instanceof HomeFragment) {
                        ((HomeFragment) tabFragments[TAB_HOME]).showEditNoteDialog();
                    }
                    break;
                case 5:
                    startActivity(new Intent(this, PagesActivity.class));
                    break;
                case 6:
                    startActivity(new Intent(this, GroupsActivity.class));
                    break;
                case 7:
                    select(TAB_HOME);
                    if (tabFragments[TAB_HOME] instanceof HomeFragment) {
                        ((HomeFragment) tabFragments[TAB_HOME]).showFeedFilterSheet();
                    }
                    break;
            }
        });
    }

    public void refreshLiteUi() {
        Ui.statusBarLight(this);
        if (dataStripWrap != null) {
            dataStripWrap.setVisibility(Prefs.dataModeBar(this) ? View.VISIBLE : View.GONE);
        }
        if (modeStrip != null) {
            if (FastCache.isOffline(this)) {
                modeStrip.setVisibility(View.VISIBLE);
                modeStrip.setBackgroundColor(0xFF3A3B3C);
                modeStripText.setText("📴 Offline — viewing cached feed · auto-syncs online");
                modeStripAction.setText("2G Options");
                modeStripAction.setOnClickListener(v -> FastCache.showLiteModeDialog(this, this::refreshLiteUi));
            } else if (FastCache.netTier(this) == FastCache.NetTier.NET_2G) {
                modeStrip.setVisibility(View.VISIBLE);
                modeStrip.setBackgroundColor(0xFF0E5FC7);
                modeStripText.setText("🐌 2G detected — Ultra-Lite WebP Turbo active");
                modeStripAction.setText("Text Mode");
                modeStripAction.setOnClickListener(v -> {
                    Prefs.setTextOnlyMode(this, true);
                    refreshLiteUi();
                });
            } else {
                modeStrip.setVisibility(View.GONE);
            }
        }
        if (tabFragments[TAB_HOME] instanceof HomeFragment) {
            ((HomeFragment) tabFragments[TAB_HOME]).onLiteModeChanged();
        }
    }

    /**
     * ⚡ 0.0ms Retained Tab Switching:
     * Keeps fragments alive in memory via add + hide/show instead of destroying & recreating!
     */
    public void select(int tab) {
        if (tab == active) {
            if (tab == TAB_HOME && tabFragments[TAB_HOME] instanceof HomeFragment) {
                ((HomeFragment) tabFragments[TAB_HOME]).scrollToTop();
            }
            return;
        }
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        if (active >= 0 && tabFragments[active] != null) {
            ft.hide(tabFragments[active]);
        }
        Fragment target = tabFragments[tab];
        if (target == null) {
            switch (tab) {
                case TAB_FRIENDS: target = new com.friendbook.friends.FriendsFragment(); break;
                case TAB_REELS: target = new com.friendbook.reels.ReelsFragment(); break;
                case TAB_NOTIFS: target = new NotificationsFragment(); break;
                case TAB_MENU: target = new MenuFragment(); break;
                default: target = new HomeFragment(); break;
            }
            tabFragments[tab] = target;
            ft.add(container.getId(), target, "tab_" + tab);
        } else {
            ft.show(target);
        }
        ft.commitAllowingStateLoss();
        active = tab;

        // Map internal tab index -> 6-slot top navigation bar highlight
        int activeSlot = -1;
        if (tab == TAB_HOME) activeSlot = 0;
        else if (tab == TAB_FRIENDS) activeSlot = 1;
        else if (tab == TAB_REELS) activeSlot = 3;
        else if (tab == TAB_NOTIFS) activeSlot = 4;

        for (int s = 0; s < 6; s++) {
            boolean on = s == activeSlot;
            slotIndicators[s].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            Icons.setTint(slotIcons[s], on ? Icons.BLUE : Icons.GRAY);
        }
        if (menuBtnCircle != null && menuBtnIcon != null) {
            boolean onMenu = (tab == TAB_MENU);
            menuBtnCircle.setBackground(Ui.circle(onMenu ? 0xFFE7F3FF : Ui.inputBg(this)));
            Icons.setTint(menuBtnIcon, onMenu ? Icons.BLUE : Ui.txt(this));
        }
    }

    private void watchReferralsForFreeTick() {
        String me = Fb.uid();
        if (me == null) return;
        referralReg = Fb.db().collection("referrals")
                .whereEqualTo("refBy", me)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null) return;
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
                    if (Prefs.quiet(this)) Ui.setBadge(notifBadge, 0);
                    else Ui.setBadge(notifBadge, snap == null ? 0 : snap.size());
                });
        if (chatReg != null) chatReg.remove();
        chatReg = Fb.db().collection("chats")
                .whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    int total = 0;
                    if (snap != null && !Prefs.quiet(this)) {
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
                    if (Prefs.quiet(this)) Ui.setBadge(friendsBadge, 0);
                    else Ui.setBadge(friendsBadge, snap == null ? 0 : snap.size());
                });
    }

    private void askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 44);
        }
    }

    private void feedbackDialog() {
        if (active != TAB_HOME) return;
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
    protected void onPause() {
        if (adView != null) {
            try { adView.pause(); } catch (Throwable ignored) { }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bottomAdWrap != null) {
            bottomAdWrap.setBackgroundColor(Ui.card(this));
            bottomAdWrap.setVisibility(AdsConfig.bannersEnabled() ? View.VISIBLE : View.GONE);
        }
        if (adView != null && AdsConfig.bannersEnabled()) {
            try { adView.resume(); } catch (Throwable ignored) { }
        }
        refreshLiteUi();
        Fb.ensureMeFresh();
        Presence.init();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            try { adView.destroy(); } catch (Throwable ignored) { }
            adView = null;
        }
        super.onDestroy();
        if (referralReg != null) referralReg.remove();
        if (notifReg != null) notifReg.remove();
        if (chatReg != null) chatReg.remove();
        if (reqReg != null) reqReg.remove();
        App.instance.setShakeListener(null);
    }
}
