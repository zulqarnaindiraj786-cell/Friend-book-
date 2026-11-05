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

import com.friendbook.social.core.AdsConfig;
import com.friendbook.social.core.ConsentManager;
import com.friendbook.social.core.ContentSafetyEngine;
import com.friendbook.social.core.Fb;
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
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

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
 * - Automatic 3-Reel lookahead background prefetching (pos + 1, pos + 2, pos + 3)
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
    private int videoGeneration;
    private final java.util.Set<String> loadedVideoIds=new java.util.HashSet<>();
    private LinearLayout emptyState;
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
                            uploadStatusText.setText("⏫ " + (message != null && !message.isEmpty()
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
        // Pre-layout & pre-prepare 2 offscreen pages above/below for instant zero-lag swipes!
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
        TextView ei = Ui.tv(a, "🎬", 56, Color.WHITE);
        ei.setGravity(Gravity.CENTER);
        emptyState.addView(ei);
        TextView et1 = Ui.tvBold(a, "No Reels yet — upload the first one!", 19, Color.WHITE);
        et1.setGravity(Gravity.CENTER);
        et1.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
        emptyState.addView(et1);
        TextView et2 = Ui.tv(a, "Share short vertical videos with instant playback, reactions, comments & shares!", 13.5f, 0xBBFFFFFF);
        et2.setGravity(Gravity.CENTER);
        emptyState.addView(et2);
        TextView createNow = Ui.tvBold(a, "🎬 ＋ Upload Reel", 15, Color.WHITE);
        createNow.setBackground(Ui.roundStroke(a, 0xFFE41E3F, 22, 0xFFFFFFFF, 1));
        Ui.pad(createNow, 22, 11, 22, 11);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = Ui.dp(a, 18);
        createNow.setOnClickListener(v -> startActivity(new Intent(a, AddReelActivity.class)));
        emptyState.addView(createNow, clp);
        root.addView(emptyState, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

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

        TextView refreshPill = Ui.tvBold(a, "↻", 15f, Color.WHITE);
        refreshPill.setGravity(Gravity.CENTER);
        refreshPill.setBackground(Ui.roundStroke(a, 0x66000000, 15, 0x44FFFFFF, 1));
        int rSz = Ui.dp(a, 30);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(rSz, rSz);
        rlp.leftMargin = Ui.dp(a, 5);
        refreshPill.setOnClickListener(v -> {
            refreshWithAlgorithm();
            Ui.toast(a, "🎬 Fresh reels loaded");
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
        uploadStatusText = Ui.tvBold(a, "⏫ Uploading Reel…", 12f, Color.WHITE);
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
            public void onPageSelected(int position) {
                adapter.playOnly(pager, position);
                Models.Reel cur = adapter.reelAtPosition(position);
                if (cur != null && cur.id != null) {
                    FeedAlgorithm.recordVideoImpression(getActivity(), cur.id);
                }
                int rIdx = adapter.reelIndexForPos(position);
                if(rIdx>=adapter.rawReels().size()-3) loadNextVideos();
            }
        });
        return root;
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
            sessionReelOrderIds.clear();
            sessionReelOrderIds.addAll(updatedOrder);
        } else {
            sessionReelOrderIds.clear();
            merged = FeedAlgorithm.rankReels(act, candidates, initialReelId);
            if (optimistic != null) {
                merged.remove(optimistic);
                merged.add(0, optimistic);
            }
            for (Models.Reel r : merged) {
                if (r != null && r.id != null) sessionReelOrderIds.add(r.id);
            }
        }

        if (emptyState != null) {
            emptyState.setVisibility(merged.isEmpty() ? View.VISIBLE : View.GONE);
        }

        boolean wasEmpty = adapter.rawReels().isEmpty();
        adapter.setData(pager, merged, forceReset);

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
        resetVideoPages();

        if (!isHidden() && pager != null && adapter != null && adapter.getItemCount() > 0) {
            pager.post(() -> {
                if (!isHidden() && pager != null) adapter.playOnly(pager, pager.getCurrentItem());
            });
        }
    }

    private void resetVideoPages() {
        videoGeneration++; pagingVideos=false; moreReels=true; moreVideoPosts=true;
        reelsCursor=null; postsCursor=null; loadedVideoIds.clear(); fromReels.clear(); fromPosts.clear();
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
        if(loadedVideoIds.add((nativeReel?"reels/":"posts/")+doc.getId())) (nativeReel?fromReels:fromPosts).add(reel);
        boolean linked=initialReelId!=null && initialReelId.equals(doc.getId());
        rebuildMergedFeed(linked);
        if(linked && pager!=null) pager.setCurrentItem(0,false);
    }
    private void loadNextVideos() {
        if(pagingVideos || (!moreReels && !moreVideoPosts) || !isAdded()) return;
        pagingVideos=true; final int generation=videoGeneration; final String actor=Fb.uid();
        // Two sources contribute five metadata documents each: at most ten per batch.
        final int[] pending={(moreReels?1:0)+(moreVideoPosts?1:0)};
        Runnable done=() -> { if(generation!=videoGeneration) return; if(--pending[0]==0) { pagingVideos=false; rebuildMergedFeed(false); } };
        for(boolean nativeReel:new boolean[]{true,false}) {
            if(nativeReel?!moreReels:!moreVideoPosts) continue;
            Query query=nativeReel?Fb.db().collection("reels").orderBy("createdAt",Query.Direction.DESCENDING)
                    :Fb.db().collection("posts").whereEqualTo("mediaType","video");
            DocumentSnapshot cursor=nativeReel?reelsCursor:postsCursor;
            if(cursor!=null) query=query.startAfter(cursor);
            query.limit(5).get().addOnSuccessListener(page -> {
                if(generation!=videoGeneration || !java.util.Objects.equals(actor,Fb.uid()) || !isAdded()) return;
                if(nativeReel) { moreReels=page.size()==5; if(!page.isEmpty()) reelsCursor=page.getDocuments().get(page.size()-1); }
                else { moreVideoPosts=page.size()==5; if(!page.isEmpty()) postsCursor=page.getDocuments().get(page.size()-1); }
                for(DocumentSnapshot doc:page) {
                    if(Boolean.TRUE.equals(doc.getBoolean("safetyFlagged"))) continue;
                    Models.Reel reel=nativeReel?Models.Reel.fromDoc(doc):Models.Reel.fromVideoPost(Models.Post.fromDoc(doc));
                    if(reel.videoUrl==null || reel.videoUrl.isEmpty()) continue;
                    if(Fb.me!=null && Fb.me.blocked.contains(reel.authorId)) continue;
                    if(Prefs.isPostHidden(getActivity(),reel.id)) continue;
                    if(ContentSafetyEngine.checkAndAutoDeleteIfTextUnsafe(getActivity(),nativeReel?"reels":"posts",reel.id,reel.authorId,reel.caption,reel.videoUrl,null)) continue;
                    if(loadedVideoIds.add((nativeReel?"reels/":"posts/")+doc.getId())) (nativeReel?fromReels:fromPosts).add(reel);
                }
                done.run();
            }).addOnFailureListener(error -> { if(generation==videoGeneration) { done.run(); if(getActivity()!=null) Ui.toast(getActivity(),"Couldn't load videos — tap More videos to retry"); } });
        }
    }

    @Override public void onResume(){super.onResume();if(pager!=null && adapter!=null)pager.post(()->adapter.playOnly(pager,pager.getCurrentItem()));}
    @Override public void onPause(){if(adapter!=null && pager!=null)adapter.pauseAll(pager);super.onPause();}

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (adapter == null || pager == null) return;
        if (hidden) {
            adapter.pauseAll(pager);
        } else {
            pager.post(() -> {
                adapter.playOnly(pager, pager.getCurrentItem());
            });
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        videoGeneration++; pagingVideos=false;
        ReelUploadManager.removeListener(bgUploadListener);
        if (reelsReg != null) { reelsReg.remove(); reelsReg = null; }
        if (videoPostsReg != null) { videoPostsReg.remove(); videoPostsReg = null; }
        if (adapter != null && pager != null) adapter.pauseAll(pager);
    }

    // ================== Reel Adapter (Reels + Production Reels Native Ads) ==================

    static class ReelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VT_REEL = 0;
        private static final int VT_REEL_AD = 1;
        private static final int REELS_PER_AD = 3;
        private static final int REEL_AD_STEP = REELS_PER_AD + 1;

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
                    && data.size() >= REELS_PER_AD && isPrefixMatch(data, incoming)) {
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

        private boolean adsActive() {
            return AdsConfig.nativeAdsEnabled();
        }

        private int adCount() {
            if (!adsActive() || data.isEmpty()) return 0;
            if (data.size() < REELS_PER_AD) return 1;
            return data.size() / REELS_PER_AD;
        }

        boolean isAdPos(int pos) {
            if (!adsActive() || data.isEmpty() || pos < 0) return false;
            if (data.size() < REELS_PER_AD) {
                return pos == data.size();
            }
            return ((pos + 1) % REEL_AD_STEP == 0) && ((pos / REEL_AD_STEP) < adCount());
        }

        int reelIndexForPos(int pos) {
            if (!adsActive() || data.size() < REELS_PER_AD) return pos;
            return pos - ((pos + 1) / REEL_AD_STEP);
        }

        Models.Reel reelAtPosition(int pos) {
            if (isAdPos(pos)) return null;
            int idx = reelIndexForPos(pos);
            return (idx >= 0 && idx < data.size()) ? data.get(idx) : null;
        }

        void pauseAll(ViewPager2 pager) {
            playingPos = -1;
            if (pager == null || !(pager.getChildAt(0) instanceof RecyclerView)) return;
            RecyclerView rv = (RecyclerView) pager.getChildAt(0);
            for (int i = 0; i < rv.getChildCount(); i++) {
                RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
                if (h instanceof VH) ((VH) h).pause();
            }
        }

        void playOnly(ViewPager2 pager, int pos) {
            if(!visible.getAsBoolean()){pauseAll(pager);return;}
            playingPos = pos;
            if (pager == null || !(pager.getChildAt(0) instanceof RecyclerView)) return;
            RecyclerView rv = (RecyclerView) pager.getChildAt(0);
            for (int i = 0; i < rv.getChildCount(); i++) {
                RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
                if (h instanceof VH) {
                    VH vh = (VH) h;
                    if (!isAdPos(pos) && vh.getAdapterPosition() == pos) vh.play();
                    else vh.pause();
                }
            }
        }

        @Override public int getItemCount() { return data.size() + adCount(); }

        @Override
        public int getItemViewType(int position) {
            return isAdPos(position) ? VT_REEL_AD : VT_REEL;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VT_REEL_AD) {
                return new ReelAdVH(activity);
            }
            return new VH(activity);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (h instanceof ReelAdVH) {
                int adSlotOrdinal = Math.max(0, position / REEL_AD_STEP);
                ((ReelAdVH) h).loadFreshReelsNativeAd(AdsConfig.reelsNativeId(), adSlotOrdinal);
            } else if (h instanceof VH) {
                Models.Reel r = reelAtPosition(position);
                if (r != null) {
                    VH vh = (VH) h;
                    vh.bind(r, visible.getAsBoolean() && position == playingPos);
                }
            }
        }

        @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder h){
            super.onViewAttachedToWindow(h);
            if(h instanceof VH && visible.getAsBoolean() && h.getBindingAdapterPosition()==playingPos)((VH)h).play();
        }
        @Override public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder h){
            if(h instanceof VH)((VH)h).pause();super.onViewDetachedFromWindow(h);
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder h) {
            super.onViewRecycled(h);
            if (h instanceof VH) {
                ((VH) h).releasePlayer();
            } else if (h instanceof ReelAdVH) {
                ((ReelAdVH) h).onRecycled();
            }
        }

        /**
         * 📢 Full-Screen Reels Production Native Ad ViewHolder (`ca-app-pub-3387480177023669/6468085788`):
         * - Loads a FRESH NativeAd via `AdLoader.Builder` every time it binds (never reuses old/failed ads).
         * - Logs exact `adError.getCode()` + details in Logcat (`FriendBookAds`) via `AdListener`.
         */
        static class ReelAdVH extends RecyclerView.ViewHolder {
            final com.google.android.gms.ads.nativead.NativeAdView nativeAdView;
            final com.google.android.gms.ads.nativead.MediaView mediaView;
            final ImageView iconView;
            final TextView headlineView, advertiserView, bodyView, storeView, priceView, statusView;
            final android.widget.RatingBar starsView;
            final android.widget.Button ctaButton;
            final LinearLayout loadingBox;
            com.google.android.gms.ads.nativead.NativeAd currentNativeAd;
            int requestSeq = 0;

            ReelAdVH(Activity a) {
                super(buildAdRoot(a));
                FrameLayout root = (FrameLayout) itemView;

                loadingBox = Ui.col(a);
                loadingBox.setGravity(Gravity.CENTER);
                Ui.pad(loadingBox, 24, 24, 24, 24);
                TextView spChip = Ui.roundChip(a, "Sponsored", 0x33FFFFFF, Color.WHITE, 12);
                Ui.pad(spChip, 12, 5, 12, 5);
                loadingBox.addView(spChip);
                statusView = Ui.tv(a, "Loading Sponsored Reel…", 13.5f, 0xFFBBBBBB);
                statusView.setGravity(Gravity.CENTER);
                statusView.setPadding(0, Ui.dp(a, 12), 0, 0);
                loadingBox.addView(statusView);
                root.addView(loadingBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

                nativeAdView = new com.google.android.gms.ads.nativead.NativeAdView(a);
                nativeAdView.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
                nativeAdView.setPadding(0, Ui.dp(a, 58), 0, 0);
                nativeAdView.setVisibility(View.GONE);

                FrameLayout inner = new FrameLayout(a);
                inner.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

                mediaView = new com.google.android.gms.ads.nativead.MediaView(a);
                mediaView.setImageScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
                mlp.bottomMargin = Ui.dp(a, 150);
                inner.addView(mediaView, mlp);

                TextView badge = Ui.tvBold(a, "Sponsored", 11.5f, 0xFFFFD54F);
                badge.setBackground(Ui.roundStroke(a, 0xAA000000, 12, 0x55FFD54F, 1));
                Ui.pad(badge, 10, 4, 10, 4);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                blp.setMargins(Ui.dp(a, 14), Ui.dp(a, 8), 0, 0);
                inner.addView(badge, blp);

                LinearLayout bottomCard = Ui.col(a);
                bottomCard.setBackground(Ui.roundStroke(a, 0xDD121418, 16, 0x33FFFFFF, 1));
                Ui.pad(bottomCard, 14, 12, 14, 14);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
                clp.setMargins(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 18));

                LinearLayout headerRow = Ui.row(a);
                headerRow.setGravity(Gravity.CENTER_VERTICAL);
                iconView = new ImageView(a);
                int iconSz = Ui.dp(a, 42);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconSz, iconSz);
                ilp.rightMargin = Ui.dp(a, 10);
                iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                headerRow.addView(iconView, ilp);

                LinearLayout titleCol = Ui.col(a);
                headlineView = Ui.tvBold(a, "Sponsored", 15.5f, Color.WHITE);
                headlineView.setMaxLines(2);
                titleCol.addView(headlineView);

                LinearLayout subRow = Ui.row(a);
                subRow.setGravity(Gravity.CENTER_VERTICAL);
                advertiserView = Ui.tv(a, "", 12f, 0xFFCCCCCC);
                advertiserView.setSingleLine(true);
                subRow.addView(advertiserView);

                starsView = new android.widget.RatingBar(a, null, android.R.attr.ratingBarStyleSmall);
                starsView.setNumStars(5);
                starsView.setStepSize(0.5f);
                starsView.setIsIndicator(true);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
                slp.leftMargin = Ui.dp(a, 6);
                subRow.addView(starsView, slp);
                titleCol.addView(subRow);

                headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

                LinearLayout metaRight = Ui.col(a);
                metaRight.setGravity(Gravity.END);
                storeView = Ui.tv(a, "", 11f, 0xFFAAAAAA);
                priceView = Ui.tvBold(a, "", 11.5f, 0xFF81C784);
                metaRight.addView(storeView);
                metaRight.addView(priceView);
                headerRow.addView(metaRight);
                bottomCard.addView(headerRow);

                bodyView = Ui.tv(a, "", 13f, 0xFFE4E6EB);
                bodyView.setMaxLines(2);
                bodyView.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 10));
                bottomCard.addView(bodyView);

                ctaButton = new android.widget.Button(a);
                ctaButton.setAllCaps(false);
                ctaButton.setTextColor(Color.WHITE);
                ctaButton.setTextSize(14.5f);
                ctaButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                ctaButton.setBackground(Ui.round(a, Ui.BLUE, 10));
                bottomCard.addView(ctaButton, new LinearLayout.LayoutParams(-1, Ui.dp(a, 44)));

                inner.addView(bottomCard, clp);
                nativeAdView.addView(inner);
                root.addView(nativeAdView);

                nativeAdView.setMediaView(mediaView);
                nativeAdView.setHeadlineView(headlineView);
                nativeAdView.setBodyView(bodyView);
                nativeAdView.setCallToActionView(ctaButton);
                nativeAdView.setIconView(iconView);
                nativeAdView.setAdvertiserView(advertiserView);
                nativeAdView.setStarRatingView(starsView);
                nativeAdView.setStoreView(storeView);
                nativeAdView.setPriceView(priceView);
            }

            private static FrameLayout buildAdRoot(Activity a) {
                FrameLayout f = new FrameLayout(a);
                f.setBackgroundColor(0xFF0A0B0E);
                f.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
                return f;
            }

            private void destroyCurrentAd() {
                if (currentNativeAd != null) {
                    try { currentNativeAd.destroy(); } catch (Throwable ignored) {}
                    currentNativeAd = null;
                }
            }

            void loadFreshReelsNativeAd(final String rawUnitId, final int slotOrdinal) {
                final int mySeq = ++requestSeq;
                destroyCurrentAd();
                nativeAdView.setVisibility(View.GONE);
                loadingBox.setVisibility(View.VISIBLE);
                statusView.setText("Loading Sponsored Reel…");

                final Context ctx = itemView.getContext();
                final String unitId = AdsConfig.isRealProductionUnit(rawUnitId)
                        ? rawUnitId.trim()
                        : AdsConfig.REELS_NATIVE_ID;

                ConsentManager.ensureInitialized(ctx);
                AdsConfig.recordAdRequest(ctx);
                android.util.Log.i(AdsConfig.LOG_TAG,
                        "[ReelsNative] Requesting fresh NativeAd slot=" + slotOrdinal + " unitId=" + unitId);

                try {
                    boolean startMuted = !Prefs.reelsStartWithSound(ctx);
                    com.google.android.gms.ads.VideoOptions videoOptions =
                            new com.google.android.gms.ads.VideoOptions.Builder()
                                    .setStartMuted(startMuted)
                                    .build();
                    com.google.android.gms.ads.nativead.NativeAdOptions adOptions =
                            new com.google.android.gms.ads.nativead.NativeAdOptions.Builder()
                                    .setAdChoicesPlacement(
                                            com.google.android.gms.ads.nativead.NativeAdOptions.ADCHOICES_TOP_RIGHT)
                                    .setMediaAspectRatio(
                                            com.google.android.gms.ads.nativead.NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_PORTRAIT)
                                    .setVideoOptions(videoOptions)
                                    .build();

                    com.google.android.gms.ads.AdLoader adLoader =
                            new com.google.android.gms.ads.AdLoader.Builder(ctx, unitId)
                                    .forNativeAd(nativeAd -> {
                                        if (mySeq != requestSeq) {
                                            try { nativeAd.destroy(); } catch (Throwable ignored) {}
                                            return;
                                        }
                                        destroyCurrentAd();
                                        currentNativeAd = nativeAd;
                                        AdsConfig.onNativeAdLoaded(ctx);
                                        nativeAd.setOnPaidEventListener(adValue ->
                                                AdsConfig.recordPaidEvent(ctx, adValue));

                                        loadingBox.setVisibility(View.GONE);
                                        nativeAdView.setVisibility(View.VISIBLE);
                                        populateReelsNativeAd(nativeAd, ctx);
                                        nativeAdView.requestLayout();
                                    })
                                    .withAdListener(new com.google.android.gms.ads.AdListener() {
                                        @Override
                                        public void onAdLoaded() {
                                            android.util.Log.i(AdsConfig.LOG_TAG,
                                                    "[ReelsNative] Ad loaded successfully (slot=" + slotOrdinal
                                                            + ", unitId=" + unitId + ")");
                                        }

                                        @Override
                                        public void onAdImpression() {
                                            android.util.Log.i(AdsConfig.LOG_TAG,
                                                    "[ReelsNative] Ad impression recorded (slot=" + slotOrdinal
                                                            + ", unitId=" + unitId + ")");
                                            AdsConfig.recordAdImpression(ctx);
                                        }

                                        @Override
                                        public void onAdClicked() {
                                            android.util.Log.i(AdsConfig.LOG_TAG,
                                                    "[ReelsNative] Ad clicked (slot=" + slotOrdinal
                                                            + ", unitId=" + unitId + ")");
                                            AdsConfig.recordAdClick(ctx);
                                        }

                                        @Override
                                        public void onAdFailedToLoad(@NonNull com.google.android.gms.ads.LoadAdError adError) {
                                            android.util.Log.e(AdsConfig.LOG_TAG,
                                                    "[ReelsNative] Ad failed to load! errorCode=" + adError.getCode()
                                                            + ", message=" + adError.getMessage()
                                                            + ", domain=" + adError.getDomain()
                                                            + ", slot=" + slotOrdinal
                                                            + ", unitId=" + unitId
                                                            + ", responseInfo=" + adError.getResponseInfo());
                                            AdsConfig.onNativeAdFailed(ctx, adError.getCode());
                                            if (mySeq == requestSeq) {
                                                destroyCurrentAd();
                                                nativeAdView.setVisibility(View.GONE);
                                                loadingBox.setVisibility(View.VISIBLE);
                                                statusView.setText("Swipe up to continue watching Reels ↑");
                                            }
                                        }
                                    })
                                    .withNativeAdOptions(adOptions)
                                    .build();

                    adLoader.loadAd(new com.google.android.gms.ads.AdRequest.Builder().build());
                } catch (Throwable t) {
                    android.util.Log.e(AdsConfig.LOG_TAG,
                            "[ReelsNative] Exception loading ad (unitId=" + unitId + "): " + t.getMessage(), t);
                    AdsConfig.onNativeAdFailed(ctx, -1);
                    destroyCurrentAd();
                    nativeAdView.setVisibility(View.GONE);
                    loadingBox.setVisibility(View.VISIBLE);
                    statusView.setText("Swipe up to continue watching Reels ↑");
                }
            }

            private void populateReelsNativeAd(com.google.android.gms.ads.nativead.NativeAd ad, Context c) {
                headlineView.setText(ad.getHeadline() != null ? ad.getHeadline() : "Sponsored");

                if (ad.getMediaContent() != null) {
                    mediaView.setMediaContent(ad.getMediaContent());
                    mediaView.setVisibility(View.VISIBLE);
                } else {
                    mediaView.setVisibility(View.GONE);
                }

                if (ad.getBody() == null || ad.getBody().isEmpty()) {
                    bodyView.setVisibility(View.GONE);
                } else {
                    bodyView.setVisibility(View.VISIBLE);
                    bodyView.setText(ad.getBody());
                }

                if (ad.getCallToAction() == null || ad.getCallToAction().isEmpty()) {
                    ctaButton.setVisibility(View.GONE);
                } else {
                    ctaButton.setVisibility(View.VISIBLE);
                    ctaButton.setText(ad.getCallToAction());
                }

                if (ad.getIcon() == null) {
                    iconView.setVisibility(View.GONE);
                } else {
                    iconView.setImageDrawable(ad.getIcon().getDrawable());
                    iconView.setClipToOutline(true);
                    iconView.setBackground(Ui.circle(0xFF242526));
                    iconView.setVisibility(View.VISIBLE);
                }

                if (ad.getAdvertiser() == null || ad.getAdvertiser().isEmpty()) {
                    advertiserView.setVisibility(View.GONE);
                } else {
                    advertiserView.setText(ad.getAdvertiser());
                    advertiserView.setVisibility(View.VISIBLE);
                }

                if (ad.getStarRating() == null) {
                    starsView.setVisibility(View.GONE);
                } else {
                    starsView.setRating(ad.getStarRating().floatValue());
                    starsView.setVisibility(View.VISIBLE);
                }

                if (ad.getStore() == null || ad.getStore().isEmpty()) {
                    storeView.setVisibility(View.GONE);
                } else {
                    storeView.setText(ad.getStore());
                    storeView.setVisibility(View.VISIBLE);
                }

                if (ad.getPrice() == null || ad.getPrice().isEmpty()) {
                    priceView.setVisibility(View.GONE);
                } else {
                    priceView.setText(ad.getPrice());
                    priceView.setVisibility(View.VISIBLE);
                }

                nativeAdView.setNativeAd(ad);
            }

            void onRecycled() {
                requestSeq++;
                destroyCurrentAd();
            }
        }

        // ================== Ultra-Fast TextureView + SSD Cache Reel ViewHolder ==================

        class VH extends RecyclerView.ViewHolder {
            final TextureView textureView;
            final ImageView posterView;
            final ProgressBar bufferingSpinner;
            final ProgressBar scrubBar;
            final TextView pausedBadge;
            final FrameLayout uploadRailBtnBox, likeBtnBox, commentBtnBox, shareBtnBox, saveBtnBox, soundBtnBox, moreBtnBox;
            final ImageView likeOutlineIv;
            final TextView likeIcon, likeCount, commentIcon, commentCount, shareIcon, shareCount;
            final TextView saveIcon, soundIcon, heartBurst, nameT, captionT, audioT, followT, quickCommentPill;
            final ImageView avatar, tick;

            MediaPlayer activeMp;
            Surface videoSurface;
            boolean mpPrepared = false;
            boolean wantPlay = false;
            boolean userPaused = false;
            boolean triedRawFallback = false;
            int videoW = 0, videoH = 0;
            String boundVideoUrl = "";
            boolean muted;
            float speed = 1.0f;
            Models.Reel reel;

            private final Handler uiHandler = new Handler(Looper.getMainLooper());
            private final Runnable progressTicker = new Runnable() {
                @Override
                public void run() {
                    if (activeMp != null && mpPrepared && wantPlay && !userPaused) {
                        try {
                            int dur = activeMp.getDuration();
                            int pos = activeMp.getCurrentPosition();
                            if (dur > 0) {
                                scrubBar.setProgress((int) ((pos * 1000L) / dur));
                            }
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
                posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                root.addView(posterView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

                // 3. Subtle center buffering indicator & pause indicator
                bufferingSpinner = new ProgressBar(a);
                bufferingSpinner.setVisibility(View.GONE);
                int spSz = Ui.dp(a, 38);
                root.addView(bufferingSpinner, new FrameLayout.LayoutParams(spSz, spSz, Gravity.CENTER));

                pausedBadge = Ui.tvBold(a, "▶", 38, 0xDDFFFFFF);
                pausedBadge.setGravity(Gravity.CENTER);
                pausedBadge.setBackground(Ui.circle(0x66000000));
                pausedBadge.setVisibility(View.GONE);
                int pbSz = Ui.dp(a, 68);
                root.addView(pausedBadge, new FrameLayout.LayoutParams(pbSz, pbSz, Gravity.CENTER));

                // Giant animated heart burst on double-tap!
                heartBurst = Ui.tv(a, "❤️", 82, Color.WHITE);
                heartBurst.setAlpha(0f);
                heartBurst.setElevation(Ui.dp(a, 30));
                root.addView(heartBurst, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

                // Right-Side Action Rail (Upload · Like · Comment · Share · Save · Sound · •••)
                LinearLayout rail = Ui.col(a);
                rail.setGravity(Gravity.CENTER_HORIZONTAL);
                rail.setElevation(Ui.dp(a, 24));
                FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
                rp.setMargins(0, 0, Ui.dp(a, 10), Ui.dp(a, 58));
                rail.setLayoutParams(rp);

                // Dedicated eye-catching "＋ Upload" circle button right on the thumb rail!
                TextView upPlus = Ui.tvBold(a, "＋", 23, Color.WHITE);
                upPlus.setGravity(Gravity.CENTER);
                uploadRailBtnBox = new FrameLayout(a);
                uploadRailBtnBox.setBackground(Ui.roundStroke(a, 0xFFE41E3F, 22, Color.WHITE, 1));
                int upSz = Ui.dp(a, 44);
                uploadRailBtnBox.setLayoutParams(new LinearLayout.LayoutParams(upSz, upSz));
                uploadRailBtnBox.addView(upPlus, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                uploadRailBtnBox.setOnClickListener(v -> a.startActivity(new Intent(a, AddReelActivity.class)));
                TextView uploadRailLabel = shadowLabel(a, "Upload");
                uploadRailLabel.setOnClickListener(v -> a.startActivity(new Intent(a, AddReelActivity.class)));

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

                commentIcon = Ui.tv(a, "💬", 21, Color.WHITE);
                commentIcon.setGravity(Gravity.CENTER);
                commentBtnBox = circleAction(a, commentIcon);
                commentCount = shadowLabel(a, "Comment");

                shareIcon = Ui.tvBold(a, "↗", 22, Color.WHITE);
                shareIcon.setGravity(Gravity.CENTER);
                shareBtnBox = circleAction(a, shareIcon);
                shareCount = shadowLabel(a, "Share");

                saveIcon = Ui.tv(a, "🔖", 19, Color.WHITE);
                saveIcon.setGravity(Gravity.CENTER);
                saveBtnBox = circleAction(a, saveIcon);

                soundIcon = Ui.tv(a, muted ? "🔇" : "🔊", 19, Color.WHITE);
                soundIcon.setGravity(Gravity.CENTER);
                soundBtnBox = circleAction(a, soundIcon);

                TextView moreIcon = Ui.tvBold(a, "•••", 16, Color.WHITE);
                moreIcon.setGravity(Gravity.CENTER);
                moreBtnBox = circleAction(a, moreIcon);

                rail.addView(uploadRailBtnBox);
                rail.addView(uploadRailLabel);
                rail.addView(spacer(a, 10));
                rail.addView(likeBtnBox);
                rail.addView(likeCount);
                rail.addView(spacer(a, 10));
                rail.addView(commentBtnBox);
                rail.addView(commentCount);
                rail.addView(spacer(a, 10));
                rail.addView(shareBtnBox);
                rail.addView(shareCount);
                rail.addView(spacer(a, 10));
                rail.addView(saveBtnBox);
                rail.addView(spacer(a, 8));
                rail.addView(soundBtnBox);
                rail.addView(spacer(a, 8));
                rail.addView(moreBtnBox);
                root.addView(rail);

                // Bottom Creator Info + Quick "Add a comment…" + Ultra-Thin Playback Scrub Bar
                LinearLayout bottomCol = Ui.col(a);
                bottomCol.setElevation(Ui.dp(a, 22));
                bottomCol.setBackgroundColor(0x4D000000);
                Ui.pad(bottomCol, 14, 10, 72, 10);
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

                quickCommentPill = Ui.tv(a, "💬  Add a comment…", 13, 0xEEFFFFFF);
                quickCommentPill.setBackground(Ui.roundStroke(a, 0x44FFFFFF, 18, 0x55FFFFFF, 1));
                Ui.pad(quickCommentPill, 14, 7, 14, 7);
                bottomCol.addView(quickCommentPill, new LinearLayout.LayoutParams(-1, -2));

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
                            prepareMediaPlayerAsync();
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
                    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) { }
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
                quickCommentPill.setOnClickListener(openCommentsListener);

                View.OnClickListener openShareListener = v -> shareSheet(a);
                shareBtnBox.setOnClickListener(openShareListener);
                shareCount.setOnClickListener(openShareListener);

                saveBtnBox.setOnClickListener(v -> toggleSaveReel(a));

                soundBtnBox.setOnClickListener(v -> {
                    muted = !muted;
                    soundIcon.setText(muted ? "🔇" : "🔊");
                    if (activeMp != null) {
                        try { activeMp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Throwable ignored) {}
                    }
                });

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

                audioT.setText("♫ Original audio · " + (reel.authorName == null ? "friendbook" : reel.authorName));

                String me = Fb.uid();
                boolean isMe = me != null && me.equals(reel.authorId);
                followT.setVisibility(isMe ? View.GONE : View.VISIBLE);
                if (!isMe && Fb.me != null && Fb.me.following != null) {
                    followT.setText(Fb.me.following.contains(reel.authorId) ? "Following ✓" : "Follow");
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
                scrubBar.setProgress(0);

                // 0.0ms Instant Poster Frame so swiping NEVER shows a black screen!
                Context c = itemView.getContext();
                String thumbUrl = ReelVideoCache.deriveThumbUrl(r.videoUrl, r.thumbUrl);
                if (!thumbUrl.isEmpty()) {
                    posterView.setVisibility(View.VISIBLE);
                    Ui.img(c, posterView, thumbUrl);
                } else {
                    posterView.setImageDrawable(null);
                    posterView.setVisibility(View.GONE);
                }

                pausedBadge.setVisibility(View.GONE);
                // Never prepare or download unrequested/offscreen videos.
                if (textureView.isAvailable()) {
                    if (videoSurface == null && textureView.getSurfaceTexture() != null) {
                        videoSurface = new Surface(textureView.getSurfaceTexture());
                    }
                    prepareMediaPlayerAsync();
                }
            }

            private void prepareMediaPlayerAsync() {
                if (!wantPlay || getBindingAdapterPosition()!=playingPos || reel == null || reel.videoUrl == null || reel.videoUrl.isEmpty()) return;
                releasePlayerOnly();

                final Context ctx = itemView.getContext();
                final String targetUrl = reel.videoUrl;
                mpPrepared = false;

                File cached = ReelVideoCache.getCachedFile(ctx, targetUrl);
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

                    mp.setOnInfoListener((player, what, extra) -> {
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                            posterView.setVisibility(View.GONE);
                            bufferingSpinner.setVisibility(View.GONE);
                        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START && wantPlay) {
                            bufferingSpinner.setVisibility(View.VISIBLE);
                        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                            bufferingSpinner.setVisibility(View.GONE);
                        }
                        return false;
                    });

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
                        if (!triedRawFallback && reel != null && reel.videoUrl != null) {
                            triedRawFallback = true;
                            prepareWithRawUrlFallback(ctx, reel.videoUrl);
                            return true;
                        }
                        return true;
                    });

                    if (cached != null) {
                        try (FileInputStream fis = new FileInputStream(cached)) {
                            mp.setDataSource(fis.getFD());
                        }
                    } else if (targetUrl.startsWith("file:") || targetUrl.startsWith("content:")) {
                        mp.setDataSource(ctx, Uri.parse(targetUrl));
                    } else {
                        String fastUrl = triedRawFallback
                                ? targetUrl
                                : ReelVideoCache.optimizeStreamUrl(ctx, targetUrl);
                        mp.setDataSource(ctx, Uri.parse(fastUrl));
                        // One stream only: a parallel full download competes with playback on mobile data.
                    }
                    mp.prepareAsync();
                } catch (Throwable t) {
                    bufferingSpinner.setVisibility(View.GONE);
                    if (!triedRawFallback && reel != null && reel.videoUrl != null) {
                        triedRawFallback = true;
                        prepareWithRawUrlFallback(ctx, reel.videoUrl);
                    }
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
                    mp.setOnInfoListener((p, what, extra) -> {
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                            posterView.setVisibility(View.GONE);
                            bufferingSpinner.setVisibility(View.GONE);
                        }
                        return false;
                    });
                    mp.setDataSource(ctx, Uri.parse(rawUrl));
                    mp.prepareAsync();
                } catch (Throwable ignored) { }
            }

            private void applyVideoTransform(int viewW, int viewH) {
                if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return;
                float viewRatio = (float) viewW / viewH;
                float videoRatio = (float) videoW / videoH;
                Matrix matrix = new Matrix();
                float sx, sy;
                // Portrait / vertical videos fill full screen (CENTER_CROP); landscape videos FIT_CENTER
                boolean isVerticalVideo = videoH >= videoW * 0.95f;
                if (isVerticalVideo) {
                    if (videoRatio > viewRatio) {
                        sx = videoRatio / viewRatio;
                        sy = 1f;
                    } else {
                        sx = 1f;
                        sy = viewRatio / videoRatio;
                    }
                } else {
                    if (videoRatio > viewRatio) {
                        sx = 1f;
                        sy = viewRatio / videoRatio;
                    } else {
                        sx = videoRatio / viewRatio;
                        sy = 1f;
                    }
                }
                matrix.setScale(sx, sy, viewW / 2f, viewH / 2f);
                textureView.setTransform(matrix);
            }

            private void startPreparedPlayer() {
                if (!wantPlay || userPaused || getBindingAdapterPosition()!=playingPos || activeMp == null || !mpPrepared) return;
                try {
                    float vol = muted ? 0f : 1f;
                    activeMp.setVolume(vol, vol);
                    if (!activeMp.isPlaying()) activeMp.start();
                    pausedBadge.setVisibility(View.GONE);
                    // Safety hide poster 90ms after start on devices that omit MEDIA_INFO_VIDEO_RENDERING_START
                    uiHandler.postDelayed(() -> {
                        if (wantPlay && activeMp != null && mpPrepared) {
                            posterView.setVisibility(View.GONE);
                            bufferingSpinner.setVisibility(View.GONE);
                        }
                    }, 90L);
                    uiHandler.removeCallbacks(progressTicker);
                    uiHandler.post(progressTicker);
                } catch (Throwable ignored) { }
            }

            private void togglePlayPause() {
                if (activeMp == null || !mpPrepared) { play(); return; }
                try {
                    if (activeMp.isPlaying()) {
                        userPaused = true;
                        activeMp.pause();
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
                }
            }

            void pause() {
                wantPlay = false;
                uiHandler.removeCallbacks(progressTicker);
                bufferingSpinner.setVisibility(View.GONE);
                pausedBadge.setVisibility(View.GONE);
                if (activeMp != null && mpPrepared) {
                    try {
                        if (activeMp.isPlaying()) activeMp.pause();
                    } catch (Throwable ignored) { }
                }
            }

            private void releasePlayerOnly() {
                uiHandler.removeCallbacks(progressTicker);
                mpPrepared = false;
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
                Fb.db().collection("posts").document(targetId).collection("reactions")
                        .limit(200)
                        .get()
                        .addOnSuccessListener(snap -> {
                            if (snap == null || isReelReactionInFlight(targetId)) return;
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
                        });
            }

            private void renderCounts() {
                if (reel == null) return;
                Context c = itemView.getContext();
                if(com.friendbook.social.core.D1Client.enabled(reel.isFromPost?"posts":"reels")){final Models.Reel current=reel;com.friendbook.social.post.D1Reactions.reel(c,current,()->{if(reel==current)renderCounts();});}
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
                saveBtnBox.setBackground(Ui.roundStroke(c,
                        saved ? 0x881877F2 : 0x66000000, 22,
                        saved ? 0xFF1877F2 : 0x33FFFFFF, 1));
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

                    com.google.firebase.firestore.DocumentReference postRef =
                            Fb.db().collection("posts").document(reel.id);
                    com.google.firebase.firestore.DocumentReference subRef =
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
                    com.google.firebase.firestore.DocumentReference reelRef =
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
                boolean already = reel.savedBy != null && reel.savedBy.contains(me);
                if (already) reel.savedBy.remove(me);
                else reel.savedBy.add(me);
                renderCounts();
                Fb.db().collection(reel.collectionName()).document(reel.id)
                        .set(Fb.map("savedBy", already ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me)), SetOptions.merge());
                Fb.db().collection("users").document(me)
                        .set(Fb.map("savedReelIds", already ? FieldValue.arrayRemove(reel.id) : FieldValue.arrayUnion(reel.id)), SetOptions.merge());
                if (!already) {
                    FeedAlgorithm.recordCreatorAffinity(a, reel.authorId, 8);
                }
                Ui.toast(a, already ? "Removed from Saved Reels" : "Saved Reel 🔖");
            }

            private void follow() {
                if (reel == null) return;
                String me = Fb.uid();
                if (me == null) return;
                Notify.follow(reel.authorId, true);
                followT.setText("Following ✓");
                Ui.toast(itemView.getContext(), "Following " + reel.authorName + " ✓");
            }

            private void shareSheet(Activity a) {
                if (reel == null) return;
                if (reel.id != null && reel.id.startsWith("local_uploading_")) {
                    Ui.toast(a, "⏳ Reel is finishing upload — ready to share in a moment!");
                    return;
                }
                Ui.choose(a, "Share Reel", new String[]{
                        "📤 Share Reel to your News Feed",
                        "📖 Add Reel to your Story",
                        "📲 Send via WhatsApp / Other Apps",
                        "🔗 Copy Reel Video Link"
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
                                        ? ("🎬 Shared a Reel by " + reel.authorName + ": " + reel.caption)
                                        : ("🎬 Shared a Reel by " + reel.authorName),
                                "mediaType", "video",
                                "mediaUrl", reel.videoUrl,
                                "privacy", "Public",
                                "likes", 0, "comments", 0, "shares", 0,
                                "savedBy", new ArrayList<String>(),
                                "createdAt", FieldValue.serverTimestamp());
                        Fb.db().collection("posts").add(postMap);
                        Ui.toast(a, "Reel shared to your News Feed ✓");
                    } else if (which == 1) {
                        Map<String, Object> s = new HashMap<>();
                        s.put("authorId", Fb.uid());
                        s.put("authorName", Fb.myName());
                        s.put("authorPhotoUrl", Fb.myPhoto());
                        s.put("mediaUrl", reel.videoUrl);
                        s.put("mediaType", "video");
                        s.put("caption", "🎬 Reel by " + reel.authorName);
                        s.put("viewedBy", new ArrayList<String>());
                        s.put("createdAt", FieldValue.serverTimestamp());
                        s.put("expiresAt", new com.google.firebase.Timestamp(
                                new java.util.Date(System.currentTimeMillis() + 24L * 3600_000L)));
                        Fb.db().collection("stories").add(s);
                        Ui.toast(a, "Added Reel to your Story 📖");
                    } else if (which == 2) {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, "🎬 Watch " + reel.authorName + "'s Reel on friendbook:\n"
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
                m.getMenu().add(0, 10, 0, "🎬 Upload Your Own Reel");
                m.getMenu().add(0, 1, 0, "👍 See who reacted");
                m.getMenu().add(0, 2, 0, "⏩ Playback speed (" + speed + "x)");
                m.getMenu().add(0, 3, 0, "⬇ Download Reel video");
                m.getMenu().add(0, 4, 0, "🔗 Copy video link");
                if (reel.authorPhoto != null && !reel.authorPhoto.isEmpty()) {
                    m.getMenu().add(0, 5, 0, "👁 View creator's full DP");
                }
                m.getMenu().add(0, 6, 0, "🚩 Report Reel");
                if (reel.authorId != null && !reel.authorId.equals(Fb.uid())) {
                    m.getMenu().add(0, 7, 0, "🚫 Block " + (reel.authorName == null ? "creator" : reel.authorName));
                    if (com.friendbook.social.core.Admin.canModerate()) {
                        m.getMenu().add(0, 8, 0, com.friendbook.social.core.Admin.isAdmin()
                                ? "🛡 🗑 Delete Reel (Admin)" : "🛡 🗑 Delete Reel (Moderator)");
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
                                            .addOnSuccessListener(x -> Ui.toast(a, "Reel deleted 🗑")));
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
                                Ui.toast(a, "⬇ Downloading Reel to Downloads…");
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
                    } else if (id == 6) {
                        final String[] reasons = {
                                "🔞 Nudity or explicit sexual video",
                                "🚨 Child safety / CSAE violation (Zero Tolerance)",
                                "🩸 Graphic violence or dangerous acts",
                                "😡 Harassment, hate speech or bullying",
                                "🎭 Spam or misleading content"
                        };
                        Ui.choose(a, "🚩 Report Reel", reasons, w -> {
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
                                Ui.toast(a, "🛡 Violating Reel removed & reported ✓");
                            } else {
                                Ui.toast(a, "Reel hidden & reported ✓");
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
                                        Ui.toast(a, "Blocked " + reel.authorName + " 🚫");
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
