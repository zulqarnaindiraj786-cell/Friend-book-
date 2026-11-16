package com.friendbook.social.main;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Intent;
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
import com.friendbook.social.core.QuotaGuard;
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
    private TextView homeBadge, msgBadge, notifBadge, friendsBadge, reelsBadge;
    private int active = -1;
    private ListenerRegistration notifReg, chatReg, reqReg, referralReg;

    private final java.util.Set<String> currentPendingReqIds = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Re-checked here as well as in the launcher: a process that was left in the
        // background resumes straight into this activity, skipping SplashActivity entirely.
        com.friendbook.social.core.ForceUpdate.recheck(this);
        com.friendbook.social.core.AppUpdates.check(this,false);
        if (!Fb.signedIn()) { finish(); return; }
        activeInstance = this;
        // Advance friendbook discovery epoch on every app open so user greets fresh posts, videos & ads!
        FeedAlgorithm.onColdAppLaunch(this);
        Ui.statusBarLight(this);
        setContentView(R.layout.activity_main);

        LinearLayout root = findViewById(R.id.mainContentRoot);
        root.setBackgroundColor(Ui.bg(this));

        // 1. Crisp White Brand & Circular Action Header: friendbook   [ ＋ ] [ 🔍 ] [ ☰ ]
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
            } else if (s == 3) {
                reelsBadge = Ui.badge(this);
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                fp.topMargin = Ui.dp(this, 3);
                iconWrap.addView(reelsBadge, fp);
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

        // 6. Gather UMP GDPR Consent & Initialize Google Mobile Ads SDK — preload ads via new Queue Manager on app open!
        // AdQueueManager is initialized in Application.onCreate() via AdsConfig.warmUpPools()
        ConsentManager.gatherConsentAndInitAds(this, () -> {
            if (tabFragments[TAB_HOME] instanceof HomeFragment) {
                ((HomeFragment) tabFragments[TAB_HOME]).onLiteModeChanged();
            }
        });

        // ⚡ Defer non-critical SDK/network listeners until AFTER Frame #1 paints!
        Looper.myQueue().addIdleHandler(() -> {
            Fb.ensureMeFresh();
            Presence.init();
            startBadgeListeners();
            watchReferralsForFreeTick();
            AdsConfig.startWatching();
            notificationPermissionHandler.postDelayed(
                    () -> com.friendbook.social.reels.ReelVideoCache.warmStartupReels(getApplicationContext()),
                    2000L);
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
                clearReelsBadgeNow();
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

    /**
     * Switches directly to the Reels tab when the user taps the Featured Reels card on the Home Feed.
     * Passes the featured reel's full data as arguments so the ReelsFragment can show it immediately on first load. */
    public void openReelsTabFromFeed(com.friendbook.social.models.Models.Reel featuredReel) {
        clearReelsBadgeNow();
        if (featuredReel != null && featuredReel.id != null && !featuredReel.id.isEmpty()
                && featuredReel.videoUrl != null && !featuredReel.videoUrl.isEmpty()) {
            select(TAB_REELS, featuredReel);
        } else {
            select(TAB_REELS);
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
            dataStripWrap.setVisibility(View.GONE);
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
        // New queue-based system: ads are preloaded and maintained automatically
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
        } else if (tab == TAB_REELS) {
            activeSlot = 3;
            clearReelsBadgeNow();
        } else if (tab == TAB_NOTIFS) {
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

    /**
     * Overload for Reels tab: accepts a featured Reel to pre-load immediately
     * (used when opening from Home Feed Featured Reels card). If fragment is new, creates it
     * with ALL reel data as arguments and ADDS it to container immediately (not just show).
     * If fragment already exists, calls focusFeaturedReel() to insert the reel at top and scroll. */
    public void select(int tab, com.friendbook.social.models.Models.Reel featuredReel) {
        if (tab == TAB_REELS && featuredReel != null
                && featuredReel.id != null && !featuredReel.id.isEmpty()
                && featuredReel.videoUrl != null && !featuredReel.videoUrl.isEmpty()) {
            Fragment existing = tabFragments[TAB_REELS];
            if (existing == null) {
                // First time: create fragment with ALL reel data as arguments AND add to container NOW
                com.friendbook.social.reels.ReelsFragment f = new com.friendbook.social.reels.ReelsFragment();
                Bundle args = new Bundle();
                args.putString("initial_reel_id", featuredReel.id);
                args.putString("video_url", featuredReel.videoUrl);
                args.putBoolean("is_post", featuredReel.isFromPost);
                args.putString("author_id", featuredReel.authorId);
                args.putString("author_name", featuredReel.authorName);
                args.putString("author_photo", featuredReel.authorPhoto);
                args.putString("caption", featuredReel.caption);
                args.putInt("likes", featuredReel.likes);
                args.putInt("comments", featuredReel.comments);
                args.putInt("shares", featuredReel.shares);
                f.setArguments(args);
                tabFragments[TAB_REELS] = f;

                // Do the fragment transaction HERE with ft.add() - not ft.show()!
                // This ensures the fragment with bundle args is properly attached.
                FragmentTransaction ft = getFragmentManager().beginTransaction();
                if (active >= 0 && tabFragments[active] != null) {
                    ft.hide(tabFragments[active]);
                }
                ft.add(container.getId(), f, "tab_" + TAB_REELS);
                ft.commitAllowingStateLoss();
                active = TAB_REELS;

                // Update nav highlight
                int activeSlot = 3;
                for (int s = 0; s < 6; s++) {
                    boolean on = s == activeSlot;
                    slotIndicators[s].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
                    Icons.setTint(slotIcons[s], on ? Icons.BLUE : Icons.GRAY);
                }
                if (menuBtnCircle != null && menuBtnIcon != null) {
                    menuBtnCircle.setBackground(Ui.circle(Ui.inputBg(this)));
                    Icons.setTint(menuBtnIcon, Ui.txt(this));
                }
                clearReelsBadgeNow();
                return; // Done - fragment added with bundle args
            } else if (existing instanceof com.friendbook.social.reels.ReelsFragment) {
                // Fragment already exists: insert featured reel at top and scroll to it
                ((com.friendbook.social.reels.ReelsFragment) existing).focusFeaturedReel(featuredReel);
            }
            // The Reels tab is already on screen with the tapped reel pinned at the top —
            // never fall through to select(tab), which re-shows the tab and wipes the reel.
            return;
        }
        select(tab); // Normal path for existing fragment or non-Reels tabs
    }

    /** Dismiss ONLY the outer friend-request badge; pending requests and their seen flags stay untouched. */
    public static void clearFriendsBadgeNow() {
        String me = Fb.uid();
        MainActivity inst = activeInstance;
        android.content.Context ctx = inst != null ? inst : App.instance;
        if (inst != null) inst.runOnUiThread(() -> Ui.setBadge(inst.friendsBadge, 0));
        if (me == null || ctx == null) return;
        java.util.Set<String> badgeIds = new java.util.HashSet<>();
        if (inst != null) synchronized (inst.currentPendingReqIds) { badgeIds.addAll(inst.currentPendingReqIds); }
        Prefs.dismissFriendRequestBadge(ctx, me, badgeIds);
    }

    /** Dismiss ONLY the top Messages badge. Inbox unread counts and message receipts are untouched. */
    public static void clearMessagesBadgeNow() {
        String me = Fb.uid();
        MainActivity inst = activeInstance;
        android.content.Context ctx = inst != null ? inst : App.instance;
        if (inst != null) {
            inst.runOnUiThread(() -> Ui.setBadge(inst.msgBadge, 0));
        }
        if (me == null || ctx == null) return;
        // Device/account-scoped badge acknowledgement, NOT a conversation read operation.
        // ChatActivity remains responsible for marking only the conversation actually opened.
        Prefs.setChatBadgeClearedAt(ctx, me, System.currentTimeMillis());
    }

    /** Dismiss ONLY the outer bell badge. Notifications remain unread until individually opened. */
    public static void clearNotificationsBadgeNow() {
        String me = Fb.uid();
        MainActivity inst = activeInstance;
        android.content.Context ctx = inst != null ? inst : App.instance;
        if (inst != null) inst.runOnUiThread(() -> Ui.setBadge(inst.notifBadge, 0));
        if (me == null || ctx == null) return;
        Prefs.setNotifBadgeClearedAt(ctx, me, System.currentTimeMillis());
    }

    /** Dismiss ONLY the top Video/Reels badge. */
    public static void clearReelsBadgeNow() {
        String me = Fb.uid();
        MainActivity inst = activeInstance;
        android.content.Context ctx = inst != null ? inst : App.instance;
        if (inst != null) inst.runOnUiThread(() -> Ui.setBadge(inst.reelsBadge, 0));
        if (me == null || ctx == null) return;
        Prefs.setReelsBadgeClearedAt(ctx, me, System.currentTimeMillis());
    }

    private void watchReferralsForFreeTick() {
        String me = Fb.uid();
        if (me == null) return;
        if(referralReg!=null)referralReg.remove();
        referralReg=com.friendbook.social.core.CountReads.once(
                com.friendbook.social.core.DocQuery.of("referrals").whereEqualTo("refBy",me).build(),total->
                com.friendbook.social.core.Admin.autoGrantByReferrals(this,(int)Math.min(Integer.MAX_VALUE,total)));
    }

    /** True while this Activity has told the user the backend is unavailable. */
    private boolean backendNoticeShown;

    /**
     * Tell the user once, not sixty times, that the app is in a degraded state.
     *
     * <p>Without this, a spent quota silently empties every list in the app and users read that
     * as data loss. The message is deliberately reassuring and only appears when the failure is
     * a refusal (quota/unavailable), never for a genuinely empty result.
     */
    private void noteBackendDegraded() {
        if (backendNoticeShown || isFinishing()) return;
        backendNoticeShown = true;
        Ui.toast(this, com.friendbook.social.core.QuotaGuard.userMessage(this));
        // Re-arm once the backend answers again, so a later outage still shows a message.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!com.friendbook.social.core.QuotaGuard.degraded()) backendNoticeShown = false;
        }, 5 * 60_000L);
    }

    /**
     * Upper bound on chats considered for the unread/call badge.
     *
     * <p>Deliberately generous: it only exists so the per-change cost of the chats listener
     * cannot grow without limit. 100 is far above what any current user has, so it changes
     * nothing today while capping the pathological case.
     */
    private static final int CHAT_BADGE_LIMIT = 100;

    private void startBadgeListeners() {
        String me = Fb.uid();
        if (me == null) return;
        if (notifReg != null) notifReg.remove();
        // A badge is a number, so it is counted, not downloaded. Fetching a page to take its
        // length reads every matching document and reports the page ceiling once the real
        // number is larger; COUNT answers the same question from one row. CloudflareStore
        // caches the answer for a minute, so repeated foregrounds cost nothing, and the badge
        // still refreshes on foreground, on leaving the notification screen, and after any
        // action that changes it.
        final long clearedAt = Prefs.notifBadgeClearedAt(this, me);
        com.friendbook.social.core.DocQuery.Builder unseenQuery =
                com.friendbook.social.core.DocQuery.of("notifications")
                        .whereEqualTo("toUserId", me).whereEqualTo("isRead", false);
        // "Unseen since I last cleared the badge" is a filter, so it belongs in the query
        // rather than in a page of results that the count would otherwise include.
        if (clearedAt > 0L) {
            unseenQuery = unseenQuery.whereGreaterThan("createdAt", clearedAt);
        }
        com.friendbook.social.core.CloudflareStore.count(unseenQuery.build(), total -> {
            if (Prefs.quiet(this)) {
                Ui.setBadge(notifBadge, 0);
                return;
            }
            if (active == TAB_NOTIFS) {
                Ui.setBadge(notifBadge, 0);
                if (total > 0) {
                    clearNotificationsBadgeNow();
                }
                return;
            }
            Ui.setBadge(notifBadge, (int) Math.min(Integer.MAX_VALUE, total));
        }, () -> {
            // A refusal is not zero. Leaving the badge at its last value is the honest
            // outcome; setting it to zero would tell the user they have nothing waiting.
        });

        if (chatReg != null) chatReg.remove();
        // The badge is a number, so it is counted. Fetching a page of chats to take its length
        // read every chat the user is in - which is how ~200 active users exhausted a 50,000
        // reads/day plan - and reported the page ceiling rather than the real total once the
        // user had more chats than the cap.
        //
        // The fetch is still needed for one thing a count cannot answer: whether any chat is
        // ringing. It is bounded and asks only for the field that matters.
        com.friendbook.social.core.D1Snap.read(
                com.friendbook.social.core.DocQuery.of("chats")
                        .whereArrayContains("participants", me)
                        .whereExists("activeCall")
                        .limit(CHAT_BADGE_LIMIT).build(), snap -> {
                    if (snap != null) {
                        for (com.friendbook.social.core.Doc d : snap) {
                            // 📞 A ringing call must be picked up wherever the user is, so this
                            // check cannot move to the count.
                            com.friendbook.social.chat.CallActivity.maybeHandleIncomingFromRoomDoc(this, d);
                        }
                    }
                });

        // Bounded. A chat with an unread message and an old lastMessageTime is a corner case
        // (the two fields are written together), and sampling it is still cheaper than reading
        // every chat on every foreground.
        final long chatClearedAt = Prefs.chatBadgeClearedAt(this, me);
        com.friendbook.social.core.DocQuery.Builder unreadChats =
                com.friendbook.social.core.DocQuery.of("chats")
                        .whereArrayContains("participants", me)
                        .whereGreaterThan("unreadCount." + me, 0);
        if (chatClearedAt > 0L) {
            unreadChats = unreadChats.whereGreaterThan("lastMessageTimeMs", chatClearedAt);
        }
        com.friendbook.social.core.CloudflareStore.count(unreadChats.build(), total -> {
            QuotaGuard.noteSuccess();
            if (Prefs.quiet(this) || active == TAB_MESSAGES) {
                Ui.setBadge(msgBadge, 0);
                return;
            }
            Ui.setBadge(msgBadge, (int) Math.min(Integer.MAX_VALUE, total));
        }, () -> {
            // A refusal is not an empty inbox: the badge keeps its last value rather than
            // dropping to zero, which would read as "you have nothing waiting".
        });

        if (reqReg != null) reqReg.remove();
        com.friendbook.social.core.D1Snap.read(com.friendbook.social.core.DocQuery.of("friend_requests").whereEqualTo("toUid",me).whereEqualTo("status","pending").limit(50).build(),snap->{
                    synchronized (currentPendingReqIds) {
                        currentPendingReqIds.clear();
                        if (snap != null) {
                            for (com.friendbook.social.core.Doc d : snap) {
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
                            java.util.Set<String> seenIds = Prefs.dismissedFriendReqBadgeIds(this, me);
                            boolean hasUnseen = false;
                            for (com.friendbook.social.core.Doc d : snap) {
                                if (!seenIds.contains(d.getId())) {
                                    hasUnseen = true;
                                    break;
                                }
                            }
                            if (hasUnseen) clearFriendsBadgeNow();
                        }
                        return;
                    }
                    java.util.Set<String> seenIds = Prefs.dismissedFriendReqBadgeIds(this, me);
                    long clearedAt = Prefs.friendRequestsClearedAt(this, me);
                    int count = 0;
                    if (snap != null) {
                        for (com.friendbook.social.core.Doc d : snap) {
                            if (seenIds.contains(d.getId())) continue;
                            long ts = com.friendbook.social.core.Format.ts(d.get("createdAt"));
                            if (clearedAt > 0L && ts > 0L && ts <= clearedAt) continue;
                            count++;
                        }
                    }
                    Ui.setBadge(friendsBadge, count);
                });

        // Video / Reels badge (capped at 15+ via Ui.setBadge)
        com.friendbook.social.core.D1Snap.read(
                com.friendbook.social.core.DocQuery.of("reels")
                        .orderBy("createdAt", com.friendbook.social.core.DocQuery.DESC)
                        .limit(16).build(), snap -> {
                    if (Prefs.quiet(this) || active == TAB_REELS) {
                        Ui.setBadge(reelsBadge, 0);
                        return;
                    }
                    long clearedAt = Prefs.reelsBadgeClearedAt(this, me);
                    int unseen = 0;
                    {
                        for (com.friendbook.social.core.Doc d : snap) {
                            long ts = com.friendbook.social.core.Format.ts(d.get("createdAt"));
                            if (clearedAt <= 0L || ts <= 0L || ts > clearedAt) {
                                unseen++;
                            }
                        }
                    }
                    Ui.setBadge(reelsBadge, unseen);
                });
    }

    private final android.os.Handler notificationPermissionHandler =
            new android.os.Handler(Looper.getMainLooper());
    private boolean permissionHostResumed;
    private final Runnable notificationPermissionPrompt = () ->
            com.friendbook.social.push.NotificationPermissionPrompt.requestOnce(this);

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        com.friendbook.social.push.NotificationPermissionPrompt.onResult(this, requestCode, results);
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
        permissionHostResumed = false;
        notificationPermissionHandler.removeCallbacks(notificationPermissionPrompt);
        FeedAlgorithm.onAppBackgrounded(this);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        permissionHostResumed = true;
        boolean freshSession = FeedAlgorithm.onAppResumed(this);
        if (freshSession) {
            if (tabFragments[TAB_HOME] instanceof HomeFragment) {
                ((HomeFragment) tabFragments[TAB_HOME]).onWarmSessionResume();
            }
        }
        if (getIntent().getBooleanExtra("push_friends", false)) {
            getIntent().removeExtra("push_friends");
            select(TAB_FRIENDS);
        }
        com.friendbook.social.push.PushManager.sync(this);
        com.friendbook.social.push.PushOpenActivity.resumePending(this);
        refreshLiteUi();
        Fb.ensureMeFresh();
        Presence.init();
        // Delay until Main is visible; never show a permission dialog from background/PIN/login.
        notificationPermissionHandler.removeCallbacks(notificationPermissionPrompt);
        notificationPermissionHandler.postDelayed(notificationPermissionPrompt, 2000L);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        notificationPermissionHandler.removeCallbacks(notificationPermissionPrompt);
        if (hasFocus && permissionHostResumed) {
            notificationPermissionHandler.postDelayed(notificationPermissionPrompt, 2000L);
        }
    }

    @Override
    protected void onDestroy() {
        notificationPermissionHandler.removeCallbacks(notificationPermissionPrompt);
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
        if (referralReg != null) referralReg.remove();
        if (notifReg != null) notifReg.remove();
        if (chatReg != null) chatReg.remove();
        if (reqReg != null) reqReg.remove();
        App.instance.setShakeListener(null);
    }
}
