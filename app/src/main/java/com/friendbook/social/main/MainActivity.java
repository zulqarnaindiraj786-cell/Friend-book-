package com.friendbook.social.main;

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

import com.friendbook.social.App;
import com.friendbook.social.R;
import com.friendbook.social.chat.ConversationsActivity;
import com.friendbook.social.core.AdsConfig;
import com.friendbook.social.core.ConsentManager;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.FeedAlgorithm;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Presence;
import com.friendbook.social.core.Ui;
import com.friendbook.social.friends.SearchActivity;
import com.friendbook.social.groups.GroupsActivity;
import com.friendbook.social.live.LiveActivity;
import com.friendbook.social.post.CreatePostActivity;
import com.friendbook.social.profile.CreatorDashboardDialog;
import com.friendbook.social.reels.AddReelActivity;
import com.friendbook.social.social.PagesActivity;
import com.friendbook.social.story.AddStoryActivity;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/**
 * ⚡ Authentic friendbook v525 Pro Home Shell:
 * - Top "Data mode" + "📡 Buy data" strip (exact friendbook top bar!)
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

    public static volatile MainActivity activeInstance;

    private FrameLayout container;
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

    private final java.util.Set<String> currentPendingReqIds = new java.util.HashSet<>();
    private final java.util.Set<String> currentUnreadChatIds = new java.util.HashSet<>();
    private final java.util.Set<String> currentUnreadNotifIds = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Fb.signedIn()) { finish(); return; }
        activeInstance = this;
        // Advance friendbook discovery epoch on every app open so user greets fresh posts, videos & ads!
        FeedAlgorithm.onColdAppLaunch(this);
        Ui.statusBarLight(this);
        setContentView(R.layout.activity_main);

        LinearLayout root = findViewById(R.id.mainContentRoot);
        root.setBackgroundColor(Ui.bg(this));

        // 1. Authentic friendbook Top "Data mode" + "[ 📡 Buy data ]" Strip
        dataStripWrap = Ui.dataModeStrip(this, this::refreshLiteUi);
        root.addView(dataStripWrap);

        // 2. Crisp White Brand & Circular Action Header: friendbook   [ ＋ ] [ 🔍 ] [ ☰ ]
        brandBar = Ui.row(this);
        brandBar.setBackgroundColor(Ui.card(this));
        brandBar.setGravity(Gravity.CENTER_VERTICAL);
        brandBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 7));

        TextView logo = Ui.tvBold(this, "friendbook", 27, Ui.isDark(this) ? 0xFFFFFFFF : 0xFF111827);
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

        // 3. Signature 6-Icon friendbook Top Navigation Bar directly below Header!
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

        // 6. Gather UMP GDPR Consent & Initialize Google Mobile Ads SDK for Real Native Advanced Ads
        ConsentManager.gatherConsentAndInitAds(this, () -> {
            if (tabFragments[TAB_HOME] instanceof HomeFragment) {
                ((HomeFragment) tabFragments[TAB_HOME]).onLiteModeChanged();
            }
        });

        // 7. Silently pre-warm top Reels onto SSD cache after Home renders so first Reels tap plays in ~15ms!
        container.postDelayed(() ->
                com.friendbook.social.reels.ReelVideoCache.warmStartupReels(getApplicationContext()), 900L);

        // ⚡ Defer non-critical SDK/network listeners until AFTER Frame #1 paints!
        Looper.myQueue().addIdleHandler(() -> {
            Fb.ensureMeFresh();
            Presence.init();
            startBadgeListeners();
            watchReferralsForFreeTick();
            askNotifPermission();
            AdsConfig.startWatching();
            try {
                com.google.firebase.remoteconfig.FirebaseRemoteConfig rc =
                        com.google.firebase.remoteconfig.FirebaseRemoteConfig.getInstance();
                rc.setConfigSettingsAsync(new com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings.Builder()
                        .setMinimumFetchIntervalInSeconds(3600).build());
                rc.setDefaultsAsync(java.util.Collections.singletonMap("ads_enabled_v3", true))
                        .addOnCompleteListener(dt -> rc.fetchAndActivate().addOnCompleteListener(t -> {
                            String raw = rc.getString("ads_enabled_v3");
                            boolean allow = !"false".equalsIgnoreCase(raw == null ? "" : raw.trim());
                            AdsConfig.setRemoteAllowed(allow);
                        }));
            } catch (Throwable ignored) { }
            return false;
        });

        App.instance.setShakeListener(this::feedbackDialog);
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
                clearFriendsBadgeNow();
                select(TAB_FRIENDS);
                break;
            case 2:
                clearMessagesBadgeNow();
                startActivity(new Intent(this, ConversationsActivity.class));
                break;
            case 3:
                select(TAB_REELS);
                break;
            case 4:
                clearNotificationsBadgeNow();
                select(TAB_NOTIFS);
                break;
            case 5:
                CreatorDashboardDialog.show(this, null);
                break;
        }
    }

    /** Pro friendbook '+' Universal Create Menu. */
    private void showUniversalCreateSheet() {
        String[] opts = {
                "📝 Create Post — text, photos, poll, feeling or check-in",
                "🎬 Upload Reel — share short vertical video fast ⚡",
                "📖 Add to Story — photo or colorful Aa text story",
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
                    startActivity(new Intent(this, AddReelActivity.class));
                    break;
                case 2:
                    startActivity(new Intent(this, AddStoryActivity.class));
                    break;
                case 3: {
                    Intent li = new Intent(this, LiveActivity.class);
                    li.putExtra("host", true);
                    startActivity(li);
                    break;
                }
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
            } else if (tab == TAB_REELS && tabFragments[TAB_REELS] instanceof com.friendbook.social.reels.ReelsFragment) {
                ((com.friendbook.social.reels.ReelsFragment) tabFragments[TAB_REELS]).refreshWithAlgorithm();
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
                case TAB_FRIENDS: target = new com.friendbook.social.friends.FriendsFragment(); break;
                case TAB_REELS: target = new com.friendbook.social.reels.ReelsFragment(); break;
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
        else if (tab == TAB_FRIENDS) {
            activeSlot = 1;
            clearFriendsBadgeNow();
        } else if (tab == TAB_REELS) activeSlot = 3;
        else if (tab == TAB_NOTIFS) {
            activeSlot = 4;
            clearNotificationsBadgeNow();
        }

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

    /** ⚡ 0.0ms Optimistic Friends Badge Zeroing (on Friends icon tap, Requests tab open, Confirm or Delete). */
    public static void clearFriendsBadgeNow() {
        String me = Fb.uid();
        MainActivity inst = activeInstance;
        android.content.Context ctx = inst != null ? inst : App.instance;
        if (inst != null) {
            inst.runOnUiThread(() -> Ui.setBadge(inst.friendsBadge, 0));
        }
        if (me == null || ctx == null) return;
        java.util.Set<String> toMark = new java.util.HashSet<>();
        if (inst != null) {
            synchronized (inst.currentPendingReqIds) {
                toMark.addAll(inst.currentPendingReqIds);
            }
        }
        Prefs.markFriendRequestsSeen(ctx, me, toMark);
        if (!toMark.isEmpty()) {
            com.google.firebase.firestore.WriteBatch batch = Fb.db().batch();
            int ops = 0;
            for (String rid : toMark) {
                if (rid == null || rid.isEmpty()) continue;
                batch.update(Fb.db().collection("friend_requests").document(rid), "seenByReceiver", true);
                if (++ops >= 450) break;
            }
            if (ops > 0) batch.commit();
        }
    }

    /** ⚡ 0.0ms Optimistic Message Badge Zeroing (clears entire count on opening Chat list!). */
    public static void clearMessagesBadgeNow() {
        String me = Fb.uid();
        MainActivity inst = activeInstance;
        android.content.Context ctx = inst != null ? inst : App.instance;
        if (inst != null) {
            inst.runOnUiThread(() -> Ui.setBadge(inst.msgBadge, 0));
        }
        if (me == null || ctx == null) return;
        Prefs.setChatBadgeClearedAt(ctx, me, System.currentTimeMillis());

        java.util.Set<String> roomIds = new java.util.HashSet<>();
        if (inst != null) {
            synchronized (inst.currentUnreadChatIds) {
                roomIds.addAll(inst.currentUnreadChatIds);
                inst.currentUnreadChatIds.clear();
            }
        }
        if (!roomIds.isEmpty()) {
            com.google.firebase.firestore.WriteBatch batch = Fb.db().batch();
            int ops = 0;
            for (String cid : roomIds) {
                if (cid == null || cid.isEmpty()) continue;
                batch.update(Fb.db().collection("chats").document(cid), "unreadCount." + me, 0);
                if (++ops >= 450) break;
            }
            if (ops > 0) batch.commit();
        } else {
            Fb.db().collection("chats").whereArrayContains("participants", me).get()
                    .addOnSuccessListener(snap -> {
                        if (snap == null || snap.isEmpty()) return;
                        com.google.firebase.firestore.WriteBatch batch = Fb.db().batch();
                        int ops = 0;
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                            Object u = d.get("unreadCount");
                            if (u instanceof Map) {
                                Object n = ((Map<?, ?>) u).get(me);
                                if (n instanceof Number && ((Number) n).intValue() > 0) {
                                    batch.update(d.getReference(), "unreadCount." + me, 0);
                                    if (++ops >= 450) break;
                                }
                            }
                        }
                        if (ops > 0) batch.commit();
                    });
        }
    }

    /** ⚡ 0.0ms Optimistic Bell Badge Zeroing + Automatic markAllAsRead! */
    public static void clearNotificationsBadgeNow() {
        String me = Fb.uid();
        MainActivity inst = activeInstance;
        android.content.Context ctx = inst != null ? inst : App.instance;
        if (inst != null) {
            inst.runOnUiThread(() -> {
                Ui.setBadge(inst.notifBadge, 0);
                if (inst.tabFragments[TAB_NOTIFS] instanceof NotificationsFragment) {
                    ((NotificationsFragment) inst.tabFragments[TAB_NOTIFS]).markAllReadOptimisticNow();
                }
            });
        }
        if (me == null || ctx == null) return;
        Prefs.setNotifBadgeClearedAt(ctx, me, System.currentTimeMillis());

        java.util.Set<String> unreadIds = new java.util.HashSet<>();
        if (inst != null) {
            synchronized (inst.currentUnreadNotifIds) {
                unreadIds.addAll(inst.currentUnreadNotifIds);
                inst.currentUnreadNotifIds.clear();
            }
        }
        if (!unreadIds.isEmpty()) {
            com.google.firebase.firestore.WriteBatch batch = Fb.db().batch();
            int ops = 0;
            for (String nid : unreadIds) {
                if (nid == null || nid.isEmpty()) continue;
                batch.update(Fb.db().collection("notifications").document(nid), "isRead", true);
                if (++ops >= 450) break;
            }
            if (ops > 0) batch.commit();
        }
        Fb.db().collection("notifications")
                .whereEqualTo("toUserId", me).whereEqualTo("isRead", false)
                .get().addOnSuccessListener(snap -> {
                    if (snap == null || snap.isEmpty()) return;
                    com.google.firebase.firestore.WriteBatch batch = Fb.db().batch();
                    int ops = 0;
                    for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                        batch.update(d.getReference(), "isRead", true);
                        ops++;
                        if (ops >= 450) {
                            batch.commit();
                            batch = Fb.db().batch();
                            ops = 0;
                        }
                    }
                    if (ops > 0) batch.commit();
                });
    }

    private void watchReferralsForFreeTick() {
        String me = Fb.uid();
        if (me == null) return;
        referralReg = Fb.db().collection("referrals")
                .whereEqualTo("refBy", me)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null) return;
                    com.friendbook.social.core.Admin.autoGrantByReferrals(this, snap.size());
                });
    }

    private void startBadgeListeners() {
        String me = Fb.uid();
        if (me == null) return;
        if (notifReg != null) notifReg.remove();
        notifReg = Fb.db().collection("notifications")
                .whereEqualTo("toUserId", me).whereEqualTo("isRead", false)
                .addSnapshotListener((snap, err) -> {
                    synchronized (currentUnreadNotifIds) {
                        currentUnreadNotifIds.clear();
                        if (snap != null) {
                            for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                                currentUnreadNotifIds.add(d.getId());
                            }
                        }
                    }
                    if (Prefs.quiet(this)) {
                        Ui.setBadge(notifBadge, 0);
                        return;
                    }
                    if (active == TAB_NOTIFS) {
                        Ui.setBadge(notifBadge, 0);
                        if (snap != null && !snap.isEmpty()) {
                            clearNotificationsBadgeNow();
                        }
                        return;
                    }
                    long clearedAt = Prefs.notifBadgeClearedAt(this, me);
                    int unseen = 0;
                    if (snap != null) {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                            long ts = com.friendbook.social.core.Format.ts(d.get("createdAt"));
                            if (clearedAt <= 0L || ts > clearedAt) {
                                unseen++;
                            }
                        }
                    }
                    Ui.setBadge(notifBadge, unseen);
                });

        if (chatReg != null) chatReg.remove();
        chatReg = Fb.db().collection("chats")
                .whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    int total = 0;
                    long chatClearedAt = Prefs.chatBadgeClearedAt(this, me);
                    synchronized (currentUnreadChatIds) {
                        currentUnreadChatIds.clear();
                    }
                    if (snap != null) {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                            // 📞 Detect incoming Voice / Video Calls anywhere in friendbook!
                            com.friendbook.social.chat.CallActivity.maybeHandleIncomingFromRoomDoc(this, d);

                            int roomUnread = 0;
                            Object u = d.get("unreadCount");
                            if (u instanceof Map) {
                                Object n = ((Map<?, ?>) u).get(me);
                                if (n instanceof Number) roomUnread = ((Number) n).intValue();
                            }
                            if (roomUnread > 0) {
                                synchronized (currentUnreadChatIds) {
                                    currentUnreadChatIds.add(d.getId());
                                }
                                long msgTs = com.friendbook.social.core.Format.ts(d.get("lastMessageTime"));
                                Object msRaw = d.get("lastMessageTimeMs");
                                if (msRaw instanceof Number) {
                                    msgTs = Math.max(msgTs, ((Number) msRaw).longValue());
                                }
                                if (!Prefs.quiet(this) && (chatClearedAt <= 0L || msgTs > chatClearedAt)) {
                                    total += roomUnread;
                                }
                            }
                        }
                    }
                    Ui.setBadge(msgBadge, total);
                });

        if (reqReg != null) reqReg.remove();
        reqReg = Fb.db().collection("friend_requests")
                .whereEqualTo("toUid", me).whereEqualTo("status", "pending")
                .addSnapshotListener((snap, err) -> {
                    synchronized (currentPendingReqIds) {
                        currentPendingReqIds.clear();
                        if (snap != null) {
                            for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                                currentPendingReqIds.add(d.getId());
                            }
                        }
                    }
                    if (Prefs.quiet(this)) {
                        Ui.setBadge(friendsBadge, 0);
                        return;
                    }
                    if (active == TAB_FRIENDS) {
                        Ui.setBadge(friendsBadge, 0);
                        if (snap != null && !snap.isEmpty()) {
                            java.util.Set<String> seenIds = Prefs.seenFriendReqIds(this, me);
                            boolean hasUnseen = false;
                            for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                                if (!seenIds.contains(d.getId()) && !Boolean.TRUE.equals(d.getBoolean("seenByReceiver"))) {
                                    hasUnseen = true;
                                    break;
                                }
                            }
                            if (hasUnseen) clearFriendsBadgeNow();
                        }
                        return;
                    }
                    java.util.Set<String> seenIds = Prefs.seenFriendReqIds(this, me);
                    long clearedAt = Prefs.friendRequestsClearedAt(this, me);
                    int count = 0;
                    if (snap != null) {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                            if (seenIds.contains(d.getId())) continue;
                            if (Boolean.TRUE.equals(d.getBoolean("seenByReceiver"))) continue;
                            long ts = com.friendbook.social.core.Format.ts(d.get("createdAt"));
                            if (clearedAt > 0L && ts > 0L && ts <= clearedAt) continue;
                            count++;
                        }
                    }
                    Ui.setBadge(friendsBadge, count);
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
        FeedAlgorithm.onAppBackgrounded(this);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean freshSession = FeedAlgorithm.onAppResumed(this);
        if (freshSession) {
            if (tabFragments[TAB_HOME] instanceof HomeFragment) {
                ((HomeFragment) tabFragments[TAB_HOME]).onWarmSessionResume();
            }
            if (tabFragments[TAB_REELS] instanceof com.friendbook.social.reels.ReelsFragment && active != TAB_REELS) {
                ((com.friendbook.social.reels.ReelsFragment) tabFragments[TAB_REELS]).refreshWithAlgorithm();
            }
        }
        refreshLiteUi();
        Fb.ensureMeFresh();
        Presence.init();
    }

    @Override
    protected void onDestroy() {
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
        if (referralReg != null) referralReg.remove();
        if (notifReg != null) notifReg.remove();
        if (chatReg != null) chatReg.remove();
        if (reqReg != null) reqReg.remove();
        App.instance.setShakeListener(null);
    }
}
