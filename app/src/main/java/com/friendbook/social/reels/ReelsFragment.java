package com.friendbook.social.reels;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.friendbook.social.core.AnchoredBannerPool;
import com.friendbook.social.core.ConsentManager;
import com.friendbook.social.core.ContentSafetyEngine;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.FeedAlgorithm;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.TickCache;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.FullscreenMediaActivity;
import com.friendbook.social.post.Reactions;
import com.friendbook.social.profile.ProfileActivity;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.ListenerRegistration;
import com.friendbook.social.d1.documents.Query;
import com.friendbook.social.d1.documents.SetOptions;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⚡ friendbook Ultra-Fast Pro Reels Player:
 * - Hardware TextureView + MediaPlayer + Persistent 240 MB SSD Cache (ReelVideoCache)
 * - Instant 0.0ms first-frame JPEG poster thumbnail (zero black screen on swipe!)
 * - Only the selected Reel streams; next-holder preparation uses complete local files only.
 * - Non-destructive snapshot updates so Likes/Comments NEVER interrupt video playback
 * - Crystal-clear high-visibility "＋ Upload Reel" button in top bar AND right action rail
 * - Live background upload status pill + instant optimistic playback of user's own uploaded Reel
 */
public class ReelsFragment extends Fragment {

    private ViewPager2 pager;
    private ReelAdapter adapter;
    private ListenerRegistration reelsReg, videoPostsReg;
    private DocumentSnapshot reelsCursor, postsCursor;
    private boolean pagingVideos, moreReels=true, moreVideoPosts=true;
    /** True when a load attempt ended with nothing because the NETWORK failed, not because empty. */
    private boolean reelsLoadFailed;
    /** Bounded automatic retries: an outage must end in a visible Retry state, not a retry storm. */
    private int loadAttempts;
    /** A load that neither succeeds nor fails (black-hole network) must not strand the spinner. */
    private final android.os.Handler reelsWatchdog = new android.os.Handler(android.os.Looper.getMainLooper());
    private int watchdogGen;
    private int videoGeneration;
    private final java.util.Set<String> loadedVideoIds=new java.util.HashSet<>();
    private LinearLayout emptyState;
    private LinearLayout loadErrorState;
    private LinearLayout initialLoadingBox;
    private LinearLayout uploadStatusBanner;
    private TextView uploadStatusText;
    private ProgressBar uploadStatusBar;
    private final List<Models.Reel> fromReels = new ArrayList<>();
    private final List<Models.Reel> fromPosts = new ArrayList<>();
    /** Locks Reel ordering during active viewing so Like/Reactions/Comments NEVER jump videos up or down! */
    private final List<String> sessionReelOrderIds = new ArrayList<>();
    private String activeReelUid = "";
    /** Protects fresh local Reel reaction taps strictly per `(currentUserId, reelId)` from snapshot overwrites. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LOCAL_REEL_TAP_TS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private String initialReelId;
    private boolean followingOnlyMode = false;
    private TextView forYouPill, followingPill;

    static boolean isReelReactionInFlight(String reelId) {
        String uid = Fb.uid();
        if (uid == null || uid.isEmpty() || reelId == null || reelId.isEmpty()) return false;
        Long ts = LOCAL_REEL_TAP_TS.get(uid + ":" + reelId);
        return ts != null && (System.currentTimeMillis() - ts) < 4500L;
    }

    static void markReelReactionTap(String reelId) {
        String uid = Fb.uid();
        if (uid != null && !uid.isEmpty() && reelId != null && !reelId.isEmpty()) {
            LOCAL_REEL_TAP_TS.put(uid + ":" + reelId, System.currentTimeMillis());
        }
    }

    /** Returns ONLY `currentUserId`'s reaction on this Reel, or `null` if `currentUserId` has not reacted. */
    static String resolveMyReelReaction(Models.Reel reel) {
        String me = Fb.uid();
        if (me == null || me.isEmpty() || reel == null) return null;
        if(com.friendbook.social.core.D1Client.enabled(reel.isFromPost?"posts":"reels"))return com.friendbook.social.post.D1Reactions.mine(reel.isFromPost?"posts":"reels",reel.id);
        String myRx = reel.reactions != null ? reel.reactions.get(me) : null;
        if (myRx != null && !myRx.isEmpty()) {
            return Models.normalizeReactionType(myRx, "love");
        }
        if (!reel.isFromPost && reel.likedBy != null && reel.likedBy.contains(me)) {
            return "love";
        }
        return null;
    }

    private final ReelUploadManager.Listener bgUploadListener =
            (active, percent, sentBytes, totalBytes, message, succeeded, error) -> {
                if (getActivity() == null) return;
                if (uploadStatusBanner != null) {
                    if (active) {
                        uploadStatusBanner.setVisibility(View.VISIBLE);
                        if (uploadStatusBar != null) uploadStatusBar.setProgress(Math.max(3, percent));
                        if (uploadStatusText != null) {
                            uploadStatusText.setText((message != null && !message.isEmpty()
                                    ? message : ("Uploading Reel… " + percent + "%")));
                        }
                        rebuildMergedFeed(false);
                    } else {
                        uploadStatusBanner.setVisibility(View.GONE);
                        if (succeeded) {
                            rebuildMergedFeed(true);
                            if (pager != null) pager.setCurrentItem(0, false);
                        } else if (error != null && !error.isEmpty()) {
                            rebuildMergedFeed(true);
                            Ui.toast(getActivity(), "Reel upload failed: " + error);
                        }
                    }
                }
            };

    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup c, Bundle b) {
        Activity a = getActivity();
        if (getArguments() != null) {
            initialReelId = getArguments().getString("initial_reel_id");
        }
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);

        pager = new ViewPager2(a);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        // Pre-layout & pre-prepare 2 offscreen pages above/below for instant zero-lag swipes & prebound video!
        pager.setOffscreenPageLimit(1);
        if (pager.getChildAt(0) instanceof RecyclerView) {
            ((RecyclerView) pager.getChildAt(0)).setItemAnimator(null);
        }
        adapter = new ReelAdapter(a,()->isResumed() && !isHidden());
        pager.setAdapter(adapter);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        // Empty-state prompt with unmistakable Upload Reel button
        emptyState = Ui.col(a);
        emptyState.setGravity(Gravity.CENTER);
        Ui.pad(emptyState, 28, 28, 28, 28);
        emptyState.setVisibility(View.GONE);
        ImageView ei = com.friendbook.social.core.StudioVectorIcons.iconView(a, "play", 62, 0x66FFFFFF);
        emptyState.addView(ei, new LinearLayout.LayoutParams(Ui.dp(a, 68), Ui.dp(a, 68)));
        TextView et1 = Ui.tvBold(a, "No Reels yet — upload the first one!", 19, Color.WHITE);
        et1.setGravity(Gravity.CENTER);
        et1.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
        emptyState.addView(et1);
        TextView et2 = Ui.tv(a, "Share short vertical videos with instant playback, reactions, comments & shares!", 13.5f, 0xBBFFFFFF);
        et2.setGravity(Gravity.CENTER);
        emptyState.addView(et2);
        TextView createNow = com.friendbook.social.core.StudioVectorIcons.iconChip(
                a, "camera", "Upload Reel", 15f, 0xFFE41E3F, Color.WHITE, Color.WHITE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = Ui.dp(a, 18);
        createNow.setOnClickListener(v -> startActivity(new Intent(a, AddReelActivity.class)));
        emptyState.addView(createNow, clp);
        root.addView(emptyState, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // Network-failure state: an endless spinner is the worst answer a slow network can get.
        loadErrorState = Ui.col(a);
        loadErrorState.setGravity(Gravity.CENTER);
        Ui.pad(loadErrorState, 28, 28, 28, 28);
        loadErrorState.setVisibility(View.GONE);
        ImageView ni = com.friendbook.social.core.StudioVectorIcons.iconView(a, "globe", 62, 0x66FFFFFF);
        loadErrorState.addView(ni, new LinearLayout.LayoutParams(Ui.dp(a, 68), Ui.dp(a, 68)));
        TextView nt1 = Ui.tvBold(a, "Couldn't load Reels", 19, Color.WHITE);
        nt1.setGravity(Gravity.CENTER);
        nt1.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
        loadErrorState.addView(nt1, new LinearLayout.LayoutParams(-2, -2));
        TextView nt2 = Ui.tv(a, "The network did not answer. Your Reels are safe — try again.", 13.5f, 0xBBFFFFFF);
        nt2.setGravity(Gravity.CENTER);
        loadErrorState.addView(nt2, new LinearLayout.LayoutParams(-2, -2));
        TextView retryNow = com.friendbook.social.core.StudioVectorIcons.iconChip(
                a, "play", "Retry", 15f, Ui.BLUE, Color.WHITE, Color.WHITE);
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, -2);
        retryLp.topMargin = Ui.dp(a, 18);
        retryNow.setOnClickListener(v -> { reelsLoadFailed = false; resetVideoPages(); });
        loadErrorState.addView(retryNow, retryLp);
        root.addView(loadErrorState, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // Initial Loading Indicator (so opening Reels NEVER shows a blank black screen!)
        initialLoadingBox = Ui.col(a);
        initialLoadingBox.setGravity(Gravity.CENTER);
        Ui.pad(initialLoadingBox, 24, 24, 24, 24);
        ProgressBar initSpin = new ProgressBar(a);
        initialLoadingBox.addView(initSpin, new LinearLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 42)));
        TextView initTxt = Ui.tvBold(a, "Loading Videos…", 14.5f, 0xEEFFFFFF);
        initTxt.setPadding(0, Ui.dp(a, 10), 0, 0);
        initTxt.setGravity(Gravity.CENTER);
        // Explicit WRAP_CONTENT: inside this wrap-content box the LinearLayout default (MATCH_PARENT
        // width) measured the text against nothing and visibly clipped it to "Loadi".
        initialLoadingBox.addView(initTxt, new LinearLayout.LayoutParams(-2, -2));
        root.addView(initialLoadingBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // Top Overlay Stack (Header + Live Background Upload Progress Pill)
        LinearLayout topStack = Ui.col(a);
        topStack.setElevation(Ui.dp(a, 28));

        LinearLayout bar = Ui.row(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x59000000);
        boolean showBack = getArguments() != null && getArguments().getBoolean("show_back", false);
        Ui.pad(bar, showBack ? 6 : 12, showBack ? 28 : 10, 12, 10);

        if (showBack) {
            ImageView backBtn = Ui.backArrow(a, Color.WHITE,
                    v -> { if (getActivity() != null) getActivity().finish(); });
            bar.addView(backBtn);
        }

        TextView title = Ui.tvBold(a, "Reels", 19, Color.WHITE);
        title.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        bar.addView(title);

        forYouPill = Ui.roundChip(a, "For You", Ui.BLUE, Color.WHITE, 11.5f);
        Ui.pad(forYouPill, 9, 5, 9, 5);
        LinearLayout.LayoutParams fyp = new LinearLayout.LayoutParams(-2, -2);
        fyp.leftMargin = Ui.dp(a, 7);
        forYouPill.setOnClickListener(v -> {
            followingOnlyMode = false;
            updateFilterPills(a);
            rebuildMergedFeed(true);
        });
        bar.addView(forYouPill, fyp);

        followingPill = Ui.roundChip(a, "Following", 0x66000000, Color.WHITE, 11.5f);
        Ui.pad(followingPill, 9, 5, 9, 5);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
        flp.leftMargin = Ui.dp(a, 4);
        followingPill.setOnClickListener(v -> {
            followingOnlyMode = true;
            updateFilterPills(a);
            rebuildMergedFeed(true);
        });
        bar.addView(followingPill, flp);

        TextView refreshPill = com.friendbook.social.core.StudioVectorIcons.circleIconButton(
                a, "flip", 30, 0x66000000, Color.WHITE, null);
        int rSz = Ui.dp(a, 30);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(rSz, rSz);
        rlp.leftMargin = Ui.dp(a, 5);
        refreshPill.setOnClickListener(v -> {
            refreshWithAlgorithm();
            Ui.toast(a, "Fresh reels loaded");
        });
        bar.addView(refreshPill, rlp);

        bar.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));

        // ✨ UNMISTAKABLE HIGH-CONTRAST UPLOAD REEL BUTTON IN TOP-RIGHT!
        TextView uploadReelPill = Ui.tvBold(a, "＋ Upload Reel", 12.5f, Color.WHITE);
        uploadReelPill.setSingleLine(true);
        uploadReelPill.setBackground(Ui.roundStroke(a, 0xFFE41E3F, 18, 0xCCFFFFFF, 1));
        Ui.pad(uploadReelPill, 13, 7, 13, 7);
        uploadReelPill.setOnClickListener(v -> startActivity(new Intent(a, AddReelActivity.class)));
        bar.addView(uploadReelPill);
        topStack.addView(bar);

        // Live Background Upload Banner (visible whenever ReelUploadManager is uploading)
        uploadStatusBanner = Ui.col(a);
        uploadStatusBanner.setBackground(Ui.roundStroke(a, 0xEE111827, 12, Ui.BLUE, 1));
        Ui.pad(uploadStatusBanner, 12, 7, 12, 8);
        uploadStatusBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams ublp = new LinearLayout.LayoutParams(-1, -2);
        ublp.setMargins(Ui.dp(a, 12), Ui.dp(a, 6), Ui.dp(a, 12), 0);
        uploadStatusText = Ui.tvBold(a, "Uploading Reel…", 12f, Color.WHITE);
        uploadStatusBanner.addView(uploadStatusText);
        uploadStatusBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        uploadStatusBar.setMax(100);
        LinearLayout.LayoutParams usblp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 5));
        usblp.topMargin = Ui.dp(a, 4);
        uploadStatusBanner.addView(uploadStatusBar, usblp);
        topStack.addView(uploadStatusBanner, ublp);

        TextView moreVideos=Ui.tvBold(a,"More videos ›",12,Color.WHITE);
        moreVideos.setPadding(Ui.dp(a,12),Ui.dp(a,6),Ui.dp(a,12),Ui.dp(a,6));
        moreVideos.setOnClickListener(v -> loadNextVideos()); topStack.addView(moreVideos);
        root.addView(topStack, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrollStateChanged(int state) {
                if (adapter == null || pager == null) return;
                if (state == ViewPager2.SCROLL_STATE_IDLE) adapter.playOnly(pager,pager.getCurrentItem());
                else adapter.pauseAll(pager);
            }

            @Override
            public void onPageSelected(int position) {
                updateReelBanner();
                if(pager.getScrollState()==ViewPager2.SCROLL_STATE_IDLE) adapter.playOnly(pager, position);
                Models.Reel cur = adapter.reelAtPosition(position);
                if (cur != null && cur.id != null) {
                    FeedAlgorithm.recordVideoImpression(getActivity(), cur.id);
                }
                int rIdx = adapter.reelIndexForPos(position);
                // More videos is explicit demand. A selection/bind, including the
                // initial page callback, must not prefetch another network page.
                if(cur!=null && rIdx>=adapter.rawReels().size()-3 && !moreReels && !moreVideoPosts)
                    appendEndlessCycleIfNeeded();
            }
        });
        // Banner is outside the pager so it cannot cover the right-side controls.
        LinearLayout screen = Ui.col(a);
        screen.setBackgroundColor(Color.BLACK);
        screen.addView(root, new LinearLayout.LayoutParams(-1, 0, 1f));
        reelBannerSlot = new FrameLayout(a);
        reelBannerSlot.setVisibility(View.GONE);
        screen.addView(reelBannerSlot, new LinearLayout.LayoutParams(-1, -2));
        reelBanners = new AnchoredBannerPool(a, AnchoredBannerPool.Placement.REELS);
        return screen;
    }

    /** Advances the friendbook discovery epoch so new/unwatched Reels & fresh Sponsored Ads appear immediately! */
    public void refreshWithAlgorithm() {
        Activity a = getActivity();
        if (a == null) return;
        initialReelId = null;
        FeedAlgorithm.nextRefreshGeneration(a);
        resetVideoPages();
        if (pager != null) pager.setCurrentItem(0, false);
    }

    private void updateFilterPills(Context a) {
        if (forYouPill == null || followingPill == null) return;
        forYouPill.setBackground(Ui.round(a, !followingOnlyMode ? Ui.BLUE : 0x66000000, 16));
        followingPill.setBackground(Ui.round(a, followingOnlyMode ? Ui.BLUE : 0x66000000, 16));
    }

    private AnchoredBannerPool reelBanners;
    private FrameLayout reelBannerSlot;

    private void updateReelBanner() {
        if (reelBanners == null || pager == null || adapter == null) return;
        boolean visible = isResumed() && !isHidden();
        reelBanners.setActive(visible);
        if (!visible) return;
        int position = pager.getCurrentItem();
        Models.Reel reel = adapter.reelAtPosition(position);
        reelBanners.show(reelBannerSlot, reel == null ? null : position + ":" + reel.id);
    }

    private void rebuildMergedFeed(boolean forceReset) {
        Activity act = getActivity();
        if (act == null || adapter == null) return;

        Map<String, Models.Reel> map = new LinkedHashMap<>();
        Models.Reel optimistic = ReelUploadManager.getOptimisticReel();
        if (optimistic != null && optimistic.videoUrl != null && !optimistic.videoUrl.isEmpty()) {
            map.put(optimistic.id, optimistic);
        }
        for (Models.Reel r : fromReels) {
            if (r != null && r.videoUrl != null && !r.videoUrl.isEmpty()) map.put(r.id, r);
        }
        for (Models.Reel r : fromPosts) {
            if (r != null && r.videoUrl != null && !r.videoUrl.isEmpty() && !map.containsKey(r.id)) {
                map.put(r.id, r);
            }
        }
        List<Models.Reel> candidates = new ArrayList<>(map.values());
        if (followingOnlyMode && Fb.me != null) {
            java.util.Set<String> circle = new java.util.HashSet<>();
            if (Fb.me.following != null) circle.addAll(Fb.me.following);
            if (Fb.me.favoriteUids != null) circle.addAll(Fb.me.favoriteUids);
            if (Fb.uid() != null) circle.add(Fb.uid());
            List<Models.Reel> filtered = new ArrayList<>();
            for (Models.Reel r : candidates) {
                if (r.authorId != null && circle.contains(r.authorId)) filtered.add(r);
            }
            if (!filtered.isEmpty()) candidates = filtered;
        }
        String curUid = Fb.uid() == null ? "" : Fb.uid();
        if (!curUid.equals(activeReelUid)) {
            activeReelUid = curUid;
            sessionReelOrderIds.clear();
        }
        List<Models.Reel> merged;
        if (!forceReset && !sessionReelOrderIds.isEmpty()) {
            // ⚡ Anti-Jump Session Order Lock:
            // Keep EVERY currently loaded Reel in its exact frozen slot so Like / Love / Haha / Wow / Sad / Angry
            // or Comments NEVER reorder or jump the active video up or down!
            Map<String, Models.Reel> byId = new LinkedHashMap<>();
            for (Models.Reel r : candidates) {
                if (r != null && r.id != null) byId.put(r.id, r);
            }
            // Preserve local optimistic reaction state for any Reel with an in-flight reaction tap
            for (Models.Reel existing : adapter.rawReels()) {
                if (existing != null && existing.id != null && isReelReactionInFlight(existing.id)) {
                    Models.Reel snapReel = byId.get(existing.id);
                    if (snapReel != null) {
                        snapReel.likes = existing.likes;
                        snapReel.reactions = new HashMap<>(existing.reactions);
                        snapReel.likedBy = new ArrayList<>(existing.likedBy);
                    }
                }
            }
            merged = new ArrayList<>(candidates.size());
            List<String> updatedOrder = new ArrayList<>(candidates.size());
            if (optimistic != null && optimistic.id != null && !sessionReelOrderIds.contains(optimistic.id)) {
                merged.add(optimistic);
                updatedOrder.add(optimistic.id);
                byId.remove(optimistic.id);
            }
            for (String lockedId : sessionReelOrderIds) {
                Models.Reel r = byId.remove(lockedId);
                if (r != null) {
                    merged.add(r);
                    updatedOrder.add(r.id);
                }
            }
            // Append any newly arrived Reels/Video Posts strictly at the END so current ViewPager2 index never shifts!
            for (Models.Reel leftover : candidates) {
                if (leftover != null && leftover.id != null && byId.containsKey(leftover.id)) {
                    merged.add(leftover);
                    updatedOrder.add(leftover.id);
                    byId.remove(leftover.id);
                }
            }
            if (initialReelId != null && !initialReelId.isEmpty()) {
                for (int i = 0; i < merged.size(); i++) {
                    if (initialReelId.equals(merged.get(i).id)) {
                        if (i > 0) {
                            merged.add(0, merged.remove(i));
                            updatedOrder.remove(initialReelId);
                            updatedOrder.add(0, initialReelId);
                        }
                        break;
                    }
                }
            }
            sessionReelOrderIds.clear();
            sessionReelOrderIds.addAll(updatedOrder);
        } else {
            sessionReelOrderIds.clear();
            merged = FeedAlgorithm.rankReels(act, candidates, initialReelId);
            if (initialReelId != null && !initialReelId.isEmpty()) {
                for (int i = 0; i < merged.size(); i++) {
                    if (initialReelId.equals(merged.get(i).id)) {
                        if (i > 0) merged.add(0, merged.remove(i));
                        break;
                    }
                }
            }
            if (optimistic != null) {
                merged.remove(optimistic);
                merged.add(0, optimistic);
            }
            for (Models.Reel r : merged) {
                if (r != null && r.id != null) sessionReelOrderIds.add(r.id);
            }
        }

        if (initialLoadingBox != null) {
            initialLoadingBox.setVisibility((merged.isEmpty() && pagingVideos) ? View.VISIBLE : View.GONE);
        }
        if (loadErrorState != null) {
            loadErrorState.setVisibility((merged.isEmpty() && !pagingVideos && reelsLoadFailed) ? View.VISIBLE : View.GONE);
        }
        if (emptyState != null) {
            emptyState.setVisibility((merged.isEmpty() && !pagingVideos && !reelsLoadFailed) ? View.VISIBLE : View.GONE);
        }

        boolean wasEmpty = adapter.rawReels().isEmpty();
        adapter.setData(pager, merged, forceReset);
        updateReelBanner();

        if (!isHidden() && !merged.isEmpty() && pager != null && (wasEmpty || forceReset)) {
            pager.post(() -> {
                if (!isHidden() && pager != null) adapter.playOnly(pager, pager.getCurrentItem());
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        ReelUploadManager.addListener(bgUploadListener);
        if (!isHidden() && pager != null && adapter != null && adapter.getItemCount() > 0) {
            pager.post(() -> {
                if (!isHidden() && pager != null) adapter.playOnly(pager, pager.getCurrentItem());
            });
        }
    }

    /** Focuses a featured Reel at the top of the Reels tab when tapped from the Home Feed Reels card. */
    public void focusFeaturedReel(Models.Reel featured) {
        if (featured != null && featured.id != null && !featured.id.isEmpty()) {
            initialReelId = featured.id;
            if (featured.videoUrl != null && !featured.videoUrl.isEmpty()) {
                String key = (featured.isFromPost ? "posts/" : "reels/") + featured.id;
                if (loadedVideoIds.add(key)) {
                    if (featured.isFromPost) fromPosts.add(0, featured);
                    else fromReels.add(0, featured);
                }
            }
            if (adapter != null && pager != null) {
                rebuildMergedFeed(true);
                pager.setCurrentItem(0, false);
                pager.post(() -> {
                    if (!isHidden() && pager != null) adapter.playOnly(pager, 0);
                });
            }
        }
    }

    private void appendEndlessCycleIfNeeded() {
        if (adapter == null || pager == null) return;
        List<Models.Reel> current = adapter.rawReels();
        if (current.isEmpty() || current.size() >= 1200) return;
        List<Models.Reel> base = new ArrayList<>();
        for (Models.Reel r : fromReels) {
            if (r != null && r.videoUrl != null && !r.videoUrl.isEmpty()) base.add(r);
        }
        for (Models.Reel r : fromPosts) {
            if (r != null && r.videoUrl != null && !r.videoUrl.isEmpty()) base.add(r);
        }
        if (base.isEmpty()) return;
        Activity act = getActivity();
        if (act != null) {
            FeedAlgorithm.nextRefreshGeneration(act);
            base = FeedAlgorithm.rankReels(act, base, null);
        }
        List<Models.Reel> expanded = new ArrayList<>(current);
        expanded.addAll(base);
        adapter.setData(pager, expanded, false);
    }

    private void resetVideoPages() {
        videoGeneration++; pagingVideos=false; moreReels=true; moreVideoPosts=true;
        reelsLoadFailed=false; loadAttempts=0; watchdogGen++;
        reelsWatchdog.removeCallbacksAndMessages(null);
        reelsCursor=null; postsCursor=null; loadedVideoIds.clear(); fromReels.clear(); fromPosts.clear();

        // 1. 0.0ms Instant Launch when clicking a Video Post from Home Feed / Watch / Profile!
        Bundle args = getArguments();
        if (args != null) {
            String directUrl = args.getString("video_url");
            String directId = args.getString("initial_reel_id");
            if (directUrl != null && !directUrl.isEmpty() && directId != null && !directId.isEmpty()) {
                Models.Reel clicked = new Models.Reel();
                clicked.id = directId;
                clicked.isFromPost = args.getBoolean("is_post", true);
                clicked.videoUrl = directUrl;
                clicked.authorId = args.getString("author_id", "");
                clicked.authorName = args.getString("author_name", "User");
                clicked.authorPhoto = args.getString("author_photo", "");
                clicked.caption = args.getString("caption", "");
                clicked.likes = args.getInt("likes", 0);
                clicked.comments = args.getInt("comments", 0);
                clicked.shares = args.getInt("shares", 0);
                clicked.createdAt = System.currentTimeMillis();
                loadedVideoIds.add((clicked.isFromPost ? "posts/" : "reels/") + clicked.id);
                if (clicked.isFromPost) fromPosts.add(clicked);
                else fromReels.add(clicked);
            }
        }

        // 2. 0.0ms Instant Seed from FastCache (Home Feed videos already in memory!)
        boolean hasPinnedTarget = (initialReelId == null || initialReelId.isEmpty());
        for (Models.Reel r : fromReels) {
            if (r != null && initialReelId != null && initialReelId.equals(r.id)) hasPinnedTarget = true;
        }
        for (Models.Reel r : fromPosts) {
            if (r != null && initialReelId != null && initialReelId.equals(r.id)) hasPinnedTarget = true;
        }
        for (Models.Post p : com.friendbook.social.core.FastCache.getFeed()) {
            if (p != null && "video".equals(p.mediaType) && p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
                if (Fb.me != null && Fb.me.blocked.contains(p.authorId)) continue;
                if (Prefs.isPostHidden(getActivity(), p.id)) continue;
                if (loadedVideoIds.add("posts/" + p.id)) {
                    fromPosts.add(Models.Reel.fromVideoPost(p));
                }
                if (initialReelId != null && initialReelId.equals(p.id)) {
                    hasPinnedTarget = true;
                }
            }
        }
        if (hasPinnedTarget && (!fromReels.isEmpty() || !fromPosts.isEmpty())) {
            rebuildMergedFeed(true);
        } else if (initialLoadingBox != null) {
            initialLoadingBox.setVisibility(View.VISIBLE);
        }

        // 3. Also check local Firestore disk cache (Source.CACHE) for 0ms offline/slow-network render
        final int cacheGen = videoGeneration;
        Fb.db().collection("reels").orderBy("createdAt", Query.Direction.DESCENDING).limit(5)
                .get(com.friendbook.social.d1.documents.Source.CACHE)
                .addOnSuccessListener(snap -> {
                    if (cacheGen != videoGeneration || !isAdded() || snap == null || snap.isEmpty()) return;
                    boolean added = false;
                    for (DocumentSnapshot doc : snap.getDocuments()) {
                        if (Boolean.TRUE.equals(doc.getBoolean("safetyFlagged"))) continue;
                        Models.Reel r = Models.Reel.fromDoc(doc);
                        if (r.videoUrl == null || r.videoUrl.isEmpty()) continue;
                        if (loadedVideoIds.add("reels/" + doc.getId())) {
                            fromReels.add(r);
                            added = true;
                        }
                    }
                    if (added) rebuildMergedFeed(adapter == null || adapter.rawReels().isEmpty());
                });

        loadNextVideos();
        if(initialReelId!=null && !initialReelId.isEmpty()) {
            final int request=videoGeneration; final String target=initialReelId; final String actor=Fb.uid();
            Fb.db().collection("reels").document(target).get().addOnSuccessListener(doc -> {
                if(request!=videoGeneration || !java.util.Objects.equals(actor,Fb.uid()) || !isAdded()) return;
                if(doc.exists()) { addLinkedVideo(doc,true); return; }
                Fb.db().collection("posts").document(target).get().addOnSuccessListener(post -> {
                    if(request==videoGeneration && java.util.Objects.equals(actor,Fb.uid()) && isAdded() && post.exists()) addLinkedVideo(post,false);
                });
            });
        }
    }
    private void addLinkedVideo(DocumentSnapshot doc,boolean nativeReel) {
        if(Boolean.TRUE.equals(doc.getBoolean("safetyFlagged"))) return;
        Models.Reel reel=nativeReel?Models.Reel.fromDoc(doc):Models.Reel.fromVideoPost(Models.Post.fromDoc(doc));
        if(reel.videoUrl==null || reel.videoUrl.isEmpty() || (Fb.me!=null && Fb.me.blocked.contains(reel.authorId))) return;
        if(Prefs.isPostHidden(getActivity(),reel.id)) return;
        if(ContentSafetyEngine.checkAndAutoDeleteIfTextUnsafe(getActivity(),nativeReel?"reels":"posts",reel.id,reel.authorId,reel.caption,reel.videoUrl,null)) return;
        List<Models.Reel> bucket = nativeReel ? fromReels : fromPosts;
        if(loadedVideoIds.add((nativeReel?"reels/":"posts/")+doc.getId())) {
            bucket.add(0, reel);
        } else {
            for (int i = 0; i < bucket.size(); i++) {
                if (bucket.get(i) != null && doc.getId().equals(bucket.get(i).id)) {
                    bucket.set(i, reel);
                    break;
                }
            }
        }
        boolean wasEmpty = (adapter == null || adapter.rawReels().isEmpty());
        rebuildMergedFeed(wasEmpty);
    }
    private void loadNextVideos() {
        if(pagingVideos || (!moreReels && !moreVideoPosts) || !isAdded() || !isResumed() || isHidden()) return;
        pagingVideos=true; reelsLoadFailed=false; final int generation=videoGeneration; final String actor=Fb.uid();
        // A Firestore get() on a black-hole network calls NEITHER listener. Without this watchdog the
        // opening spinner would spin forever; after 15s the user gets the failure state and a Retry.
        reelsWatchdog.removeCallbacksAndMessages(null);
        final int wdGen=++watchdogGen;
        reelsWatchdog.postDelayed(() -> {
            if (wdGen != watchdogGen || generation != videoGeneration || !isAdded()) return;
            if (!pagingVideos) return;
            // Retire this attempt before exposing retry. Its late callbacks must
            // not move the cursor or clear the watchdog of the next attempt.
            watchdogGen++;
            pagingVideos=false;
            reelsLoadFailed=true;
            boolean empty = adapter == null || adapter.rawReels().isEmpty();
            rebuildMergedFeed(empty);
            if (getActivity() != null) Ui.toast(getActivity(), empty
                    ? "Network slow — tap Retry to load Reels" : "Network slow — tap More videos to retry");
        }, 15_000L);
        // The document query already merges canonical and legacy root Reels with
        // access checks. Avoid a duplicate legacy request racing the first page.
        // Two sources contribute five metadata documents each: at most ten per batch.
        final int[] pending={(moreReels?1:0)+(moreVideoPosts?1:0)};
        final boolean[] pageFailed={false};
        Runnable done=() -> {
            if(generation!=videoGeneration || wdGen!=watchdogGen) return;
            if(--pending[0]==0) {
                pagingVideos=false;
                watchdogGen++;
                reelsWatchdog.removeCallbacksAndMessages(null);
                if (fromReels.isEmpty() && fromPosts.isEmpty() && (moreReels || moreVideoPosts)) {
                    // Skip a bounded number of successfully read but filtered pages.
                    // A network error stops here until the user retries.
                    if (!pageFailed[0] && loadAttempts < 3) { loadAttempts++; loadNextVideos(); }
                    else { reelsLoadFailed=true; rebuildMergedFeed(true); }
                } else {
                    reelsLoadFailed=false;
                    rebuildMergedFeed(adapter == null || adapter.rawReels().isEmpty());
                }
            }
        };
        for(boolean nativeReel:new boolean[]{true,false}) {
            if(nativeReel?!moreReels:!moreVideoPosts) continue;
            Query query=nativeReel?Fb.db().collection("reels").orderBy("createdAt",Query.Direction.DESCENDING)
                    :Fb.db().collection("posts").whereEqualTo("mediaType","video");
            DocumentSnapshot cursor=nativeReel?reelsCursor:postsCursor;
            if(cursor!=null) query=query.startAfter(cursor);
            query.limit(5).get().addOnSuccessListener(page -> {
                // A superseded generation returns without settling: the newer load owns pagingVideos.
                if(generation!=videoGeneration || wdGen!=watchdogGen) return;
                // But an auth flip or a hide/show MUST NOT swallow the settle - that is exactly how
                // pagingVideos stayed true forever and the opening spinner never went away. The page
                // is simply not applied when it no longer belongs to this session.
                if(java.util.Objects.equals(actor,Fb.uid()) && isAdded()) {
                    if(nativeReel) { moreReels=page.size()==5; if(!page.isEmpty()) reelsCursor=page.getDocuments().get(page.size()-1); }
                    else { moreVideoPosts=page.size()==5; if(!page.isEmpty()) postsCursor=page.getDocuments().get(page.size()-1); }
                    boolean addedNow = false;
                    for(DocumentSnapshot doc:page) {
                        if(Boolean.TRUE.equals(doc.getBoolean("safetyFlagged"))) continue;
                        Models.Reel reel=nativeReel?Models.Reel.fromDoc(doc):Models.Reel.fromVideoPost(Models.Post.fromDoc(doc));
                        if(reel.videoUrl==null || reel.videoUrl.isEmpty()) continue;
                        if(Fb.me!=null && Fb.me.blocked.contains(reel.authorId)) continue;
                        if(Prefs.isPostHidden(getActivity(),reel.id)) continue;
                        if(ContentSafetyEngine.checkAndAutoDeleteIfTextUnsafe(getActivity(),nativeReel?"reels":"posts",reel.id,reel.authorId,reel.caption,reel.videoUrl,null)) continue;
                        if(loadedVideoIds.add((nativeReel?"reels/":"posts/")+doc.getId())) {
                            (nativeReel?fromReels:fromPosts).add(reel);
                            addedNow = true;
                        }
                    }
                    if (addedNow) rebuildMergedFeed(false);
                }
                done.run();
            }).addOnFailureListener(error -> {
                if(generation!=videoGeneration || wdGen!=watchdogGen) return;
                pageFailed[0]=true;
                done.run();
                if(getActivity()!=null) Ui.toast(getActivity(),"Couldn't load Reels — tap Retry");
            });
        }
    }

    private void startVisibleVideosIfNeeded() {
        if(isAdded() && isResumed() && !isHidden() && !pagingVideos && !reelsLoadFailed
                && fromReels.isEmpty() && fromPosts.isEmpty()) resetVideoPages();
    }
    private void retireVideoLoad() {
        videoGeneration++;pagingVideos=false;watchdogGen++;
        reelsWatchdog.removeCallbacksAndMessages(null);
    }
    @Override public void onResume(){
        super.onResume();
        startVisibleVideosIfNeeded();
        if (getActivity() != null) ConsentManager.retryConsentOnResume(getActivity());
        updateReelBanner();
        if(pager!=null && adapter!=null){
            adapter.refreshVisibleHoldersInPlace(pager);
            pager.post(()->adapter.playOnly(pager,pager.getCurrentItem()));
        }
    }
    @Override public void onPause(){
        retireVideoLoad();
        if(reelBanners!=null)reelBanners.setActive(false);
        if(adapter!=null && pager!=null)adapter.pauseAll(pager);
        super.onPause();
    }

    @Override public void onDestroyView() {
        if (reelBanners != null) reelBanners.setActive(false);
        reelBanners = null;
        reelBannerSlot = null;
        super.onDestroyView();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        updateReelBanner();
        if (adapter == null || pager == null) return;
        if (hidden) {
            retireVideoLoad();
            adapter.pauseAll(pager);
        } else {
            startVisibleVideosIfNeeded();
            pager.post(() -> {
                adapter.playOnly(pager, pager.getCurrentItem());
            });
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        videoGeneration++; pagingVideos=false; watchdogGen++;
        reelsWatchdog.removeCallbacksAndMessages(null);
        ReelUploadManager.removeListener(bgUploadListener);
        if (reelsReg != null) { reelsReg.remove(); reelsReg = null; }
        if (videoPostsReg != null) { videoPostsReg.remove(); videoPostsReg = null; }
        if (adapter != null && pager != null) adapter.pauseAll(pager);
    }

    // ================== Reel Adapter ==================

    static class ReelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        /**
         * Reels this process has already sent a view for. Counting rule (product decision): ONE view
         * per user per Reel, forever - watching it again, looping it or finishing it changes nothing.
         * This set stops the pointless repeat requests; the Worker de-dupes per uid as well, so the
         * number can never be inflated even after a reinstall or on another device.
         */
        private static final java.util.Set<String> SESSION_COUNTED_VIEWS =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        private final Activity activity;
        private final List<Models.Reel> data = new ArrayList<>();
        private int playingPos = 0;

        private final java.util.function.BooleanSupplier visible;
        ReelAdapter(Activity a,java.util.function.BooleanSupplier visible) { this.activity = a;this.visible=visible; }

        List<Models.Reel> rawReels() { return data; }

        /**
         * Smart non-destructive update:
         * - When Firestore snapshots fire for likes/comments/saves without changing the Reel ID order,
         *   updates Models.Reel models in-place WITHOUT calling notifyDataSetChanged(), so the currently
         *   playing video NEVER stutters, flashes black, or restarts!
         */
        void setData(ViewPager2 pager, List<Models.Reel> list, boolean forceReset) {
            List<Models.Reel> incoming = list != null ? list : new ArrayList<>();
            if (!forceReset && sameIdSequence(data, incoming)) {
                for (int i = 0; i < data.size(); i++) {
                    data.set(i, incoming.get(i));
                }
                refreshVisibleHoldersInPlace(pager);
                return;
            }
            // Prefix-append fast path: when new Reels/VideoPosts arrive at the end, append without touching active Reel!
            if (!forceReset && !data.isEmpty() && incoming.size() > data.size()
                    && isPrefixMatch(data, incoming)) {
                int oldAdapterCount = getItemCount();
                for (int i = 0; i < data.size(); i++) {
                    data.set(i, incoming.get(i));
                }
                for (int i = data.size(); i < incoming.size(); i++) {
                    data.add(incoming.get(i));
                }
                refreshVisibleHoldersInPlace(pager);
                int newAdapterCount = getItemCount();
                if (newAdapterCount > oldAdapterCount) {
                    notifyItemRangeInserted(oldAdapterCount, newAdapterCount - oldAdapterCount);
                }
                return;
            }
            data.clear();
            data.addAll(incoming);
            notifyDataSetChanged();
        }

        private void refreshVisibleHoldersInPlace(ViewPager2 pager) {
            if (pager != null && pager.getChildAt(0) instanceof RecyclerView) {
                RecyclerView rv = (RecyclerView) pager.getChildAt(0);
                for (int i = 0; i < rv.getChildCount(); i++) {
                    RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
                    if (h instanceof VH) {
                        VH vh = (VH) h;
                        int ap = vh.getAdapterPosition();
                        Models.Reel updated = reelAtPosition(ap);
                        if (updated != null) vh.updateMetaInPlace(updated);
                    }
                }
            }
        }

        private static boolean isPrefixMatch(List<Models.Reel> prefix, List<Models.Reel> full) {
            if (prefix.isEmpty() || full.size() < prefix.size()) return false;
            for (int i = 0; i < prefix.size(); i++) {
                String idA = prefix.get(i) != null ? prefix.get(i).id : null;
                String idB = full.get(i) != null ? full.get(i).id : null;
                if (idA == null || !idA.equals(idB)) return false;
            }
            return true;
        }

        private static boolean sameIdSequence(List<Models.Reel> a, List<Models.Reel> b) {
            if (a.size() != b.size() || a.isEmpty()) return false;
            for (int i = 0; i < a.size(); i++) {
                String idA = a.get(i) != null ? a.get(i).id : null;
                String idB = b.get(i) != null ? b.get(i).id : null;
                if (idA == null || !idA.equals(idB)) return false;
            }
            return true;
        }

        int reelIndexForPos(int pos) { return pos; }

        Models.Reel reelAtPosition(int pos) {
            return (pos >= 0 && pos < data.size()) ? data.get(pos) : null;
        }

        void pauseAll(ViewPager2 pager) {
            ReelVideoCache.cancelUpcomingWarm();
            playingPos = -1;
            if (pager == null || !(pager.getChildAt(0) instanceof RecyclerView)) return;
            RecyclerView rv = (RecyclerView) pager.getChildAt(0);
            for (int i = 0; i < rv.getChildCount(); i++) {
                RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
                if (h instanceof VH) ((VH) h).pause();
            }
        }

        void playOnly(ViewPager2 pager, int pos) {
            if(!visible.getAsBoolean() || (pager!=null && pager.getScrollState()!=ViewPager2.SCROLL_STATE_IDLE)){pauseAll(pager);return;}
            if(playingPos!=pos) ReelVideoCache.cancelUpcomingWarm();
            playingPos = pos;
            if (pager == null || !(pager.getChildAt(0) instanceof RecyclerView)) return;
            RecyclerView rv = (RecyclerView) pager.getChildAt(0);
            RecyclerView.ViewHolder selected=null;
            for (int i=0;i<rv.getChildCount();i++) {
                RecyclerView.ViewHolder holder=rv.getChildViewHolder(rv.getChildAt(i));
                if(holder.getBindingAdapterPosition()==pos) { selected=holder; continue; }
                if(holder instanceof VH) ((VH)holder).pause();
            }
            if(selected instanceof VH) {
                ((VH)selected).refreshEngagement();
                ((VH)selected).play();
            }
        }

        @Override public int getItemCount() { return data.size(); }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(activity);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Models.Reel r = reelAtPosition(position);
            if (r != null) ((VH) h).bind(r, visible.getAsBoolean() && position == playingPos);
        }

        @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder h){
            super.onViewAttachedToWindow(h);
            if(h instanceof VH && visible.getAsBoolean() && h.getBindingAdapterPosition()==playingPos){
                ((VH)h).refreshEngagement();
                ((VH)h).play();
            }
        }
        @Override public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder h){
            if(h instanceof VH)((VH)h).pause();super.onViewDetachedFromWindow(h);
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder h) {
            super.onViewRecycled(h);
            ((VH) h).releasePlayer();
        }

        // ================== Ultra-Fast TextureView + SSD Cache Reel ViewHolder ==================

        class VH extends RecyclerView.ViewHolder {
            final TextureView textureView;
            final ImageView posterView;
            final ProgressBar bufferingSpinner;
            final TextView playbackRetry;
            final ProgressBar scrubBar;
            final TextView pausedBadge;
            final FrameLayout likeBtnBox, commentBtnBox, shareBtnBox, saveBtnBox, soundBtnBox, moreBtnBox;
            final ImageView likeOutlineIv, saveIconIv, shareIconIv, soundDiscAvatarIv;
            final TextView likeIcon, likeCount, commentCount, saveCount, shareCount;
            final ImageView commentIcon;
            final ImageView heartBurst;
            final TextView nameT, captionT, audioT, followT;
            final ImageView avatar, tick;
            final View filterTintView;
            final TextView overlayTextPill, overlayStickerTv;

            MediaPlayer activeMp;
            MediaPlayer externalAudioMp;
            Surface videoSurface;
            boolean mpPrepared = false;
            boolean wantPlay = false;
            boolean userPaused = false;
            boolean triedRawFallback = false;
            int videoW = 0, videoH = 0;
            String boundVideoUrl = "";
            boolean muted;
            float speed = 1.0f;
            float soundDiscRotation = 0f;
            Models.Reel reel;
            /** True once this play-through reached one second and its view was sent to Cloudflare. */
            boolean viewCountedThisPlay = false;
            /** Last observed playback position, used to detect a loop/rewind of the same Reel. */
            int lastTickPos = 0;

            private final Handler uiHandler = new Handler(Looper.getMainLooper());
            private final Runnable progressTicker = new Runnable() {
                @Override
                public void run() {
                    if (activeMp != null && mpPrepared && wantPlay && !userPaused) {
                        try {
                            int dur = activeMp.getDuration();
                            int pos = activeMp.getCurrentPosition();
                            int advanced=pos-qualityLastPosition;
                            if(qualityLastPosition>=0 && advanced>0 && advanced<=400) watchedPlaybackMs+=advanced;
                            qualityLastPosition=pos;
                            if(watchedPlaybackMs>=2000 && !upgradeAttempted) tryQualityUpgrade();
                            if (reel != null && reel.trimEndMs > reel.trimStartMs && pos >= reel.trimEndMs) {
                                activeMp.seekTo(Math.max(0, reel.trimStartMs));
                                pos = reel.trimStartMs;
                            }
                            if (dur > 0) {
                                scrubBar.setProgress((int) ((pos * 1000L) / dur));
                            }
                            // A rewind means the Reel looped (setLooping(true)) or the trim window
                            // restarted. One second of real watching is enough to count this user's
                            // ONE view for this Reel; loops and re-watches never add another.
                            if (pos + 400 < lastTickPos) {
                                viewCountedThisPlay = false;
                            }
                            lastTickPos = pos;
                            if (!viewCountedThisPlay && pos >= 1000) {
                                viewCountedThisPlay = true;
                                countRealView();
                            }
                            soundDiscRotation = (soundDiscRotation + 5.5f) % 360f;
                            if (soundBtnBox != null) soundBtnBox.setRotation(soundDiscRotation);
                        } catch (Throwable ignored) { }
                        uiHandler.postDelayed(this, 120L);
                    }
                }
            };

            VH(Activity a) {
                super(buildRoot(a));
                FrameLayout root = (FrameLayout) itemView;
                muted = !Prefs.reelsStartWithSound(a);

                // 1. Hardware-accelerated TextureView (zero SurfaceView black hole in ViewPager2!)
                textureView = new TextureView(a);
                textureView.setOpaque(false);
                root.addView(textureView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

                // 2. Instant 0.0ms Poster Thumbnail Overlay (hidden the exact ms video frame #1 renders)
                posterView = new ImageView(a);
                posterView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                root.addView(posterView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

                // 2b. Creator Color Filter Tint + Sticker + Text Overlay Layers
                filterTintView = new View(a);
                filterTintView.setVisibility(View.GONE);
                filterTintView.setClickable(false);
                filterTintView.setFocusable(false);
                root.addView(filterTintView, new FrameLayout.LayoutParams(-1, -1));

                // Real 2026 vector stickers on playback; text stickers from older builds still render.
                overlayStickerTv = com.friendbook.social.core.StudioVectorIcons.stickerView(a, 48, Color.WHITE);
                overlayStickerTv.setVisibility(View.GONE);
                overlayStickerTv.setElevation(Ui.dp(a, 18));
                root.addView(overlayStickerTv, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

                overlayTextPill = Ui.tvBold(a, "", 16.5f, Color.WHITE);
                overlayTextPill.setGravity(Gravity.CENTER);
                overlayTextPill.setShadowLayer(4f, 0f, 1f, 0xCC000000);
                overlayTextPill.setVisibility(View.GONE);
                overlayTextPill.setElevation(Ui.dp(a, 19));
                Ui.pad(overlayTextPill, 14, 7, 14, 7);
                root.addView(overlayTextPill, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

                // 3. Subtle center buffering indicator & pause indicator
                bufferingSpinner = new ProgressBar(a);
                bufferingSpinner.setVisibility(View.GONE);
                int spSz = Ui.dp(a, 38);
                root.addView(bufferingSpinner, new FrameLayout.LayoutParams(spSz, spSz, Gravity.CENTER));
                playbackRetry = Ui.roundChip(a, "Retry video", Ui.BLUE, Color.WHITE, 15f);
                Ui.pad(playbackRetry, 16, 10, 16, 10);
                playbackRetry.setVisibility(View.GONE);
                playbackRetry.setOnClickListener(v -> togglePlayPause());
                root.addView(playbackRetry, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

                pausedBadge = com.friendbook.social.core.StudioVectorIcons.circleIconButton(a, "play", 62, 0x55000000, 0xDDFFFFFF, null);
                pausedBadge.setGravity(Gravity.CENTER);
                pausedBadge.setBackground(Ui.circle(0x66000000));
                pausedBadge.setVisibility(View.GONE);
                int pbSz = Ui.dp(a, 68);
                root.addView(pausedBadge, new FrameLayout.LayoutParams(pbSz, pbSz, Gravity.CENTER));

                // Giant animated heart burst on double-tap!
                // 2026 vector heart burst (no keyboard emoji), tinted brand pink
                heartBurst = com.friendbook.social.core.StudioVectorIcons.iconView(a, "heart", 86, 0xFFFF2D55);
                heartBurst.setAlpha(0f);
                heartBurst.setElevation(Ui.dp(a, 30));
                int hbSz = Ui.dp(a, 96);
                root.addView(heartBurst, new FrameLayout.LayoutParams(hbSz, hbSz, Gravity.CENTER));

                // Right-Side TikTok Action Rail (Like · Comment · Favorite + Count · Share + Count · ••• · Spinning Sound Disc)
                LinearLayout rail = Ui.col(a);
                rail.setGravity(Gravity.CENTER_HORIZONTAL);
                rail.setElevation(Ui.dp(a, 24));
                FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
                rp.setMargins(0, 0, Ui.dp(a, 10), Ui.dp(a, 54));
                rail.setLayoutParams(rp);

                likeOutlineIv = new ImageView(a);
                likeOutlineIv.setImageResource(com.friendbook.social.R.drawable.ic_like_outline);
                likeOutlineIv.setColorFilter(Color.WHITE);
                likeIcon = Ui.tv(a, "", 22, Color.WHITE);
                likeIcon.setGravity(Gravity.CENTER);
                likeIcon.setVisibility(View.GONE);
                likeBtnBox = new FrameLayout(a);
                likeBtnBox.setBackground(Ui.roundStroke(a, 0x66000000, 22, 0x33FFFFFF, 1));
                int lkSz = Ui.dp(a, 44);
                likeBtnBox.setLayoutParams(new LinearLayout.LayoutParams(lkSz, lkSz));
                int lkIconSz = Ui.dp(a, 22);
                likeBtnBox.addView(likeOutlineIv, new FrameLayout.LayoutParams(lkIconSz, lkIconSz, Gravity.CENTER));
                likeBtnBox.addView(likeIcon, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                likeCount = shadowLabel(a, "Like");

                commentIcon = com.friendbook.social.core.StudioVectorIcons.iconView(a, "captions", 22, Color.WHITE);
                commentBtnBox = circleActionView(a, commentIcon);
                commentCount = shadowLabel(a, "Comment");

                // TikTok-Style Ribbon Bookmark (Favorite) Icon + Favorite Count
                saveIconIv = new ImageView(a);
                saveIconIv.setImageResource(com.friendbook.social.R.drawable.ic_tiktok_bookmark_filled);
                saveIconIv.setColorFilter(Color.WHITE);
                saveBtnBox = new FrameLayout(a);
                saveBtnBox.setBackground(Ui.roundStroke(a, 0x66000000, 22, 0x33FFFFFF, 1));
                saveBtnBox.setLayoutParams(new LinearLayout.LayoutParams(lkSz, lkSz));
                int bmSz = Ui.dp(a, 23);
                saveBtnBox.addView(saveIconIv, new FrameLayout.LayoutParams(bmSz, bmSz, Gravity.CENTER));
                saveCount = shadowLabel(a, "0");

                // TikTok-Style Curved Share Arrow Icon + Share Count
                shareIconIv = new ImageView(a);
                shareIconIv.setImageResource(com.friendbook.social.R.drawable.ic_tiktok_share_filled);
                shareIconIv.setColorFilter(Color.WHITE);
                shareBtnBox = new FrameLayout(a);
                shareBtnBox.setBackground(Ui.roundStroke(a, 0x66000000, 22, 0x33FFFFFF, 1));
                shareBtnBox.setLayoutParams(new LinearLayout.LayoutParams(lkSz, lkSz));
                int shSz = Ui.dp(a, 23);
                shareBtnBox.addView(shareIconIv, new FrameLayout.LayoutParams(shSz, shSz, Gravity.CENTER));
                shareCount = shadowLabel(a, "Share");

                ImageView moreIcon = com.friendbook.social.core.StudioVectorIcons.iconView(a, "more", 20, Color.WHITE);
                moreBtnBox = circleActionView(a, moreIcon);

                // TikTok-Style Spinning Vinyl Sound Disc at bottom of right rail (tap to use this sound in your own video!)
                soundBtnBox = new FrameLayout(a);
                soundBtnBox.setBackground(Ui.roundStroke(a, 0xFF161823, 24, 0xFF3E4042, 7));
                int discSz = Ui.dp(a, 46);
                soundBtnBox.setLayoutParams(new LinearLayout.LayoutParams(discSz, discSz));
                soundDiscAvatarIv = new ImageView(a);
                int innerAvSz = Ui.dp(a, 26);
                soundBtnBox.addView(soundDiscAvatarIv, new FrameLayout.LayoutParams(innerAvSz, innerAvSz, Gravity.CENTER));
                ImageView musicBadge = com.friendbook.social.core.StudioVectorIcons.iconView(a, "music", 12, 0xFF25F4EE);
                soundBtnBox.addView(musicBadge, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));

                rail.addView(likeBtnBox);
                rail.addView(likeCount);
                rail.addView(spacer(a, 10));
                rail.addView(commentBtnBox);
                rail.addView(commentCount);
                rail.addView(spacer(a, 10));
                rail.addView(saveBtnBox);
                rail.addView(saveCount);
                rail.addView(spacer(a, 10));
                rail.addView(shareBtnBox);
                rail.addView(shareCount);
                rail.addView(spacer(a, 8));
                rail.addView(moreBtnBox);
                rail.addView(spacer(a, 10));
                rail.addView(soundBtnBox);
                root.addView(rail);

                // Bottom creator info and playback scrub bar
                LinearLayout bottomCol = Ui.col(a);
                bottomCol.setElevation(Ui.dp(a, 22));
                android.graphics.drawable.GradientDrawable bottomGrad = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x00000000, 0x44000000, 0x99000000});
                bottomCol.setBackground(bottomGrad);
                Ui.pad(bottomCol, 14, 16, 72, 10);
                FrameLayout.LayoutParams bip = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
                bottomCol.setLayoutParams(bip);

                LinearLayout authorRow = Ui.row(a);
                authorRow.setGravity(Gravity.CENTER_VERTICAL);
                avatar = new ImageView(a);
                int s = Ui.dp(a, 38);
                authorRow.addView(avatar, new LinearLayout.LayoutParams(s, s));

                nameT = Ui.tvBold(a, "", 15, Color.WHITE);
                nameT.setShadowLayer(4f, 0f, 1f, 0xCC000000);
                nameT.setPadding(Ui.dp(a, 9), 0, 0, 0);
                authorRow.addView(nameT);

                tick = new ImageView(a);
                tick.setImageResource(com.friendbook.social.R.drawable.ic_verified);
                tick.setVisibility(View.GONE);
                int ts = Ui.dp(a, 15);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ts, ts);
                tlp.leftMargin = Ui.dp(a, 4);
                authorRow.addView(tick, tlp);

                followT = Ui.tvBold(a, "Follow", 12.5f, Color.WHITE);
                followT.setBackground(Ui.roundStroke(a, 0x33000000, 8, Color.WHITE, 1));
                Ui.pad(followT, 10, 4, 10, 4);
                LinearLayout.LayoutParams fp2 = new LinearLayout.LayoutParams(-2, -2);
                fp2.leftMargin = Ui.dp(a, 10);
                authorRow.addView(followT, fp2);
                bottomCol.addView(authorRow);

                captionT = Ui.tv(a, "", 14, Color.WHITE);
                captionT.setMaxLines(2);
                captionT.setShadowLayer(4f, 0f, 1f, 0xCC000000);
                captionT.setPadding(0, Ui.dp(a, 6), 0, 0);
                captionT.setOnClickListener(v -> captionT.setMaxLines(captionT.getMaxLines() == 2 ? 8 : 2));
                bottomCol.addView(captionT);

                audioT = Ui.tv(a, "", 12, 0xDDFFFFFF);
                audioT.setSingleLine(true);
                audioT.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 8));
                bottomCol.addView(audioT);

                root.addView(bottomCol);

                // Thin bottom playback progress bar
                scrubBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
                scrubBar.setMax(1000);
                scrubBar.setProgress(0);
                scrubBar.setElevation(Ui.dp(a, 26));
                FrameLayout.LayoutParams sblp = new FrameLayout.LayoutParams(-1, Ui.dp(a, 3), Gravity.BOTTOM);
                root.addView(scrubBar, sblp);

                rail.bringToFront();

                // TextureView lifecycle
                textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                        if (videoSurface != null) {
                            try { videoSurface.release(); } catch (Throwable ignored) {}
                        }
                        videoSurface = new Surface(surface);
                        if (activeMp != null) {
                            try {
                                activeMp.setSurface(videoSurface);
                                applyVideoTransform(width, height);
                            } catch (Throwable ignored) {}
                        } else if (reel != null) {
                            if (wantPlay) prepareMediaPlayerAsync();
                            else prepareCachedNext();
                        }
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                        applyVideoTransform(width, height);
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                        if (activeMp != null) {
                            try { activeMp.setSurface(null); } catch (Throwable ignored) {}
                        }
                        if (videoSurface != null) {
                            try { videoSurface.release(); } catch (Throwable ignored) {}
                            videoSurface = null;
                        }
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                        if (activeMp != null && mpPrepared && wantPlay && posterView.getVisibility() == View.VISIBLE)
                            handlePlaybackInfo(activeMp, MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, itemView.getContext());
                    }
                });

                // Gestures: double-tap to ❤️ Like, single tap pause/play
                GestureDetector gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        if (reel != null) {
                            animateHeartBurst();
                            applyReelReaction("love", true);
                        }
                        return true;
                    }
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (reel != null && playbackRetry.getVisibility() != View.VISIBLE
                                && !java.util.Objects.equals(reel.videoUrl, com.friendbook.social.core.R2VideoClient.fullPlaybackUrl(reel.videoUrl))) {
                            Intent full = new Intent(a, FullscreenMediaActivity.class);
                            full.putExtra("type", "video");
                            full.putExtra("url", reel.videoUrl);
                            a.startActivity(full);
                            return true;
                        }
                        togglePlayPause();
                        return true;
                    }
                });
                View.OnTouchListener videoTouch = (v, ev) -> gd.onTouchEvent(ev);
                textureView.setOnTouchListener(videoTouch);
                posterView.setOnTouchListener(videoTouch);

                // Action Listeners: 1-tap adds/removes ONLY currentUserId's reaction; long-press picks any of 6!
                likeBtnBox.setOnClickListener(v -> {
                    if (reel == null) return;
                    if(!com.friendbook.social.core.InteractionCooldown.disableBriefly(v))return;
                    Ui.haptic(v);
                    String cur = resolveMyReelReaction(reel);
                    applyReelReaction(cur != null ? null : "like", false);
                });
                likeBtnBox.setOnLongClickListener(v -> {
                    if (reel == null) return false;
                    Reactions.show(likeBtnBox, picked -> applyReelReaction(picked, false));
                    return true;
                });
                likeCount.setOnClickListener(v -> {
                    if (reel != null) Reactions.showReelReactors(a, reel);
                });

                View.OnClickListener openCommentsListener = v -> {
                    if (reel == null || (reel.id != null && reel.id.startsWith("local_uploading_"))) return;
                    Intent i = new Intent(v.getContext(), ReelCommentsActivity.class);
                    i.putExtra("reel_id", reel.id);
                    i.putExtra("author", reel.authorId);
                    i.putExtra("collection", reel.collectionName());
                    v.getContext().startActivity(i);
                };
                commentBtnBox.setOnClickListener(openCommentsListener);
                commentCount.setOnClickListener(openCommentsListener);

                View.OnClickListener openShareListener = v -> shareSheet(a);
                shareBtnBox.setOnClickListener(openShareListener);
                shareCount.setOnClickListener(openShareListener);

                View.OnClickListener toggleFavoriteListener = v -> toggleSaveReel(a);
                saveBtnBox.setOnClickListener(toggleFavoriteListener);
                saveCount.setOnClickListener(toggleFavoriteListener);

                View.OnClickListener openSoundSheetListener = v -> openTikTokSoundSheet(a);
                soundBtnBox.setOnClickListener(openSoundSheetListener);
                audioT.setOnClickListener(openSoundSheetListener);

                moreBtnBox.setOnClickListener(v -> showMoreMenu(a, v));

                followT.setOnClickListener(v -> follow());
                View.OnClickListener prof = v -> {
                    if (reel == null) return;
                    Intent i = new Intent(v.getContext(), ProfileActivity.class);
                    i.putExtra("uid", reel.authorId);
                    v.getContext().startActivity(i);
                };
                avatar.setOnClickListener(prof);
                avatar.setOnLongClickListener(v -> {
                    if (reel != null && reel.authorPhoto != null && !reel.authorPhoto.isEmpty()) {
                        Intent dp = new Intent(a, FullscreenMediaActivity.class);
                        dp.putExtra("type", "image");
                        dp.putExtra("url", reel.authorPhoto);
                        dp.putExtra("title", reel.authorName + "'s Profile Picture");
                        a.startActivity(dp);
                        return true;
                    }
                    return false;
                });
                nameT.setOnClickListener(prof);
            }

            private FrameLayout circleAction(Context c, View inner) {
                FrameLayout box = new FrameLayout(c);
                box.setBackground(Ui.roundStroke(c, 0x66000000, 22, 0x33FFFFFF, 1));
                int sz = Ui.dp(c, 44);
                box.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
                box.addView(inner, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                return box;
            }

            /** Same circular rail button, but sized to wrap a fixed-size 2026 vector icon. */
            private FrameLayout circleActionView(Context c, View iconView) {
                FrameLayout box = new FrameLayout(c);
                box.setBackground(Ui.roundStroke(c, 0x66000000, 22, 0x33FFFFFF, 1));
                int sz = Ui.dp(c, 44);
                box.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
                int iconSz = Ui.dp(c, 23);
                box.addView(iconView, new FrameLayout.LayoutParams(iconSz, iconSz, Gravity.CENTER));
                return box;
            }

            private TextView shadowLabel(Context c, String txt) {
                TextView t = Ui.tvBold(c, txt, 11f, Color.WHITE);
                t.setGravity(Gravity.CENTER);
                t.setShadowLayer(4f, 0f, 1f, 0xDD000000);
                t.setPadding(0, Ui.dp(c, 2), 0, 0);
                return t;
            }

            private View spacer(Context c, int dp) {
                View v = new View(c);
                v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(c, dp)));
                return v;
            }

            private static FrameLayout buildRoot(Activity a) {
                FrameLayout f = new FrameLayout(a);
                f.setBackgroundColor(Color.BLACK);
                f.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
                return f;
            }

            void updateMetaInPlace(Models.Reel updated) {
                this.reel = updated;
                renderCreatorAndCaption();
                renderCounts();
                refreshEngagement();
            }

            private void renderCreatorAndCaption() {
                if (reel == null) return;
                Context c = itemView.getContext();
                Ui.avatar(c, avatar, reel.authorPhoto);
                nameT.setText(reel.authorName == null ? "User" : reel.authorName);
                TickCache.attach(tick, reel.authorId);

                boolean hasCap = reel.caption != null && !reel.caption.trim().isEmpty();
                captionT.setText(hasCap ? reel.caption : "");
                com.friendbook.social.core.LinkUtils.makeLinksClickable(captionT);
                captionT.setVisibility(hasCap ? View.VISIBLE : View.GONE);

                String customAudio = (reel.audioTitle != null && !reel.audioTitle.trim().isEmpty())
                        ? reel.audioTitle.trim()
                        : ("Original sound - " + (reel.authorName == null ? "friendbook" : reel.authorName));
                audioT.setText(customAudio + "   ·   Use sound ›");
                String discPhoto = (reel.soundAuthorPhoto != null && !reel.soundAuthorPhoto.trim().isEmpty())
                        ? reel.soundAuthorPhoto.trim() : reel.authorPhoto;
                Ui.avatar(c, soundDiscAvatarIv, discPhoto);

                // Real 4K UHD GPU grade ONLY when the creator actually picked a filter/effect, so
                // unfiltered Reels stay on the fastest possible playback path (zero extra GPU pass).
                String gradePreset = (reel.filterPreset != null && !reel.filterPreset.trim().isEmpty()
                        && !"none".equalsIgnoreCase(reel.filterPreset.trim()))
                        ? reel.filterPreset.trim() : "none";
                com.friendbook.social.core.StudioVectorIcons.apply4kUhdGpuGrade(
                        textureView, filterTintView, reel.beautyLevel, gradePreset, reel.filterOverlayColor);

                if (reel.overlayText != null && !reel.overlayText.trim().isEmpty()) {
                    overlayTextPill.setText(reel.overlayText.trim());
                    overlayTextPill.setTextColor(reel.overlayColor != 0 ? reel.overlayColor : Color.WHITE);
                    if ((reel.overlayBgColor >>> 24) > 0) {
                        overlayTextPill.setBackground(Ui.round(c, reel.overlayBgColor, 14));
                    } else {
                        overlayTextPill.setBackground(null);
                    }
                    overlayTextPill.setVisibility(View.VISIBLE);
                    itemView.post(() -> {
                        int rw = itemView.getWidth(), rh = itemView.getHeight();
                        int pw = overlayTextPill.getWidth(), ph = overlayTextPill.getHeight();
                        if (rw > 0 && rh > 0) {
                            float tx = Math.max(Ui.dp(c, 12), Math.min(rw - pw - Ui.dp(c, 64), reel.overlayX * rw - pw / 2f));
                            float ty = Math.max(Ui.dp(c, 70), Math.min(rh - ph - Ui.dp(c, 150), reel.overlayY * rh - ph / 2f));
                            overlayTextPill.setTranslationX(tx);
                            overlayTextPill.setTranslationY(ty);
                        }
                    });
                } else {
                    overlayTextPill.setVisibility(View.GONE);
                }

                if (reel.stickerText != null && !reel.stickerText.trim().isEmpty()) {
                    overlayStickerTv.setText(reel.stickerText.trim());
                    float sc = Math.max(0.6f, Math.min(2.2f, reel.stickerScale > 0f ? reel.stickerScale : 1.0f));
                    overlayStickerTv.setScaleX(sc);
                    overlayStickerTv.setScaleY(sc);
                    overlayStickerTv.setVisibility(View.VISIBLE);
                    itemView.post(() -> {
                        int rw = itemView.getWidth(), rh = itemView.getHeight();
                        int sw = overlayStickerTv.getWidth(), sh = overlayStickerTv.getHeight();
                        if (rw > 0 && rh > 0) {
                            float sx = Math.max(Ui.dp(c, 12), Math.min(rw - sw - Ui.dp(c, 64), reel.stickerX * rw - sw / 2f));
                            float sy = Math.max(Ui.dp(c, 70), Math.min(rh - sh - Ui.dp(c, 150), reel.stickerY * rh - sh / 2f));
                            overlayStickerTv.setTranslationX(sx);
                            overlayStickerTv.setTranslationY(sy);
                        }
                    });
                } else {
                    overlayStickerTv.setVisibility(View.GONE);
                }

                if (reel.playbackSpeed >= 0.25f && reel.playbackSpeed <= 3.0f) {
                    this.speed = reel.playbackSpeed;
                } else {
                    this.speed = 1.0f;
                }
                boolean hasAttachedSound = reel.audioUrl != null && !reel.audioUrl.trim().isEmpty();
                if (reel.muteOriginalAudio && !hasAttachedSound) {
                    this.muted = true;
                } else {
                    this.muted = !Prefs.reelsStartWithSound(c);
                }

                String me = Fb.uid();
                boolean isMe = me != null && me.equals(reel.authorId);
                followT.setVisibility(isMe ? View.GONE : View.VISIBLE);
                if (!isMe && Fb.me != null && Fb.me.following != null) {
                    followT.setText(Fb.me.following.contains(reel.authorId) ? "Following" : "Follow");
                }
            }


            void bind(Models.Reel r, boolean autoPlayNow) {
                boolean sameVideo = (this.reel != null && boundVideoUrl.equals(r.videoUrl) && activeMp != null);
                this.reel = r;
                this.userPaused = false;
                this.wantPlay = autoPlayNow;
                pausedBadge.setVisibility(View.GONE);

                renderCreatorAndCaption();
                renderCounts();
                refreshEngagement();
                hydrateLegacyPostReelReactionsIfNeeded(r);

                if (sameVideo) {
                    if (autoPlayNow) play();
                    else pause();
                    return;
                }

                releasePlayer();
                this.wantPlay=autoPlayNow;
                boundVideoUrl = r.videoUrl != null ? r.videoUrl : "";
                triedRawFallback = false;
                upgradeAttempted = false;
                scrubBar.setProgress(0);

                // 0.0ms Instant Poster Frame without downloading remote .mp4 via Glide in parallel with MediaPlayer!
                Context c = itemView.getContext();
                String thumbUrl = ReelVideoCache.deriveThumbUrl(r.videoUrl, r.thumbUrl);
                File localCached = ReelVideoCache.getCachedFile(c, r.videoUrl);
                bufferingSpinner.setVisibility((localCached == null && autoPlayNow) ? View.VISIBLE : View.GONE);
                if (!thumbUrl.isEmpty()) {
                    posterView.setVisibility(View.VISIBLE);
                    Ui.img(c, posterView, thumbUrl);
                } else if (localCached != null) {
                    posterView.setVisibility(View.VISIBLE);
                    com.bumptech.glide.Glide.with(c.getApplicationContext())
                            .load(localCached)
                            .fitCenter()
                            .dontAnimate()
                            .into(posterView);
                } else {
                    try { com.bumptech.glide.Glide.with(c.getApplicationContext()).clear(posterView); } catch (Throwable ignored) {}
                    posterView.setImageDrawable(Ui.round(c, 0xFF141824, 0));
                    posterView.setVisibility(View.VISIBLE);
                }

                pausedBadge.setVisibility(View.GONE);
                // The immediate next page may prepare only an already cached file.
                if (textureView.isAvailable()) {
                    if (videoSurface == null && textureView.getSurfaceTexture() != null) {
                        videoSurface = new Surface(textureView.getSurfaceTexture());
                    }
                    if (wantPlay) prepareMediaPlayerAsync();
                    else prepareCachedNext();
                }
            }

            private MediaPlayer upgradePlayer;
            private boolean upgradeAttempted;
            private int watchedPlaybackMs;
            private int qualityLastPosition = -1;

            private void cancelQualityUpgrade() {
                watchedPlaybackMs=0;qualityLastPosition=-1;
                if(upgradePlayer!=null){
                    try{upgradePlayer.release();}catch(RuntimeException ignored){}
                    upgradePlayer=null;
                }
            }

            private void tryQualityUpgrade() {
                if(upgradeAttempted || !wantPlay || userPaused || getBindingAdapterPosition()!=playingPos
                        || reel==null || activeMp==null || videoSurface==null || triedRawFallback) return;
                Context ctx=itemView.getContext();
                String raw=reel.videoUrl;
                if(raw==null || !ReelVideoCache.canUpgrade(ctx)) return;
                String high=ReelVideoCache.upgrade720Url(raw);
                if(raw.equals(high)) return;
                MediaPlayer low=activeMp;
                try {
                    if(!low.isPlaying()) return;
                    upgradeAttempted=true;
                    MediaPlayer candidate=new MediaPlayer();upgradePlayer=candidate;
                    candidate.setLooping(true);
                    candidate.setOnErrorListener((mp,what,extra)->{
                        if(mp==upgradePlayer) cancelQualityUpgrade();
                        else if(mp==activeMp) reportStartupFailure(ctx);
                        return true;
                    });
                    candidate.setOnPreparedListener(mp->{
                        if(mp!=upgradePlayer || low!=activeMp || !wantPlay || userPaused || getBindingAdapterPosition()!=playingPos || videoSurface==null) return;
                        try {
                            mp.setOnSeekCompleteListener(seeked->{
                                if(seeked!=upgradePlayer || low!=activeMp || !wantPlay || userPaused || getBindingAdapterPosition()!=playingPos || videoSurface==null) return;
                                try {
                                    boolean external=reel.audioUrl!=null&&!reel.audioUrl.trim().isEmpty();
                                    seeked.setVolume(muted||external?0f:1f,muted||external?0f:1f);
                                    low.pause();low.setSurface(null);
                                    seeked.setSurface(videoSurface);
                                    if(speed!=1f) seeked.setPlaybackParams(seeked.getPlaybackParams().setSpeed(speed));
                                    seeked.start();
                                    activeMp=seeked;upgradePlayer=null;
                                    videoW=seeked.getVideoWidth();videoH=seeked.getVideoHeight();
                                    applyVideoTransform(textureView.getWidth(),textureView.getHeight());
                                    try { low.release(); } catch(RuntimeException ignored) {}
                                } catch(RuntimeException error){
                                    cancelQualityUpgrade();
                                    try{low.setSurface(videoSurface);low.start();}catch(RuntimeException ignored){}
                                }
                            });
                            int position=Math.min(low.getCurrentPosition(),Math.max(0,mp.getDuration()-1));
                            if(android.os.Build.VERSION.SDK_INT>=26) mp.seekTo(position,MediaPlayer.SEEK_CLOSEST);
                            else mp.seekTo(position);
                        } catch(RuntimeException ignored){cancelQualityUpgrade();}
                    });
                    candidate.setDataSource(ctx,Uri.parse(high));candidate.prepareAsync();
                    uiHandler.postDelayed(()->{if(upgradePlayer==candidate)cancelQualityUpgrade();},8000L);
                }catch(Exception ignored){cancelQualityUpgrade();}
            }

            private Runnable startupWatchdog;
            private boolean renderedFirstFrame;

            private void watchStartup(MediaPlayer player, Context context, String rawUrl, boolean canFallback) {
                startupWatchdog = () -> {
                    if (player != activeMp || !wantPlay || getBindingAdapterPosition()!=playingPos) return;
                    if (canFallback && !triedRawFallback && !ReelVideoCache.preferLiteStartup(context)) {
                        triedRawFallback=true;
                        prepareWithRawUrlFallback(context,rawUrl);
                    } else {
                        reportStartupFailure(context);
                    }
                };
                // Give the small stream time on slow links instead of restarting early
                // with a potentially much larger original. Explicit media errors still fall back.
                boolean slow = FastCache.isSlow2G(context)
                        || FastCache.netTier(context) == FastCache.NetTier.NET_3G;
                uiHandler.postDelayed(startupWatchdog, canFallback ? (slow ? 12000L : 4500L) : 12000L);
            }

            private void reportStartupFailure(Context context) {
                releasePlayerOnly();
                bufferingSpinner.setVisibility(View.GONE);
                if (wantPlay && getBindingAdapterPosition()==playingPos) playbackRetry.setVisibility(View.VISIBLE);
            }

            private boolean handlePlaybackInfo(MediaPlayer player, int what, Context context) {
                if (player != activeMp) return false;
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    if (!wantPlay || userPaused || getBindingAdapterPosition() != playingPos) return false;
                    cancelStartupWatchdog();
                    posterView.setVisibility(View.GONE);
                    bufferingSpinner.setVisibility(View.GONE);
                    if (!renderedFirstFrame) {
                        renderedFirstFrame = true;
                        final int position = getBindingAdapterPosition();
                        prepareNextHolder(position);
                    }
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START && wantPlay && !userPaused) {
                    ReelVideoCache.cancelUpcomingWarm();
                    bufferingSpinner.setVisibility(View.VISIBLE);
                    if (startupWatchdog == null) watchStartup(player, context, null, false);
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    bufferingSpinner.setVisibility(View.GONE);
                    if (posterView.getVisibility() == View.GONE) cancelStartupWatchdog();
                }
                return false;
            }

            private void cancelStartupWatchdog() {
                if (startupWatchdog!=null) uiHandler.removeCallbacks(startupWatchdog);
                startupWatchdog=null;
            }

            // Prepare only an already complete local file for the immediate next
            // holder. This adds no offscreen network request and is cancelled by
            // the ordinary pause/recycle lifecycle if the user scrolls away.
            private void prepareNextHolder(int position) {
                if (!visible.getAsBoolean() || !wantPlay || getBindingAdapterPosition()!=position || playingPos!=position
                        || !(itemView.getParent() instanceof RecyclerView)) return;
                RecyclerView.ViewHolder next = ((RecyclerView) itemView.getParent()).findViewHolderForAdapterPosition(position+1);
                if (next instanceof VH) ((VH) next).prepareCachedNext();
            }

            private void prepareCachedNext() {
                if (reel==null || reel.videoUrl==null || !visible.getAsBoolean() || playingPos<0
                        || getBindingAdapterPosition()!=playingPos+1 || wantPlay || activeMp!=null || videoSurface==null) return;
                Context ctx = itemView.getContext();
                File cached = ReelVideoCache.getCachedFile(ctx, reel.videoUrl);
                if (!ReelPreloadPolicy.canPrepare(visible.getAsBoolean(),playingPos,getBindingAdapterPosition(),
                        wantPlay,activeMp!=null,videoSurface!=null,cached!=null)) return;
                String targetUrl = reel.videoUrl;
                MediaPlayer mp = new MediaPlayer();
                activeMp = mp;
                try {
                    mp.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .setUsage(AudioAttributes.USAGE_MEDIA).build());
                    mp.setLooping(true);
                    mp.setVolume(0f,0f);
                    mp.setSurface(videoSurface);
                    mp.setOnVideoSizeChangedListener((player,width,height) -> {
                        if (player != activeMp) return;
                        videoW=width; videoH=height; applyVideoTransform(textureView.getWidth(),textureView.getHeight());
                    });
                    mp.setOnInfoListener((player,what,extra) -> handlePlaybackInfo(player,what,ctx));
                    mp.setOnPreparedListener(player -> {
                        if (player!=activeMp) return;
                        mpPrepared=true;
                        videoW=player.getVideoWidth();videoH=player.getVideoHeight();
                        applyVideoTransform(textureView.getWidth(),textureView.getHeight());
                        if (wantPlay && !userPaused && getBindingAdapterPosition()==playingPos) startPreparedPlayer();
                    });
                    mp.setOnErrorListener((player,what,extra) -> {
                        if (player!=activeMp) return true;
                        boolean selected=wantPlay && getBindingAdapterPosition()==playingPos;
                        releasePlayerOnly();
                        if (selected && reel!=null && java.util.Objects.equals(targetUrl,reel.videoUrl)) {
                            triedRawFallback=true;
                            prepareWithRawUrlFallback(ctx,targetUrl);
                        }
                        return true;
                    });
                    try (FileInputStream in = new FileInputStream(cached)) { mp.setDataSource(in.getFD()); }
                    mp.prepareAsync();
                } catch (Throwable error) { if (mp==activeMp) releasePlayerOnly(); }
            }

            private void prepareMediaPlayerAsync() {
                if (!wantPlay || getBindingAdapterPosition()!=playingPos || reel == null || reel.videoUrl == null || reel.videoUrl.isEmpty()) return;
                releasePlayerOnly();

                playbackRetry.setVisibility(View.GONE);
                final Context ctx = itemView.getContext();
                final String targetUrl = reel.videoUrl;
                mpPrepared = false;

                File cached = triedRawFallback ? null : ReelVideoCache.getCachedFile(ctx, targetUrl);
                final String startupUrl = triedRawFallback ? targetUrl : ReelVideoCache.startupUrl(ctx, targetUrl);
                final boolean canFallback = !triedRawFallback && (cached != null || !targetUrl.equals(startupUrl));
                bufferingSpinner.setVisibility((cached == null && wantPlay) ? View.VISIBLE : View.GONE);

                MediaPlayer mp = new MediaPlayer();
                activeMp = mp;
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mp.setAudioAttributes(new AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build());
                    }
                    mp.setLooping(true);
                    float vol = muted ? 0f : 1f;
                    mp.setVolume(vol, vol);
                    if (videoSurface != null) {
                        mp.setSurface(videoSurface);
                    }

                    mp.setOnVideoSizeChangedListener((player, width, height) -> {
                        videoW = width;
                        videoH = height;
                        applyVideoTransform(textureView.getWidth(), textureView.getHeight());
                    });

                    mp.setOnInfoListener((player, what, extra) -> handlePlaybackInfo(player, what, ctx));

                    mp.setOnPreparedListener(player -> {
                        if (player != activeMp) return;
                        mpPrepared = true;
                        videoW = player.getVideoWidth();
                        videoH = player.getVideoHeight();
                        applyVideoTransform(textureView.getWidth(), textureView.getHeight());
                        float v = muted ? 0f : 1f;
                        player.setVolume(v, v);
                        if (speed != 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try { player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed)); }
                            catch (Throwable ignored) {}
                        }
                        bufferingSpinner.setVisibility(View.GONE);
                        if (wantPlay && !userPaused) {
                            startPreparedPlayer();
                        } else {
                            // Seek to 1ms so offscreen TextureView already holds decoded frame #0!
                            try { player.seekTo(1); } catch (Throwable ignored) {}
                        }
                    });

                    mp.setOnErrorListener((player, what, extra) -> {
                        if(player!=activeMp || !java.util.Objects.equals(targetUrl,reel==null?null:reel.videoUrl)) return true;
                        bufferingSpinner.setVisibility(View.GONE);
                        // Automatic fallback to raw untransformed URL if CDN transform failed
                        if (canFallback) {
                            triedRawFallback = true;
                            prepareWithRawUrlFallback(ctx, reel.videoUrl);
                            return true;
                        }
                        reportStartupFailure(ctx);
                        return true;
                    });

                    if (cached != null) {
                        try (FileInputStream fis = new FileInputStream(cached)) {
                            mp.setDataSource(fis.getFD());
                        }
                    } else if (targetUrl.startsWith("file:") || targetUrl.startsWith("content:")) {
                        mp.setDataSource(ctx, Uri.parse(targetUrl));
                    } else {
                        mp.setDataSource(ctx, Uri.parse(startupUrl));
                        // One stream only: a parallel full download competes with playback on mobile data.
                    }
                    watchStartup(mp,ctx,targetUrl,cached==null && canFallback);
                    mp.prepareAsync();
                } catch (Throwable t) {
                    bufferingSpinner.setVisibility(View.GONE);
                    if (canFallback) {
                        triedRawFallback = true;
                        prepareWithRawUrlFallback(ctx, targetUrl);
                    } else reportStartupFailure(ctx);
                }
            }

            private void prepareWithRawUrlFallback(Context ctx, String rawUrl) {
                if(!wantPlay || getBindingAdapterPosition()!=playingPos || reel==null || !java.util.Objects.equals(rawUrl,reel.videoUrl)) return;
                releasePlayerOnly();
                try {
                    MediaPlayer mp = new MediaPlayer();
                    activeMp = mp;
                    mp.setLooping(true);
                    if (videoSurface != null) mp.setSurface(videoSurface);
                    mp.setOnPreparedListener(player -> {
                        if (player != activeMp) return;
                        mpPrepared = true;
                        videoW = player.getVideoWidth();
                        videoH = player.getVideoHeight();
                        applyVideoTransform(textureView.getWidth(), textureView.getHeight());
                        float v = muted ? 0f : 1f;
                        player.setVolume(v, v);
                        if (wantPlay && !userPaused) startPreparedPlayer();
                    });
                    mp.setOnInfoListener((player, what, extra) -> handlePlaybackInfo(player, what, ctx));
                    mp.setOnErrorListener((player,what,extra) -> {
                        if (player==activeMp) reportStartupFailure(ctx);
                        return true;
                    });
                    mp.setDataSource(ctx, Uri.parse(rawUrl));
                    watchStartup(mp,ctx,rawUrl,false);
                    mp.prepareAsync();
                } catch (Throwable ignored) { reportStartupFailure(ctx); }
            }

            private void applyVideoTransform(int viewW, int viewH) {
                if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return;
                float viewRatio = (float) viewW / viewH;
                float videoRatio = (float) videoW / videoH;
                Matrix matrix = new Matrix();
                float sx, sy;
                // Preserve 100% of the creator's uploaded video frame (aspect ratio) without cropping or stretching!
                if (videoRatio > viewRatio) {
                    sx = 1f;
                    sy = viewRatio / videoRatio;
                } else {
                    sx = videoRatio / viewRatio;
                    sy = 1f;
                }
                if (reel != null && reel.mirrorHorizontal) {
                    sx = -sx;
                }
                matrix.setScale(sx, sy, viewW / 2f, viewH / 2f);
                textureView.setTransform(matrix);
            }

            private void startPreparedPlayer() {
                if (!wantPlay || userPaused || getBindingAdapterPosition()!=playingPos || activeMp == null || !mpPrepared) return;
                try {
                    boolean hasAttachedSound = reel != null && reel.audioUrl != null && !reel.audioUrl.trim().isEmpty();
                    float vol = (muted || hasAttachedSound) ? 0f : 1f;
                    activeMp.setVolume(vol, vol);
                    if (!activeMp.isPlaying()) {
                        if (speed != 1f) {
                            try { activeMp.setPlaybackParams(activeMp.getPlaybackParams().setSpeed(speed)); }
                            catch (Throwable ignored) { }
                        }
                        activeMp.start();
                    }
                    // Starting from (near) the beginning re-arms the one-second check. Whether the
                    // user watches one second or the whole Reel, at most ONE view is ever counted
                    // for them - countRealView() and the Worker both refuse a second one.
                    try {
                        if (activeMp.getCurrentPosition() < 600) {
                            viewCountedThisPlay = false;
                            lastTickPos = 0;
                        }
                    } catch (Throwable ignored) {}
                    if (hasAttachedSound && !muted) {
                        ensureExternalSoundPlaying(itemView.getContext(), reel.audioUrl.trim());
                    }
                    pausedBadge.setVisibility(View.GONE);
                    uiHandler.removeCallbacks(progressTicker);
                    uiHandler.post(progressTicker);
                } catch (Throwable ignored) { }
            }

            private void ensureExternalSoundPlaying(Context ctx, final String soundUrl) {
                if (soundUrl == null || soundUrl.isEmpty()) return;
                try {
                    if (externalAudioMp != null) {
                        if (!externalAudioMp.isPlaying()) externalAudioMp.start();
                        return;
                    }
                    final MediaPlayer emp = new MediaPlayer();
                    externalAudioMp = emp;
                    emp.setLooping(true);
                    File cached = ReelVideoCache.getCachedFile(ctx, soundUrl);
                    if (cached != null) {
                        try (FileInputStream fis = new FileInputStream(cached)) {
                            emp.setDataSource(fis.getFD());
                        }
                    } else {
                        emp.setDataSource(ctx, Uri.parse(soundUrl));
                    }
                    emp.setOnPreparedListener(player -> {
                        if (player == externalAudioMp && wantPlay && !userPaused && !muted) {
                            try { player.start(); } catch (Throwable ignored) {}
                        }
                    });
                    emp.prepareAsync();
                } catch (Throwable ignored) { }
            }

            /**
             * Counts this user's ONE real view in Cloudflare (Worker `/v1/views`) after one second of
             * actual watching - watching the whole Reel or only a second makes no difference, and
             * watching it again never counts twice. Firebase is authentication only: nothing is
             * written to Firestore and the number is never guessed locally; the Worker returns the
             * live total it keeps in memory + KV and persists to D1 once an hour (zero D1 row reads).
             */
            private void countRealView() {
                if (reel == null || reel.id == null || reel.id.isEmpty() || reel.id.startsWith("opt_")) return;
                final Models.Reel viewedReel = reel;
                final String key = viewedReel.collectionName() + "|" + viewedReel.id;
                // Already sent this Reel in this app session: no repeat request, no repeat view.
                if (!SESSION_COUNTED_VIEWS.add(key)) return;
                try {
                    com.friendbook.social.core.CloudflareViews.recordView(
                            viewedReel.collectionName(), viewedReel.id,
                            total -> viewedReel.views = Math.max(viewedReel.views, Math.max(0, total)));
                } catch (Throwable ignored) {
                    // Nothing was sent, so a later play may try again.
                    SESSION_COUNTED_VIEWS.remove(key);
                }
            }

            private void togglePlayPause() {
                if (activeMp == null || !mpPrepared) {
                    if (activeMp == null) triedRawFallback=false; // A deliberate tap gets a fresh 480p attempt.
                    play();
                    return;
                }
                try {
                    if (activeMp.isPlaying()) {
                        userPaused = true;
                        cancelQualityUpgrade();
                        activeMp.pause();
                        if (externalAudioMp != null && externalAudioMp.isPlaying()) {
                            try { externalAudioMp.pause(); } catch (Throwable ignored) {}
                        }
                        pausedBadge.setVisibility(View.VISIBLE);
                        uiHandler.removeCallbacks(progressTicker);
                    } else {
                        userPaused = false;
                        wantPlay = true;
                        startPreparedPlayer();
                    }
                } catch (Throwable ignored) { }
            }

            void play() {
                if(getBindingAdapterPosition()!=playingPos) return;
                wantPlay = true;
                userPaused = false;
                pausedBadge.setVisibility(View.GONE);
                if (activeMp != null && mpPrepared) {
                    startPreparedPlayer();
                } else if (activeMp == null && reel != null && textureView.isAvailable()) {
                    if (videoSurface == null && textureView.getSurfaceTexture() != null) {
                        videoSurface = new Surface(textureView.getSurfaceTexture());
                    }
                    prepareMediaPlayerAsync();
                } else {
                    bufferingSpinner.setVisibility(View.VISIBLE);
                    if (activeMp != null && startupWatchdog == null && reel != null)
                        watchStartup(activeMp,itemView.getContext(),reel.videoUrl,false);
                }
            }

            void pause() {
                wantPlay = false;
                cancelQualityUpgrade();
                if (!mpPrepared) releasePlayerOnly();
                uiHandler.removeCallbacks(progressTicker);
                bufferingSpinner.setVisibility(View.GONE);
                pausedBadge.setVisibility(View.GONE);
                if (activeMp != null && mpPrepared) {
                    try {
                        if (activeMp.isPlaying()) activeMp.pause();
                    } catch (Throwable ignored) { }
                }
                if (externalAudioMp != null) {
                    try {
                        if (externalAudioMp.isPlaying()) externalAudioMp.pause();
                    } catch (Throwable ignored) { }
                }
            }

            private void releasePlayerOnly() {
                renderedFirstFrame = false;
                playbackRetry.setVisibility(View.GONE);
                cancelQualityUpgrade();
                cancelStartupWatchdog();
                uiHandler.removeCallbacks(progressTicker);
                mpPrepared = false;
                if (externalAudioMp != null) {
                    final MediaPlayer toReleaseAudio = externalAudioMp;
                    externalAudioMp = null;
                    new Thread(() -> {
                        try { toReleaseAudio.reset(); } catch (Throwable ignored) {}
                        try { toReleaseAudio.release(); } catch (Throwable ignored) {}
                    }, "reel-ext-audio-release").start();
                }
                if (activeMp != null) {
                    final MediaPlayer toRelease = activeMp;
                    activeMp = null;
                    try { toRelease.setSurface(null); } catch (Throwable ignored) {}
                    new Thread(() -> {
                        try { toRelease.reset(); } catch (Throwable ignored) {}
                        try { toRelease.release(); } catch (Throwable ignored) {}
                    }, "reel-mp-release").start();
                }
            }

            void releasePlayer() {
                wantPlay = false;
                boundVideoUrl = "";
                bufferingSpinner.setVisibility(View.GONE);
                pausedBadge.setVisibility(View.GONE);
                releasePlayerOnly();
            }

            /** Reconciles subcollection reactions (`posts/{id}/reactions`) for legacy Video Posts with empty `reactionsMap`. */
            private void hydrateLegacyPostReelReactionsIfNeeded(final Models.Reel target) {
                if (target == null || !target.isFromPost || target.id == null || target.id.isEmpty()) return;
                if(com.friendbook.social.core.D1Client.enabled(target.isFromPost?"posts":"reels"))return;
                if (target.reactions != null && !target.reactions.isEmpty()) return;
                if (target.likes <= 0 || isReelReactionInFlight(target.id)) return;
                final String targetId = target.id;
                final String readActor = Fb.uid();
                if (!LegacyReactionReadGate.begin(readActor,targetId,
                        android.os.SystemClock.elapsedRealtime())) return;
                Fb.db().collection("posts").document(targetId).collection("reactions")
                        .limit(200)
                        .get()
                        .addOnSuccessListener(snap -> {
                            if (snap == null) {
                                LegacyReactionReadGate.failure(readActor,targetId);
                                return;
                            }
                            boolean stillCurrent = readActor != null && readActor.equals(Fb.uid());
                            boolean reactionTapInFlight = isReelReactionInFlight(targetId);
                            LegacyReactionReadGate.success(readActor,targetId,
                                    snap.isEmpty() && stillCurrent && !reactionTapInFlight,
                                    android.os.SystemClock.elapsedRealtime());
                            if (!stillCurrent || reactionTapInFlight) return;
                            Map<String, String> loaded = new HashMap<>();
                            List<String> uids = new ArrayList<>();
                            for (DocumentSnapshot d : snap.getDocuments()) {
                                String uid = d.getString("userId");
                                if (uid == null || uid.trim().isEmpty()) uid = d.getId();
                                if (uid == null || uid.trim().isEmpty()) continue;
                                uid = uid.trim();
                                String t = Models.normalizeReactionType(d.getString("type"), "like");
                                loaded.put(uid, t);
                                uids.add(uid);
                            }
                            target.reactions = loaded;
                            target.likedBy = uids;
                            if (snap.size() < 200) {
                                target.likes = loaded.size();
                            } else {
                                target.likes = Math.max(target.likes, loaded.size());
                            }
                            if (reel != null && targetId.equals(reel.id)) {
                                reel.reactions = loaded;
                                reel.likedBy = uids;
                                reel.likes = target.likes;
                                renderCounts();
                            }
                        }).addOnFailureListener(error ->
                                LegacyReactionReadGate.failure(readActor,targetId));
            }

            // Only explicit bind/resume/authoritative metadata refresh requests
            // engagement. Async completion only paints cache; it cannot refetch.
            private void refreshEngagement() {
                // ViewPager attaches/prebinds neighboring holders. They are not
                // user-visible reads; selected play/attach will refresh later.
                if (!visible.getAsBoolean() || getBindingAdapterPosition() != playingPos
                        || playingPos < 0) return;
                if (reel == null || reel.id == null || reel.id.startsWith("local_uploading_")) return;
                if (!com.friendbook.social.core.D1Client.enabled(reel.isFromPost?"posts":"reels")) return;
                final Models.Reel current = reel;
                com.friendbook.social.post.D1Reactions.reel(itemView.getContext(),current,()->{
                    if (reel == current) renderCounts();
                });
            }

            private void renderCounts() {
                if (reel == null) return;
                Context c = itemView.getContext();
                if(com.friendbook.social.core.D1Client.enabled(reel.isFromPost?"posts":"reels"))com.friendbook.social.post.D1Reactions.hydrateReel(reel);
                String me = Fb.uid();
                String myRx = resolveMyReelReaction(reel);

                // STRICT PER-USER RULE:
                // - If `currentUserId` has NOT reacted (`myRx == null`) -> 100% UNCOLORED white outline icon & neutral circle!
                // - If `currentUserId` HAS reacted (`myRx != null`) -> ONLY `currentUserId`'s own reaction is colored!
                if (myRx == null) {
                    likeOutlineIv.setVisibility(View.VISIBLE);
                    likeOutlineIv.setImageResource(com.friendbook.social.R.drawable.ic_like_outline);
                    likeOutlineIv.setColorFilter(Color.WHITE);
                    likeIcon.setText("");
                    likeIcon.setVisibility(View.GONE);
                    likeBtnBox.setBackground(Ui.roundStroke(c, 0x66000000, 22, 0x33FFFFFF, 1));
                } else if ("like".equals(myRx)) {
                    likeOutlineIv.setVisibility(View.VISIBLE);
                    likeOutlineIv.setImageResource(com.friendbook.social.R.drawable.ic_like_filled);
                    likeOutlineIv.clearColorFilter();
                    likeIcon.setText("");
                    likeIcon.setVisibility(View.GONE);
                    likeBtnBox.setBackground(Ui.roundStroke(c, 0x771877F2, 22, 0xFF1877F2, 1));
                } else {
                    likeOutlineIv.setVisibility(View.GONE);
                    likeIcon.setText(Reactions.emojiFor(myRx));
                    likeIcon.setVisibility(View.VISIBLE);
                    int rxBg = "love".equals(myRx) ? 0x66F02849 : 0x66E09B00;
                    int rxStroke = "love".equals(myRx) ? 0xCCF02849 : 0xCCE09B00;
                    likeBtnBox.setBackground(Ui.roundStroke(c, rxBg, 22, rxStroke, 1));
                }

                // Total Count shown separately right underneath the reaction button!
                int mapSize = reel.reactions != null ? reel.reactions.size() : 0;
                int totalLikes = Math.max(reel.likes, mapSize);
                if (myRx != null && totalLikes < 1) totalLikes = 1;
                likeCount.setText(totalLikes > 0 ? Format.count(totalLikes) : "Like");
                commentCount.setText(reel.comments > 0 ? Format.count(reel.comments) : "Comment");
                shareCount.setText(reel.shares > 0 ? Format.count(reel.shares) : "Share");

                boolean saved = me != null && reel.savedBy != null && reel.savedBy.contains(me);
                int favCount = Math.max(reel.favorites, reel.savedBy != null ? reel.savedBy.size() : 0);
                if (saved && favCount < 1) favCount = 1;
                saveCount.setText(Format.count(favCount));
                saveIconIv.setColorFilter(saved ? 0xFFFACE15 : Color.WHITE);
                saveBtnBox.setBackground(Ui.roundStroke(c,
                        saved ? 0x55FACE15 : 0x66000000, 22,
                        saved ? 0xFFFACE15 : 0x33FFFFFF, 1));
            }

            private void animateHeartBurst() {
                heartBurst.setScaleX(0.3f);
                heartBurst.setScaleY(0.3f);
                heartBurst.setAlpha(1f);
                heartBurst.animate().scaleX(1.3f).scaleY(1.3f).setDuration(200).withEndAction(() ->
                        heartBurst.animate().scaleX(1f).scaleY(1f).alpha(0f).setStartDelay(220).setDuration(240).start()
                ).start();
            }

            private void applyReelReaction(String requestedType, boolean onlyAdd) {
                String me = Fb.uid();
                if (me == null || me.isEmpty() || reel == null) return;
                if (reel.id != null && reel.id.startsWith("local_uploading_")) return;
                if(com.friendbook.social.core.D1Client.enabled(reel.isFromPost?"posts":"reels")){
                    final Models.Reel target=reel;String collection=target.isFromPost?"posts":"reels";
                    com.friendbook.social.post.D1Reactions.apply(itemView.getContext(),collection,target.id,requestedType,onlyAdd,()->{
                        if(reel==target)com.friendbook.social.post.D1Reactions.reel(itemView.getContext(),target,this::renderCounts);
                        if(reel==target)renderCounts();});return;
                }
                if (reel.reactions == null) reel.reactions = new HashMap<>();
                if (reel.likedBy == null) reel.likedBy = new ArrayList<>();

                String prev = resolveMyReelReaction(reel);
                if (onlyAdd && prev != null) {
                    renderCounts();
                    return;
                }
                // Tapping or re-selecting the exact same reaction toggles ONLY `currentUserId`'s reaction off cleanly
                final String nextType = (!onlyAdd && prev != null && (requestedType == null || prev.equals(requestedType)))
                        ? null
                        : (requestedType == null ? null : Models.normalizeReactionType(requestedType, "like"));

                markReelReactionTap(reel.id);
                if (nextType == null) {
                    reel.reactions.remove(me);
                    reel.likedBy.remove(me);
                    if (prev != null && reel.likes > 0) reel.likes--;
                } else {
                    reel.reactions.put(me, nextType);
                    if (!reel.likedBy.contains(me)) reel.likedBy.add(me);
                    if (prev == null) reel.likes++;
                }
                reel.likes = Math.max(reel.reactions.size(), Math.max(0, reel.likes));
                renderCounts();

                final Context ctx = itemView.getContext();
                final int delta = (prev == null && nextType != null) ? 1 : (prev != null && nextType == null ? -1 : 0);

                final String pushAuthor = reel.authorId;
                final String pushReelId = reel.id;
                Runnable notifyReaction = () -> {
                    if (me.equals(Fb.uid()) && nextType != null && prev == null && !me.equals(pushAuthor)) {
                        Notify.send(pushAuthor, "reel_like", Fb.myName() + " liked your reel", pushReelId);
                    }
                };
                if (reel.isFromPost) {
                    // Video Post in Reels: sync posts/{id}.reactionsMap.<me> + posts/{id}/reactions/<me> + FastCache!
                    com.friendbook.social.core.FastCache.markLocalReactionTap(reel.id);
                    List<String> activeTypes = new ArrayList<>();
                    for (String t : Reactions.TYPES) {
                        if (reel.reactions.containsValue(t)) activeTypes.add(t);
                    }
                    com.friendbook.social.core.FastCache.setReactionTypes(reel.id, activeTypes);
                    com.friendbook.social.core.FastCache.setReactionState(ctx, reel.id, nextType, reel.likes, true);

                    com.friendbook.social.d1.documents.DocumentReference postRef =
                            Fb.db().collection("posts").document(reel.id);
                    com.friendbook.social.d1.documents.DocumentReference subRef =
                            postRef.collection("reactions").document(me);

                    if (nextType == null) {
                        subRef.delete();
                        Map<String, Object> dot = new HashMap<>();
                        dot.put("reactionsMap." + me, FieldValue.delete());
                        dot.put("reactions." + me, FieldValue.delete());
                        if (delta != 0) dot.put("likes", FieldValue.increment(delta));
                        postRef.update(dot).addOnFailureListener(err -> {
                            Map<String, Object> up = new HashMap<>();
                            Map<String, Object> rmDel = new HashMap<>();
                            rmDel.put(me, FieldValue.delete());
                            up.put("reactionsMap", rmDel);
                            Map<String, Object> rxDel = new HashMap<>();
                            rxDel.put(me, FieldValue.delete());
                            up.put("reactions", rxDel);
                            if (delta != 0) up.put("likes", FieldValue.increment(delta));
                            postRef.set(up, SetOptions.merge());
                        });
                    } else {
                        final Map<String, Object> rxEntry = new HashMap<>();
                        rxEntry.put("userId", me);
                        rxEntry.put("userName", Fb.myName());
                        rxEntry.put("userPhoto", Fb.myPhoto());
                        rxEntry.put("userTickLevel", Fb.me != null ? Fb.me.tickLevel : "none");
                        rxEntry.put("type", nextType);
                        rxEntry.put("createdAt", System.currentTimeMillis());
                        subRef.set(rxEntry).addOnSuccessListener(v -> notifyReaction.run());

                        Map<String, Object> dot = new HashMap<>();
                        dot.put("reactionsMap." + me, rxEntry);
                        if (delta != 0) dot.put("likes", FieldValue.increment(delta));
                        postRef.update(dot).addOnFailureListener(err -> {
                            Map<String, Object> nested = new HashMap<>();
                            Map<String, Object> rmap = new HashMap<>();
                            rmap.put(me, rxEntry);
                            nested.put("reactionsMap", rmap);
                            if (delta != 0) nested.put("likes", FieldValue.increment(delta));
                            postRef.set(nested, SetOptions.merge());
                        });
                    }
                } else {
                    // Native Reel in reels/{id}: atomic per-userId dot-path update with FieldValue.delete() merge fallback
                    com.friendbook.social.d1.documents.DocumentReference reelRef =
                            Fb.db().collection("reels").document(reel.id);
                    Map<String, Object> dot = new HashMap<>();
                    dot.put("reactions." + me, nextType == null ? FieldValue.delete() : nextType);
                    dot.put("likedBy", nextType == null ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
                    dot.put("likes", reel.reactions.size());
                    reelRef.update(dot).addOnSuccessListener(v -> notifyReaction.run()).addOnFailureListener(err -> {
                        Map<String, Object> fallback = new HashMap<>();
                        Map<String, Object> rxMap = new HashMap<>();
                        rxMap.put(me, nextType == null ? FieldValue.delete() : nextType);
                        fallback.put("reactions", rxMap);
                        fallback.put("likedBy", nextType == null ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
                        fallback.put("likes", reel.reactions.size());
                        reelRef.set(fallback, SetOptions.merge()).addOnSuccessListener(v -> notifyReaction.run());
                    });
                }

                if (nextType != null && prev == null && !me.equals(reel.authorId)) {
                    FeedAlgorithm.recordCreatorAffinity(ctx, reel.authorId, 6);
                }
            }

            private void toggleSaveReel(Activity a) {
                String me = Fb.uid();
                if (reel == null || me == null) return;
                if (reel.id != null && reel.id.startsWith("local_uploading_")) return;
                Ui.haptic(saveBtnBox);
                if (reel.savedBy == null) reel.savedBy = new ArrayList<>();
                boolean already = reel.savedBy.contains(me);
                if (already) {
                    reel.savedBy.remove(me);
                    if (reel.favorites > 0) reel.favorites--;
                } else {
                    reel.savedBy.add(me);
                    reel.favorites++;
                }
                reel.favorites = Math.max(reel.savedBy.size(), Math.max(0, reel.favorites));
                renderCounts();
                saveBtnBox.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110).withEndAction(() ->
                        saveBtnBox.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                ).start();
                Map<String, Object> favUp = new HashMap<>();
                favUp.put("savedBy", already ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
                favUp.put("favorites", reel.favorites);
                Fb.db().collection(reel.collectionName()).document(reel.id)
                        .set(favUp, SetOptions.merge());
                Fb.db().collection("users").document(me)
                        .set(Fb.map("savedReelIds", already ? FieldValue.arrayRemove(reel.id) : FieldValue.arrayUnion(reel.id)), SetOptions.merge());
                if (!already) {
                    FeedAlgorithm.recordCreatorAffinity(a, reel.authorId, 8);
                }
                Ui.toast(a, already ? "Removed from Favorites" : "Added to Favorites");
            }

            /**
             * 🎵 TikTok-Style Sound Sheet:
             * Shows the spinning sound disc cover, sound title & creator, live sound preview,
             * and "🎬 Use this sound" so any user can tap it and record/upload their own video with this sound!
             */
            private void openTikTokSoundSheet(Activity a) {
                if (reel == null || a == null) return;
                final String soundTitle = (reel.audioTitle != null && !reel.audioTitle.trim().isEmpty())
                        ? reel.audioTitle.trim()
                        : ("Original sound - " + (reel.authorName == null ? "friendbook" : reel.authorName));
                final String soundUrl = (reel.audioUrl != null && !reel.audioUrl.trim().isEmpty())
                        ? reel.audioUrl.trim()
                        : (reel.videoUrl != null ? reel.videoUrl.trim() : "");
                final String soundPhoto = (reel.soundAuthorPhoto != null && !reel.soundAuthorPhoto.trim().isEmpty())
                        ? reel.soundAuthorPhoto.trim()
                        : (reel.authorPhoto != null ? reel.authorPhoto : "");

                LinearLayout sheet = Ui.col(a);
                Ui.pad(sheet, 20, 18, 20, 14);

                LinearLayout topRow = Ui.row(a);
                topRow.setGravity(Gravity.CENTER_VERTICAL);
                FrameLayout discWrap = new FrameLayout(a);
                discWrap.setBackground(Ui.roundStroke(a, 0xFF161823, 34, 0xFF3E4042, 9));
                int dSz = Ui.dp(a, 68);
                ImageView coverIv = new ImageView(a);
                Ui.avatar(a, coverIv, soundPhoto);
                int cSz = Ui.dp(a, 40);
                discWrap.addView(coverIv, new FrameLayout.LayoutParams(cSz, cSz, Gravity.CENTER));
                topRow.addView(discWrap, new LinearLayout.LayoutParams(dSz, dSz));

                LinearLayout metaCol = Ui.col(a);
                metaCol.setPadding(Ui.dp(a, 14), 0, 0, 0);
                TextView titleTv = Ui.tvBold(a, soundTitle, 16f, Ui.txt(a));
                metaCol.addView(titleTv);
                TextView creatorTv = Ui.tv(a, "Creator: @" + (reel.authorName == null ? "user" : reel.authorName), 13f, Ui.sub(a));
                creatorTv.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
                metaCol.addView(creatorTv);
                TextView hintTv = Ui.tvBold(a, "Tap 'Use this sound' to add this sound to your video", 12f, Ui.BLUE);
                metaCol.addView(hintTv);
                topRow.addView(metaCol, new LinearLayout.LayoutParams(0, -2, 1f));
                sheet.addView(topRow);

                TextView useSoundBtn = Ui.tvBold(a, "Use this sound", 15f, Color.WHITE);
                useSoundBtn.setGravity(Gravity.CENTER);
                useSoundBtn.setBackground(Ui.round(a, 0xFFFE2C55, 24));
                Ui.pad(useSoundBtn, 20, 13, 20, 13);
                LinearLayout.LayoutParams ublp = new LinearLayout.LayoutParams(-1, -2);
                ublp.topMargin = Ui.dp(a, 18);
                sheet.addView(useSoundBtn, ublp);

                final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(a)
                        .setView(sheet)
                        .setNegativeButton("Close", null)
                        .create();

                useSoundBtn.setOnClickListener(v -> {
                    dlg.dismiss();
                    pause();
                    Intent i = new Intent(a, AddReelActivity.class);
                    i.putExtra("use_sound_url", soundUrl);
                    i.putExtra("use_sound_title", soundTitle);
                    i.putExtra("use_sound_author_photo", soundPhoto);
                    a.startActivity(i);
                });

                dlg.show();
            }

            private void follow() {
                if (reel == null) return;
                String me = Fb.uid();
                if (me == null) return;
                if(!com.friendbook.social.core.InteractionCooldown.disableBriefly(followT))return;
                final String authorId = reel.authorId, authorName = reel.authorName;
                Notify.follow(authorId, true).addOnSuccessListener(v -> {
                    if (!me.equals(Fb.uid())) return;
                    if (reel != null && java.util.Objects.equals(authorId, reel.authorId)) followT.setText("Following");
                    Ui.toast(itemView.getContext(), "Following " + authorName);
                }).addOnFailureListener(e -> {
                    if (me.equals(Fb.uid())) Ui.toast(itemView.getContext(), "Couldn't follow. Please try again.");
                });
            }

            private void shareSheet(Activity a) {
                if (reel == null) return;
                if (reel.id != null && reel.id.startsWith("local_uploading_")) {
                    Ui.toast(a, "Reel is finishing upload — ready to share in a moment!");
                    return;
                }
                Ui.choose(a, "Share Reel", new String[]{
                        "Share Reel to your News Feed",
                        "Add Reel to your Story",
                        "Send via WhatsApp / Other Apps",
                        "Copy Reel Video Link"
                }, which -> {
                    reel.shares++;
                    renderCounts();
                    Fb.db().collection(reel.collectionName()).document(reel.id)
                            .set(Fb.map("shares", FieldValue.increment(1)), SetOptions.merge());

                    if (which == 0) {
                        Map<String, Object> postMap = Fb.map(
                                "authorId", Fb.uid(),
                                "authorName", Fb.myName(),
                                "authorPhotoUrl", Fb.myPhoto(),
                                "authorVerified", Fb.me != null && Fb.me.isVerified,
                                "authorTickLevel", Fb.me != null ? Fb.me.tickLevel : "none",
                                "content", reel.caption != null && !reel.caption.isEmpty()
                                        ? ("Shared a Reel by " + reel.authorName + ": " + reel.caption)
                                        : ("Shared a Reel by " + reel.authorName),
                                "mediaType", "video",
                                "mediaUrl", reel.videoUrl,
                                "privacy", "Public",
                                "likes", 0, "comments", 0, "shares", 0,
                                "savedBy", new ArrayList<String>(),
                                "createdAt", FieldValue.serverTimestamp());
                        Fb.db().collection("posts").add(postMap);
                        Ui.toast(a, "Reel shared to your News Feed");
                    } else if (which == 1) {
                        Map<String, Object> s = new HashMap<>();
                        s.put("authorId", Fb.uid());
                        s.put("authorName", Fb.myName());
                        s.put("authorPhotoUrl", Fb.myPhoto());
                        s.put("mediaUrl", reel.videoUrl);
                        s.put("mediaType", "video");
                        s.put("caption", "Reel by " + reel.authorName);
                        s.put("viewedBy", new ArrayList<String>());
                        s.put("createdAt", FieldValue.serverTimestamp());
                        s.put("expiresAt", new com.google.firebase.Timestamp(
                                new java.util.Date(System.currentTimeMillis() + 24L * 3600_000L)));
                        Fb.db().collection("stories").add(s);
                        Ui.toast(a, "Added Reel to your Story");
                    } else if (which == 2) {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, "Watch " + reel.authorName + "'s Reel on friendbook:\n"
                                + (reel.caption == null ? "" : reel.caption + "\n") + reel.videoUrl);
                        a.startActivity(Intent.createChooser(i, "Share Reel"));
                    } else {
                        Ui.copy(a, reel.videoUrl);
                    }
                });
            }

            private void showMoreMenu(Activity a, View anchor) {
                if (reel == null) return;
                PopupMenu m = new PopupMenu(a, anchor);
                m.getMenu().add(0, 10, 0, "Upload Your Own Reel");
                m.getMenu().add(0, 1, 0, "See who reacted");
                m.getMenu().add(0, 2, 0, "Playback speed (" + speed + "x)");
                m.getMenu().add(0, 3, 0, "Download Reel video");
                m.getMenu().add(0, 4, 0, "Copy video link");
                if (reel.authorPhoto != null && !reel.authorPhoto.isEmpty()) {
                    m.getMenu().add(0, 5, 0, "View creator's full DP");
                }
                m.getMenu().add(0, 6, 0, "Report Reel");
                if (!java.util.Objects.equals(reel.videoUrl, com.friendbook.social.core.R2VideoClient.fullPlaybackUrl(reel.videoUrl)))
                    m.getMenu().add(0, 17, 0, "Watch full quality");
                if (reel.authorId != null && !reel.authorId.equals(Fb.uid())) {
                    m.getMenu().add(0, 7, 0, "Block " + (reel.authorName == null ? "creator" : reel.authorName));
                    if (com.friendbook.social.core.Admin.canModerate()) {
                        m.getMenu().add(0, 8, 0, com.friendbook.social.core.Admin.isAdmin()
                                ? "Delete Reel (Admin)" : "Delete Reel (Moderator)");
                    }
                }
                m.setOnMenuItemClickListener(it -> {
                    int id = it.getItemId();
                    if (id == 10) {
                        a.startActivity(new Intent(a, AddReelActivity.class));
                        return true;
                    }
                    if (id == 8) {
                        Fb.getUser(reel.authorId).addOnSuccessListener(u -> {
                            if (!com.friendbook.social.core.Admin.isAdmin() && com.friendbook.social.core.Admin.isProtectedStaff(u)) {
                                Ui.toast(a, "Admin reels cannot be removed by a moderator");
                                return;
                            }
                            Ui.confirm(a, "Delete Reel?", "Remove this reel permanently?", "Delete", () ->
                                    Fb.db().collection(reel.collectionName()).document(reel.id).delete()
                                            .addOnSuccessListener(x -> Ui.toast(a, "Reel deleted")));
                        });
                        return true;
                    }
                    if (id == 1) {
                        Reactions.showReelReactors(a, reel);
                    } else if (id == 2) {
                        Ui.choose(a, "Playback speed", new String[]{"0.5x Slow", "1.0x Normal", "1.5x Fast", "2.0x Turbo"}, w -> {
                            speed = w == 0 ? 0.5f : (w == 1 ? 1.0f : (w == 2 ? 1.5f : 2.0f));
                            if (activeMp != null && mpPrepared && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try { activeMp.setPlaybackParams(activeMp.getPlaybackParams().setSpeed(speed)); } catch (Throwable ignored) {}
                            }
                            Ui.toast(a, "Speed: " + speed + "x");
                        });
                    } else if (id == 3) {
                        try {
                            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
                            if (dm != null) {
                                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(reel.videoUrl));
                                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                String fn = "friendbook_reel_" + System.currentTimeMillis() + ".mp4";
                                req.setTitle(fn);
                                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
                                dm.enqueue(req);
                                Ui.toast(a, "Downloading Reel to Downloads…");
                            }
                        } catch (Throwable t) {
                            Ui.openUrl(a, reel.videoUrl);
                        }
                    } else if (id == 4) {
                        Ui.copy(a, reel.videoUrl);
                    } else if (id == 5) {
                        Intent dp = new Intent(a, FullscreenMediaActivity.class);
                        dp.putExtra("type", "image");
                        dp.putExtra("url", reel.authorPhoto);
                        dp.putExtra("title", reel.authorName + "'s Profile Picture");
                        a.startActivity(dp);
                    } else if (id == 17) {
                        Intent full = new Intent(a, FullscreenMediaActivity.class);
                        full.putExtra("type", "video");
                        full.putExtra("url", reel.videoUrl);
                        a.startActivity(full);
                    } else if (id == 6) {
                        final String[] reasons = {
                                "Nudity or explicit sexual video",
                                "Child safety / CSAE violation (Zero Tolerance)",
                                "Graphic violence or dangerous acts",
                                "Harassment, hate speech or bullying",
                                "Spam or misleading content"
                        };
                        Ui.choose(a, "Report Reel", reasons, w -> {
                            String reason = reasons[w];
                            Fb.db().collection("reports").add(Fb.map(
                                    "reelId", reel.id,
                                    "authorId", reel.authorId,
                                    "reason", reason,
                                    "reportedBy", Fb.uid(),
                                    "type", "reel",
                                    "createdAt", FieldValue.serverTimestamp()));
                            Prefs.hidePost(a, reel.id);
                            if (w == 0 || w == 1 || w == 2) {
                                ContentSafetyEngine.autoDeleteContent(
                                        a, reel.collectionName(), reel.id, reel.authorId,
                                        w == 1 ? "csae" : (w == 0 ? "adult_nudity" : "gore_violence"),
                                        reason, null);
                                Ui.toast(a, "Violating Reel removed & reported");
                            } else {
                                Ui.toast(a, "Reel hidden & reported");
                            }
                        });
                    } else if (id == 7) {
                        String myUid = Fb.uid();
                        if (myUid != null && reel.authorId != null) {
                            Ui.confirm(a, "Block " + reel.authorName + "?",
                                    "You won't see their reels, posts, stories, or messages anymore.",
                                    "Block", () -> {
                                        Fb.db().collection("users").document(myUid)
                                                .set(Fb.map("blockedUsers", FieldValue.arrayUnion(reel.authorId)), SetOptions.merge());
                                        if (Fb.me != null && Fb.me.blocked != null && !Fb.me.blocked.contains(reel.authorId)) {
                                            Fb.me.blocked.add(reel.authorId);
                                        }
                                        Ui.toast(a, "Blocked " + reel.authorName);
                                    });
                        }
                    }
                    return true;
                });
                m.show();
            }
        }
    }
}
