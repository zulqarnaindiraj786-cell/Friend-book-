package com.friendbook.reels;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Notify;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reels tab: full-screen vertical video feed (TikTok-style scroll). */
public class ReelsFragment extends Fragment {

    private ViewPager2 pager;
    private ReelAdapter adapter;
    private ListenerRegistration reg;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        Activity a = getActivity();
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);

        pager = new ViewPager2(a);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        adapter = new ReelAdapter(a);
        pager.setAdapter(adapter);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        // overlay bar
        LinearLayout bar = Ui.row(a);
        Ui.pad(bar, 16, 34, 12, 8);
        TextView title = Ui.tvBold(a, "Reels", 23, Color.WHITE);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = Ui.tv(a, "🎥", 22, Color.WHITE);
        add.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 4), 0);
        bar.addView(add);
        add.setOnClickListener(v -> startActivity(new Intent(a, AddReelActivity.class)));
        root.addView(bar);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                adapter.playOnly(pager, position);
            }
        });
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        reg = Fb.db().collection("reels")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener((snap, err) -> {
                    List<Models.Reel> list = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Reel r = Models.Reel.fromDoc(d);
                            if (r.videoUrl == null || r.videoUrl.isEmpty()) continue;
                            if (Fb.me != null && Fb.me.blocked.contains(r.authorId)) continue;
                            list.add(r);
                        }
                    }
                    adapter.setData(list);
                    if (!isHidden()) {
                        pager.postDelayed(() -> {
                            if (!isHidden()) adapter.playOnly(pager, pager.getCurrentItem());
                        }, 200);
                    }
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
        if (reg != null) reg.remove();
        adapter.stopAll();
    }

    // ================== adapter ==================

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

        void stopAll() {
            if (playingPos >= 0) {
                playingPos = -1;
                notifyDataSetChanged();
            }
        }

        void playOnly(ViewPager2 pager, int pos) {
            if (pos == playingPos) return;
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
            final TextView likeT, likeCount, commentT, commentCount, shareT, soundT, moreT, nameT, captionT, followT;
            final ImageView avatar;
            android.media.MediaPlayer activeMp;
            boolean muted = false;
            Models.Reel reel;

            VH(Activity a) {
                super(build(a));
                FrameLayout root = (FrameLayout) itemView;

                video = new VideoView(a);
                root.addView(video, new FrameLayout.LayoutParams(-1, -1));

                // right rail
                LinearLayout rail = Ui.col(a);
                rail.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
                rp.rightMargin = Ui.dp(a, 10);
                rail.setLayoutParams(rp);

                likeT = Ui.tv(a, "🤍", 30, Color.WHITE);
                likeCount = Ui.tvBold(a, "0", 12, Color.WHITE);
                likeCount.setGravity(Gravity.CENTER);
                commentT = Ui.tv(a, "💬", 28, Color.WHITE);
                commentCount = Ui.tvBold(a, "0", 12, Color.WHITE);
                commentCount.setGravity(Gravity.CENTER);
                shareT = Ui.tv(a, "↗", 30, Color.WHITE);
                soundT = Ui.tv(a, "🔊", 24, Color.WHITE);
                moreT = Ui.tvBold(a, "⋮", 24, Color.WHITE);
                rail.addView(likeT);
                rail.addView(likeCount);
                rail.addView(spacer(a, 16));
                rail.addView(commentT);
                rail.addView(commentCount);
                rail.addView(spacer(a, 16));
                rail.addView(shareT);
                rail.addView(spacer(a, 16));
                rail.addView(soundT);
                rail.addView(spacer(a, 14));
                rail.addView(moreT);
                root.addView(rail);

                // bottom info
                LinearLayout info = Ui.col(a);
                FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
                ip.setMargins(Ui.dp(a, 14), 0, Ui.dp(a, 74), Ui.dp(a, 26));
                info.setLayoutParams(ip);
                LinearLayout authorRow = Ui.row(a);
                avatar = new ImageView(a);
                int s = Ui.dp(a, 38);
                authorRow.addView(avatar, new LinearLayout.LayoutParams(s, s));
                nameT = Ui.tvBold(a, "", 15, Color.WHITE);
                nameT.setPadding(Ui.dp(a, 10), 0, 0, 0);
                authorRow.addView(nameT);
                followT = Ui.tvBold(a, "Follow", 13, Color.WHITE);
                followT.setBackground(Ui.roundStroke(a, Color.TRANSPARENT, 8, Color.WHITE, 1));
                followT.setPadding(Ui.dp(a, 10), Ui.dp(a, 5), Ui.dp(a, 10), Ui.dp(a, 5));
                LinearLayout.LayoutParams fp2 = new LinearLayout.LayoutParams(-2, -2);
                fp2.leftMargin = Ui.dp(a, 12);
                authorRow.addView(followT, fp2);
                info.addView(authorRow);
                captionT = Ui.tv(a, "", 14, Color.WHITE);
                captionT.setMaxLines(2);
                captionT.setPadding(0, Ui.dp(a, 8), 0, 0);
                info.addView(captionT);
                root.addView(info);

                // gestures: double-tap to like, single tap pause/play
                GestureDetector gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        if (reel != null) like(true);
                        return true;
                    }
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (video.isPlaying()) video.pause(); else video.start();
                        return true;
                    }
                });
                root.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));

                likeT.setOnClickListener(v -> like(false));
                commentT.setOnClickListener(v -> {
                    if (reel == null) return;
                    Intent i = new Intent(v.getContext(), ReelCommentsActivity.class);
                    i.putExtra("reel_id", reel.id);
                    i.putExtra("author", reel.authorId);
                    v.getContext().startActivity(i);
                });
                shareT.setOnClickListener(v -> share());
                soundT.setOnClickListener(v -> {
                    muted = !muted;
                    soundT.setText(muted ? "🔇" : "🔊");
                    if (activeMp != null) {
                        try { activeMp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Throwable ignored) {}
                    }
                });
                moreT.setOnClickListener(v -> {
                    if (reel == null) return;
                    Ui.choose(a, "Reel options", new String[]{"🔗 Copy video link", "🚩 Report Reel"}, w -> {
                        if (w == 0) {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("reel", reel.videoUrl));
                            Ui.toast(a, "Link copied 🔗");
                        } else {
                            Fb.db().collection("reports").add(Fb.map("reelId", reel.id,
                                    "reportedBy", Fb.uid(), "type", "reel",
                                    "createdAt", FieldValue.serverTimestamp()));
                            Ui.toast(a, "Reel reported ✓");
                        }
                    });
                });
                followT.setOnClickListener(v -> follow());
                View.OnClickListener prof = v -> {
                    if (reel == null) return;
                    Intent i = new Intent(v.getContext(), ProfileActivity.class);
                    i.putExtra("uid", reel.authorId);
                    v.getContext().startActivity(i);
                };
                avatar.setOnClickListener(prof);
                nameT.setOnClickListener(prof);
            }

            private View spacer(Activity a, int h) {
                View v = new View(a);
                v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(a, h)));
                return v;
            }

            private static FrameLayout build(Activity a) {
                FrameLayout f = new FrameLayout(a);
                f.setBackgroundColor(Color.BLACK);
                f.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
                return f;
            }

            void bind(Models.Reel r) {
                reel = r;
                nameT.setText(r.authorName);
                captionT.setText(r.caption);
                Ui.avatar(itemView.getContext(), avatar, r.authorPhoto);
                boolean liked = r.likedBy.contains(Fb.uid());
                likeT.setText(liked ? "❤️" : "🤍");
                boolean hideCounts = com.friendbook.core.Prefs.hideReactionCounts(itemView.getContext())
                        || (Fb.me != null && Fb.me.hideReactionCounts);
                likeCount.setText(hideCounts ? "" : Format.count(r.likes));
                commentCount.setText(Format.count(r.comments));
                video.setVideoURI(android.net.Uri.parse(r.videoUrl));
                video.setOnPreparedListener(mp -> {
                    activeMp = mp;
                    mp.setLooping(true);
                    try { mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Throwable ignored) {}
                });
            }

            void play() {
                if (reel == null) return;
                android.content.Context ctx = itemView.getContext();
                if (!com.friendbook.core.Prefs.shouldAutoplayNow(ctx)) {
                    Ui.toast(ctx, "Data Saver / Manual play: tap video to play ▶");
                    return;
                }
                com.friendbook.core.Prefs.trackMediaLoad(ctx, 420_000L);
                if (!video.isPlaying()) {
                    video.seekTo(0);
                    video.start();
                }
            }

            void pause() {
                if (video.isPlaying()) video.pause();
            }

            private void like(boolean fromDoubleTap) {
                if (reel == null) return;
                String me = Fb.uid();
                if (me == null) return;
                boolean liked = reel.likedBy.contains(me);
                if (fromDoubleTap && liked) { Ui.toast(itemView.getContext(), "❤️"); return; }
                Map<String, Object> m = new HashMap<>();
                m.put("likedBy", liked ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
                m.put("likes", FieldValue.increment(liked ? -1 : 1));
                Fb.db().collection("reels").document(reel.id).set(m, SetOptions.merge());
                if (!liked) Notify.send(reel.authorId, "reel_like", "liked your reel");
                Ui.haptic(itemView);
            }

            private void follow() {
                if (reel == null) return;
                String me = Fb.uid();
                if (me == null) return;
                Fb.db().collection("users").document(reel.authorId)
                        .set(Fb.map("followers", FieldValue.arrayUnion(me)), SetOptions.merge());
                Fb.db().collection("users").document(me)
                        .set(Fb.map("following", FieldValue.arrayUnion(reel.authorId)), SetOptions.merge());
                Notify.send(reel.authorId, "follow", "started following you");
                Ui.toast(itemView.getContext(), "Following ✓");
            }

            private void share() {
                if (reel == null) return;
                Fb.db().collection("reels").document(reel.id)
                        .set(Fb.map("shares", FieldValue.increment(1)), SetOptions.merge());
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, "Watch this reel by " + reel.authorName + ": " + reel.caption + "\n" + reel.videoUrl);
                itemView.getContext().startActivity(Intent.createChooser(i, "Share reel"));
            }
        }
    }
}
