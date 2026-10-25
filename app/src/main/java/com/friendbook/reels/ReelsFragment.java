package com.friendbook.reels;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.friendbook.core.Fb;
import com.friendbook.core.FeedAlgorithm;
import com.friendbook.core.Format;
import com.friendbook.core.Notify;
import com.friendbook.core.Prefs;
import com.friendbook.core.TickCache;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.post.FullscreenMediaActivity;
import com.friendbook.post.Reactions;
import com.friendbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🎬 Facebook Lite Pro Reels Tab:
 * - Full-screen vertical swipe feed combining both `reels` AND video `posts`!
 * - High-contrast right-side action rail with Like (6 reactions + Who Liked!), Comments (all users!),
 *   Share (Feed / Story / Apps / Copy Link), Save, Sound toggle & Playback Speed!
 * - Bottom quick "Add a comment…" pill + Creator DP, Verified Tick, Follow & Original Audio bar!
 */
public class ReelsFragment extends Fragment {

    private ViewPager2 pager;
    private ReelAdapter adapter;
    private LinearLayout emptyState;
    private ListenerRegistration reelsReg, videoPostsReg;
    private final List<Models.Reel> fromReels = new ArrayList<>();
    private final List<Models.Reel> fromPosts = new ArrayList<>();
    private String initialReelId;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        Activity a = getActivity();
        if (getArguments() != null) {
            initialReelId = getArguments().getString("initial_reel_id");
        }

        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);

        pager = new ViewPager2(a);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        adapter = new ReelAdapter(a);
        pager.setAdapter(adapter);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        // Empty state when no reels/videos exist yet
        emptyState = Ui.col(a);
        emptyState.setGravity(Gravity.CENTER);
        Ui.pad(emptyState, 28, 28, 28, 28);
        emptyState.setVisibility(View.GONE);
        emptyState.addView(Ui.tv(a, "🎬", 48, Color.WHITE));
        TextView et1 = Ui.tvBold(a, "Welcome to friendbook Reels", 18, Color.WHITE);
        et1.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
        emptyState.addView(et1);
        TextView et2 = Ui.tv(a, "Share short vertical videos with music, reactions, comments & shares!", 13.5f, 0xBBFFFFFF);
        et2.setGravity(Gravity.CENTER);
        emptyState.addView(et2);
        TextView createNow = Ui.roundChip(a, "🎥  Create First Reel", Ui.BLUE, Color.WHITE, 14);
        Ui.pad(createNow, 18, 10, 18, 10);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = Ui.dp(a, 16);
        createNow.setOnClickListener(v -> startActivity(new Intent(a, AddReelActivity.class)));
        emptyState.addView(createNow, clp);
        root.addView(emptyState, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // Top Facebook Lite Reels Overlay Header
        LinearLayout bar = Ui.row(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x44000000);
        bar.setElevation(Ui.dp(a, 24));
        boolean showBack = getArguments() != null && getArguments().getBoolean("show_back", false);
        Ui.pad(bar, showBack ? 8 : 16, showBack ? 28 : 12, 14, 10);

        if (showBack) {
            TextView backBtn = Ui.tvBold(a, "‹", 30, Color.WHITE);
            Ui.pad(backBtn, 10, 0, 12, 2);
            backBtn.setOnClickListener(v -> { if (getActivity() != null) getActivity().finish(); });
            bar.addView(backBtn);
        }

        TextView title = Ui.tvBold(a, "Reels", 22, Color.WHITE);
        title.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        TextView refreshPill = Ui.roundChip(a, "↻ Fresh", 0x55000000, Color.WHITE, 13);
        Ui.pad(refreshPill, 11, 6, 11, 6);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.rightMargin = Ui.dp(a, 8);
        refreshPill.setOnClickListener(v -> {
            refreshWithAlgorithm();
            Ui.toast(a, "🎬 Fresh reels & videos loaded");
        });
        bar.addView(refreshPill, rlp);

        TextView camPill = Ui.roundChip(a, "🎥 Create", 0x55000000, Color.WHITE, 13);
        Ui.pad(camPill, 12, 6, 12, 6);
        camPill.setOnClickListener(v -> startActivity(new Intent(a, AddReelActivity.class)));
        bar.addView(camPill);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                adapter.playOnly(pager, position);
                Models.Reel cur = adapter.reelAtPosition(position);
                if (cur != null && cur.id != null) {
                    FeedAlgorithm.recordVideoImpression(getActivity(), cur.id);
                }
            }
        });
        return root;
    }

    /** Advances the Facebook discovery epoch so new/unwatched Reels & fresh Sponsored Ads appear immediately! */
    public void refreshWithAlgorithm() {
        Activity a = getActivity();
        if (a == null) return;
        initialReelId = null; // allow full dynamic rotation after initial deep-link view
        FeedAlgorithm.nextRefreshGeneration(a);
        rebuildMergedFeed();
        if (pager != null) pager.setCurrentItem(0, false);
    }

    private void rebuildMergedFeed() {
        Map<String, Models.Reel> map = new LinkedHashMap<>();
        for (Models.Reel r : fromReels) {
            if (r != null && r.videoUrl != null && !r.videoUrl.isEmpty()) map.put(r.id, r);
        }
        for (Models.Reel r : fromPosts) {
            if (r != null && r.videoUrl != null && !r.videoUrl.isEmpty() && !map.containsKey(r.id)) {
                map.put(r.id, r);
            }
        }
        List<Models.Reel> merged = FeedAlgorithm.rankReels(
                getActivity(), new ArrayList<>(map.values()), initialReelId);

        if (emptyState != null) {
            emptyState.setVisibility(merged.isEmpty() ? View.VISIBLE : View.GONE);
        }
        adapter.setData(merged);
        if (!isHidden() && !merged.isEmpty() && pager != null) {
            pager.postDelayed(() -> {
                if (!isHidden() && pager != null) adapter.playOnly(pager, pager.getCurrentItem());
            }, 180);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        reelsReg = Fb.db().collection("reels")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(60)
                .addSnapshotListener((snap, err) -> {
                    fromReels.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Reel r = Models.Reel.fromDoc(d);
                            if (r.videoUrl == null || r.videoUrl.isEmpty()) continue;
                            if (Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(r.authorId)) continue;
                            fromReels.add(r);
                        }
                    }
                    rebuildMergedFeed();
                });

        // Also surface video posts seamlessly in Reels!
        videoPostsReg = Fb.db().collection("posts")
                .whereEqualTo("mediaType", "video")
                .limit(40)
                .addSnapshotListener((snap, err) -> {
                    fromPosts.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Post p = Models.Post.fromDoc(d);
                            if (p.mediaUrl == null || p.mediaUrl.isEmpty()) continue;
                            if (Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(p.authorId)) continue;
                            fromPosts.add(Models.Reel.fromVideoPost(p));
                        }
                    }
                    rebuildMergedFeed();
                });
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (adapter == null) return;
        if (hidden) {
            adapter.stopAll();
        } else if (pager != null) {
            pager.post(() -> adapter.playOnly(pager, pager.getCurrentItem()));
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (reelsReg != null) reelsReg.remove();
        if (videoPostsReg != null) videoPostsReg.remove();
        if (adapter != null) adapter.stopAll();
    }

    // ================== Reel Adapter ==================

    static class ReelAdapter extends RecyclerView.Adapter<ReelAdapter.VH> {
        private final Activity activity;
        private final List<Models.Reel> data = new ArrayList<>();
        private int playingPos = -1;

        ReelAdapter(Activity a) { this.activity = a; }

        void setData(List<Models.Reel> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
            playingPos = -1;
        }

        Models.Reel reelAtPosition(int pos) {
            return (pos >= 0 && pos < data.size()) ? data.get(pos) : null;
        }

        void stopAll() {
            if (playingPos >= 0) {
                playingPos = -1;
                notifyDataSetChanged();
            }
        }

        void playOnly(ViewPager2 pager, int pos) {
            playingPos = pos;
            RecyclerView rv = (RecyclerView) pager.getChildAt(0);
            if (rv == null) return;
            for (int i = 0; i < rv.getChildCount(); i++) {
                RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
                if (h instanceof VH) {
                    VH vh = (VH) h;
                    if (vh.getAdapterPosition() == pos) vh.play();
                    else vh.pause();
                }
            }
        }

        @Override public int getItemCount() { return data.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(activity);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            h.bind(data.get(position));
        }

        @Override
        public void onViewRecycled(@NonNull VH h) {
            super.onViewRecycled(h);
            h.video.stopPlayback();
        }

        class VH extends RecyclerView.ViewHolder {
            final VideoView video;
            final FrameLayout likeBtnBox, commentBtnBox, shareBtnBox, saveBtnBox, soundBtnBox, moreBtnBox;
            final TextView likeIcon, likeCount, commentIcon, commentCount, shareIcon, shareCount;
            final TextView saveIcon, soundIcon, heartBurst, nameT, captionT, audioT, followT, quickCommentPill;
            final ImageView avatar, tick;
            android.media.MediaPlayer activeMp;
            boolean muted;
            float speed = 1.0f;
            Models.Reel reel;

            VH(Activity a) {
                super(buildRoot(a));
                FrameLayout root = (FrameLayout) itemView;
                muted = !Prefs.reelsStartWithSound(a);

                video = new VideoView(a);
                FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
                root.addView(video, vlp);

                // Giant animated heart burst on double-tap!
                heartBurst = Ui.tv(a, "❤️", 82, Color.WHITE);
                heartBurst.setAlpha(0f);
                heartBurst.setElevation(Ui.dp(a, 30));
                root.addView(heartBurst, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

                // Right-Side Facebook Lite Pro Action Rail (Like · Comment · Share · Save · Sound · •••)
                LinearLayout rail = Ui.col(a);
                rail.setGravity(Gravity.CENTER_HORIZONTAL);
                rail.setElevation(Ui.dp(a, 24));
                FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
                rp.setMargins(0, 0, Ui.dp(a, 10), Ui.dp(a, 62));
                rail.setLayoutParams(rp);

                likeIcon = Ui.tv(a, "🤍", 24, Color.WHITE);
                likeIcon.setGravity(Gravity.CENTER);
                likeBtnBox = circleAction(a, likeIcon);
                likeCount = shadowLabel(a, "Like");

                commentIcon = Ui.tv(a, "💬", 22, Color.WHITE);
                commentIcon.setGravity(Gravity.CENTER);
                commentBtnBox = circleAction(a, commentIcon);
                commentCount = shadowLabel(a, "Comment");

                shareIcon = Ui.tvBold(a, "↗", 23, Color.WHITE);
                shareIcon.setGravity(Gravity.CENTER);
                shareBtnBox = circleAction(a, shareIcon);
                shareCount = shadowLabel(a, "Share");

                saveIcon = Ui.tv(a, "🔖", 20, Color.WHITE);
                saveIcon.setGravity(Gravity.CENTER);
                saveBtnBox = circleAction(a, saveIcon);

                soundIcon = Ui.tv(a, muted ? "🔇" : "🔊", 20, Color.WHITE);
                soundIcon.setGravity(Gravity.CENTER);
                soundBtnBox = circleAction(a, soundIcon);

                TextView moreIcon = Ui.tvBold(a, "•••", 17, Color.WHITE);
                moreIcon.setGravity(Gravity.CENTER);
                moreBtnBox = circleAction(a, moreIcon);

                rail.addView(likeBtnBox);
                rail.addView(likeCount);
                rail.addView(spacer(a, 12));
                rail.addView(commentBtnBox);
                rail.addView(commentCount);
                rail.addView(spacer(a, 12));
                rail.addView(shareBtnBox);
                rail.addView(shareCount);
                rail.addView(spacer(a, 12));
                rail.addView(saveBtnBox);
                rail.addView(spacer(a, 10));
                rail.addView(soundBtnBox);
                rail.addView(spacer(a, 10));
                rail.addView(moreBtnBox);
                root.addView(rail);

                // Bottom Creator Info + Quick "Add a comment…" Bar
                LinearLayout bottomCol = Ui.col(a);
                bottomCol.setElevation(Ui.dp(a, 22));
                bottomCol.setBackgroundColor(0x4D000000);
                Ui.pad(bottomCol, 14, 10, 74, 12);
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
                tick.setImageResource(com.friendbook.R.drawable.ic_verified);
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

                // Quick Comment Input Pill right on the Reel!
                quickCommentPill = Ui.tv(a, "💬  Add a comment…", 13, 0xEEFFFFFF);
                quickCommentPill.setBackground(Ui.roundStroke(a, 0x44FFFFFF, 18, 0x55FFFFFF, 1));
                Ui.pad(quickCommentPill, 14, 7, 14, 7);
                bottomCol.addView(quickCommentPill, new LinearLayout.LayoutParams(-1, -2));

                root.addView(bottomCol);
                rail.bringToFront();

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
                        if (video.isPlaying()) {
                            video.pause();
                            Ui.toast(a, "⏸ Paused");
                        } else {
                            video.start();
                        }
                        return true;
                    }
                });
                video.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));

                // Action Listeners
                likeBtnBox.setOnClickListener(v -> {
                    if (reel == null) return;
                    String me = Fb.uid();
                    String cur = (me != null && reel.reactions != null) ? reel.reactions.get(me) : null;
                    if (cur == null && me != null && reel.likedBy.contains(me)) cur = "love";
                    applyReelReaction(cur != null ? null : "love", false);
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
                    if (reel == null) return;
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
                box.setBackground(Ui.roundStroke(c, 0x66000000, 23, 0x33FFFFFF, 1));
                int sz = Ui.dp(c, 46);
                box.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
                box.addView(inner, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                return box;
            }

            private TextView shadowLabel(Context c, String txt) {
                TextView t = Ui.tvBold(c, txt, 11.5f, Color.WHITE);
                t.setGravity(Gravity.CENTER);
                t.setShadowLayer(4f, 0f, 1f, 0xDD000000);
                t.setPadding(0, Ui.dp(c, 2), 0, 0);
                return t;
            }

            private View spacer(Activity a, int h) {
                View v = new View(a);
                v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(a, h)));
                return v;
            }

            private void animateHeartBurst() {
                heartBurst.setScaleX(0.4f);
                heartBurst.setScaleY(0.4f);
                heartBurst.setAlpha(1f);
                heartBurst.animate().scaleX(1.35f).scaleY(1.35f).setDuration(220)
                        .withEndAction(() -> heartBurst.animate().alpha(0f).setDuration(260).start())
                        .start();
            }

            private static FrameLayout buildRoot(Activity a) {
                FrameLayout f = new FrameLayout(a);
                f.setBackgroundColor(Color.BLACK);
                f.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
                return f;
            }

            void bind(Models.Reel r) {
                reel = r;
                String authorName = r.authorName != null && !r.authorName.isEmpty() ? r.authorName : "friendbook Creator";
                nameT.setText(authorName);
                TickCache.attach(tick, r.authorId);
                boolean hasCap = r.caption != null && !r.caption.isEmpty();
                captionT.setText(hasCap ? r.caption : "");
                captionT.setVisibility(hasCap ? View.VISIBLE : View.GONE);
                audioT.setText("🎵 Original audio · " + authorName);

                Ui.avatar(itemView.getContext(), avatar, r.authorPhoto);

                String me = Fb.uid();
                followT.setVisibility(me != null && me.equals(r.authorId) ? View.GONE : View.VISIBLE);
                if (Fb.me != null && Fb.me.following != null && Fb.me.following.contains(r.authorId)) {
                    followT.setText("Following ✓");
                } else {
                    followT.setText("Follow");
                }

                renderCounts();

                video.setVideoURI(Uri.parse(r.videoUrl));
                video.setOnPreparedListener(mp -> {
                    activeMp = mp;
                    mp.setLooping(true);
                    try {
                        mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speed != 1.0f) {
                            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
                        }
                    } catch (Throwable ignored) {}
                });
            }

            private void renderCounts() {
                if (reel == null) return;
                String me = Fb.uid();
                String myReact = (me != null && reel.reactions != null) ? reel.reactions.get(me) : null;
                if (myReact == null && me != null && reel.likedBy != null && reel.likedBy.contains(me)) {
                    myReact = "love";
                }
                likeIcon.setText(myReact != null ? Reactions.emojiFor(myReact) : "🤍");
                boolean hideCounts = Prefs.hideReactionCounts(itemView.getContext())
                        || (Fb.me != null && Fb.me.hideReactionCounts);
                int totalLikes = Math.max(reel.likes, reel.reactions != null ? reel.reactions.size() : 0);
                likeCount.setText(hideCounts ? "Like" : (totalLikes > 0 ? Format.count(totalLikes) : "Like"));

                int totalComments = Math.max(reel.comments, reel.inlineComments != null ? reel.inlineComments.size() : 0);
                commentCount.setText(totalComments > 0 ? Format.count(totalComments) : "Comment");
                shareCount.setText(reel.shares > 0 ? Format.count(reel.shares) : "Share");

                boolean saved = me != null && ((reel.savedBy != null && reel.savedBy.contains(me))
                        || (Fb.me != null && Fb.me.savedReelIds != null && Fb.me.savedReelIds.contains(reel.id)));
                saveIcon.setText(saved ? "🔖" : "📑");
            }

            void play() {
                if (reel == null) return;
                Context ctx = itemView.getContext();
                Prefs.trackMediaLoad(ctx, 420_000L);
                if (!video.isPlaying()) {
                    video.seekTo(0);
                    video.start();
                }
            }

            void pause() {
                if (video.isPlaying()) video.pause();
            }

            private void applyReelReaction(String type, boolean fromDoubleTap) {
                if (reel == null) return;
                String me = Fb.uid();
                if (me == null) return;
                if (reel.reactions == null) reel.reactions = new HashMap<>();
                String prev = reel.reactions.get(me);
                if (prev == null && reel.likedBy.contains(me)) prev = "love";
                if (fromDoubleTap && prev != null) {
                    Ui.haptic(itemView);
                    return;
                }

                int delta = 0;
                if (type == null) {
                    reel.reactions.remove(me);
                    reel.likedBy.remove(me);
                    if (prev != null) delta = -1;
                } else {
                    reel.reactions.put(me, type);
                    if (!reel.likedBy.contains(me)) reel.likedBy.add(me);
                    if (prev == null) delta = 1;
                }
                reel.likes = Math.max(reel.reactions.size(), Math.max(0, reel.likes + delta));
                renderCounts(); // 0ms instant visual feedback!
                Ui.haptic(itemView);

                String col = reel.collectionName();
                Map<String, Object> up = new HashMap<>();
                up.put("reactions", reel.reactions);
                up.put("likedBy", type == null ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
                if (delta != 0) up.put("likes", FieldValue.increment(delta));
                Fb.db().collection(col).document(reel.id).set(up, SetOptions.merge());

                if (type != null && prev == null && !me.equals(reel.authorId)) {
                    Notify.send(reel.authorId, "reel_like",
                            Fb.myName() + " reacted " + Reactions.emojiFor(type) + " to your reel", reel.id);
                }
            }

            private void toggleSaveReel(Activity a) {
                String me = Fb.uid();
                if (reel == null || me == null) return;
                boolean already = reel.savedBy != null && reel.savedBy.contains(me);
                if (already) reel.savedBy.remove(me);
                else reel.savedBy.add(me);
                renderCounts();
                Fb.db().collection(reel.collectionName()).document(reel.id)
                        .set(Fb.map("savedBy", already ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me)), SetOptions.merge());
                Fb.db().collection("users").document(me)
                        .set(Fb.map("savedReelIds", already ? FieldValue.arrayRemove(reel.id) : FieldValue.arrayUnion(reel.id)), SetOptions.merge());
                Ui.toast(a, already ? "Removed from Saved Reels" : "Saved Reel 🔖");
            }

            private void follow() {
                if (reel == null) return;
                String me = Fb.uid();
                if (me == null) return;
                Fb.db().collection("users").document(reel.authorId)
                        .set(Fb.map("followers", FieldValue.arrayUnion(me)), SetOptions.merge());
                Fb.db().collection("users").document(me)
                        .set(Fb.map("following", FieldValue.arrayUnion(reel.authorId)), SetOptions.merge());
                followT.setText("Following ✓");
                Notify.send(reel.authorId, "follow", "started following you");
                Ui.toast(itemView.getContext(), "Following " + reel.authorName + " ✓");
            }

            private void shareSheet(Activity a) {
                if (reel == null) return;
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
                m.getMenu().add(0, 1, 0, "👍 See who reacted");
                m.getMenu().add(0, 2, 0, "⏩ Playback speed (" + speed + "x)");
                m.getMenu().add(0, 3, 0, "⬇ Download Reel video");
                m.getMenu().add(0, 4, 0, "🔗 Copy video link");
                if (reel.authorPhoto != null && !reel.authorPhoto.isEmpty()) {
                    m.getMenu().add(0, 5, 0, "👁 View creator's full DP");
                }
                m.getMenu().add(0, 6, 0, "🚩 Report Reel");
                m.setOnMenuItemClickListener(it -> {
                    int id = it.getItemId();
                    if (id == 1) {
                        Reactions.showReelReactors(a, reel);
                    } else if (id == 2) {
                        Ui.choose(a, "Playback speed", new String[]{"0.5x Slow", "1.0x Normal", "1.5x Fast", "2.0x Turbo"}, w -> {
                            speed = w == 0 ? 0.5f : (w == 1 ? 1.0f : (w == 2 ? 1.5f : 2.0f));
                            if (activeMp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
                        Fb.db().collection("reports").add(Fb.map("reelId", reel.id,
                                "reportedBy", Fb.uid(), "type", "reel",
                                "createdAt", FieldValue.serverTimestamp()));
                        Ui.toast(a, "Reel reported ✓");
                    }
                    return true;
                });
                m.show();
            }
        }
    }
}
