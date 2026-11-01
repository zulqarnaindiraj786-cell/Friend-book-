package com.friendbook.social.post;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.VideoView;

import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🖼 friendbook Pro Full-Screen HD DP & Photo Viewer:
 * - Shows 100% FULL uncropped HD profile pictures (DP), cover photos & post photos!
 * - Interactive Pinch-to-Zoom (1x–5x) + Pan + Double-Tap 2.5x Zoom!
 * - Profile Picture Guard (🛡️) protection (blocks screenshots & downloads for guarded DPs).
 * - Save to Phone, Share, Set as DP/Cover, and live per-user Like / Reactions / Comment / Share bar!
 */
public class FullscreenMediaActivity extends Activity {

    private float scaleFactor = 1.0f;
    private float posX = 0f, posY = 0f;
    private float lastTouchX, lastTouchY;
    private boolean dragging = false;

    private String postId;
    private Models.Post livePost;
    private ListenerRegistration postReg;
    private TextView totalCountSummaryTv;
    private LinearLayout likePillBox;
    private ImageView likeOutlineIv;
    private TextView likeEmojiTv;
    private TextView likeLabelTv;
    private TextView commentBtn;

    private final FastCache.ReactionObserver reactionObserver = (pid, mine, total) -> {
        if (pid != null && pid.equals(postId)) {
            if (livePost != null) livePost.likes = total;
            renderPostReactionBar();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String type = getIntent().getStringExtra("type");
        final String url = getIntent().getStringExtra("url");
        final String title = getIntent().getStringExtra("title");
        this.postId = getIntent().getStringExtra("post_id");
        final boolean guarded = getIntent().getBooleanExtra("guarded", false);

        if (guarded) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        final LinearLayout topOverlay = Ui.row(this);
        final LinearLayout bottomOverlay = Ui.col(this);

        if ("video".equals(type) && url != null) {
            VideoView vv = new VideoView(this);
            root.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            MediaController mc = new MediaController(this);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            vv.setVideoURI(Uri.parse(url));
            vv.setOnPreparedListener(mp -> { mp.setLooping(true); vv.start(); });
        } else {
            final ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (!Ui.hasRealPhoto(url)) {
                int dpSz = Ui.dp(this, 260);
                root.addView(iv, new FrameLayout.LayoutParams(dpSz, dpSz, Gravity.CENTER));
                iv.setImageResource(com.friendbook.social.R.drawable.ic_default_avatar);
            } else {
                root.addView(iv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                Ui.imgFit(this, iv, url);
            }

            final ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            scaleFactor *= detector.getScaleFactor();
                            scaleFactor = Math.max(1.0f, Math.min(scaleFactor, 5.0f));
                            if (scaleFactor <= 1.02f) { posX = 0f; posY = 0f; }
                            iv.setScaleX(scaleFactor);
                            iv.setScaleY(scaleFactor);
                            iv.setTranslationX(posX);
                            iv.setTranslationY(posY);
                            return true;
                        }
                    });

            final GestureDetector tapDetector = new GestureDetector(this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent e) { return true; }

                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            if (scaleFactor > 1.2f) {
                                scaleFactor = 1.0f;
                                posX = 0f;
                                posY = 0f;
                            } else {
                                scaleFactor = 2.5f;
                            }
                            iv.animate().scaleX(scaleFactor).scaleY(scaleFactor)
                                    .translationX(posX).translationY(posY).setDuration(180).start();
                            return true;
                        }

                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent e) {
                            int nextVis = topOverlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
                            topOverlay.setVisibility(nextVis);
                            bottomOverlay.setVisibility(nextVis);
                            return true;
                        }
                    });

            iv.setOnTouchListener((v, ev) -> {
                scaleDetector.onTouchEvent(ev);
                tapDetector.onTouchEvent(ev);
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    lastTouchX = ev.getRawX();
                    lastTouchY = ev.getRawY();
                    dragging = true;
                } else if (action == MotionEvent.ACTION_MOVE && dragging && !scaleDetector.isInProgress() && scaleFactor > 1.02f) {
                    float dx = ev.getRawX() - lastTouchX;
                    float dy = ev.getRawY() - lastTouchY;
                    float maxDx = (iv.getWidth() * (scaleFactor - 1f)) / 2f;
                    float maxDy = (iv.getHeight() * (scaleFactor - 1f)) / 2f;
                    posX = Math.max(-maxDx, Math.min(maxDx, posX + dx));
                    posY = Math.max(-maxDy, Math.min(maxDy, posY + dy));
                    iv.setTranslationX(posX);
                    iv.setTranslationY(posY);
                    lastTouchX = ev.getRawX();
                    lastTouchY = ev.getRawY();
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    dragging = false;
                }
                return true;
            });
        }

        // Top friendbook Action Bar: ← Back · Title · ⬇ Save · 📤 Share · •••
        topOverlay.setBackgroundColor(0x99000000);
        topOverlay.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(topOverlay, 6, 28, 12, 8);

        ImageView back = Ui.backArrow(this, Color.WHITE, v -> finish());
        topOverlay.addView(back);

        String barLabel = (title != null && !title.isEmpty()) ? title : "Full Photo";
        TextView titleTv = Ui.tvBold(this, barLabel, 15, Color.WHITE);
        titleTv.setSingleLine(true);
        topOverlay.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1));

        final boolean hasRealUrl = Ui.hasRealPhoto(url);
        if (!guarded && hasRealUrl) {
            TextView saveBtn = Ui.roundChip(this, "⬇ Save", 0x44FFFFFF, Color.WHITE, 12);
            Ui.pad(saveBtn, 10, 5, 10, 5);
            saveBtn.setOnClickListener(v -> downloadPhoto(url));
            topOverlay.addView(saveBtn);
        }

        if (hasRealUrl) {
            TextView moreBtn = Ui.tvBold(this, "•••", 18, Color.WHITE);
            Ui.pad(moreBtn, 12, 4, 6, 4);
            moreBtn.setOnClickListener(v -> showMoreMenu(v, url, guarded));
            topOverlay.addView(moreBtn);
        }

        root.addView(topOverlay, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Bottom Bar: Guard badge + Optional Post Like / Comment / Share actions
        bottomOverlay.setBackgroundColor(0x99000000);
        Ui.pad(bottomOverlay, 14, 10, 14, 18);

        if (guarded) {
            TextView guardNote = Ui.tvBold(this, "🛡️ Profile Picture Guard is ON — protected on friendbook", 12.5f, 0xFF66B2FF);
            guardNote.setPadding(0, 0, 0, Ui.dp(this, 6));
            bottomOverlay.addView(guardNote);
        } else {
            TextView zoomHint = Ui.tv(this,
                    hasRealUrl ? "Pinch or double-tap to zoom full HD photo" : "Default profile picture",
                    11.5f, 0xBBFFFFFF);
            zoomHint.setGravity(Gravity.CENTER);
            bottomOverlay.addView(zoomHint);
        }

        if (postId != null && !postId.isEmpty()) {
            livePost = new Models.Post();
            livePost.id = postId;

            // Separate Total Count Summary Row above buttons (shows total reactions & comments separately!)
            totalCountSummaryTv = Ui.tvBold(this, "", 12.5f, 0xEEFFFFFF);
            totalCountSummaryTv.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 2));
            totalCountSummaryTv.setVisibility(View.GONE);
            totalCountSummaryTv.setOnClickListener(v -> Reactions.showWhoReacted(this, postId));
            bottomOverlay.addView(totalCountSummaryTv);

            LinearLayout actRow = Ui.row(this);
            actRow.setPadding(0, Ui.dp(this, 8), 0, 0);

            // Reaction Pill: UNCOLORED outline unless `currentUserId` (`Fb.uid()`) has reacted!
            likePillBox = Ui.row(this);
            likePillBox.setGravity(Gravity.CENTER);
            likePillBox.setBackground(Ui.round(this, 0x38FFFFFF, 20));
            Ui.pad(likePillBox, 8, 8, 8, 8);

            likeOutlineIv = new ImageView(this);
            likeOutlineIv.setImageResource(com.friendbook.social.R.drawable.ic_like_outline);
            likeOutlineIv.setColorFilter(Color.WHITE);
            likePillBox.addView(likeOutlineIv, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));

            likeEmojiTv = Ui.tv(this, "", 15f, Color.WHITE);
            likeEmojiTv.setIncludeFontPadding(false);
            likeEmojiTv.setVisibility(View.GONE);
            likePillBox.addView(likeEmojiTv);

            likeLabelTv = Ui.tvBold(this, "Like", 13, Color.WHITE);
            likeLabelTv.setPadding(Ui.dp(this, 6), 0, 0, 0);
            likePillBox.addView(likeLabelTv);

            commentBtn = pillBtn("💬 Comment");
            TextView shareBtn = pillBtn("↗ Share");

            likePillBox.setOnClickListener(v -> {
                Ui.haptic(v);
                if (livePost == null) {
                    livePost = new Models.Post();
                    livePost.id = postId;
                }
                Reactions.tapLike(this, livePost);
                renderPostReactionBar();
            });
            likePillBox.setOnLongClickListener(v -> {
                if (livePost == null) {
                    livePost = new Models.Post();
                    livePost.id = postId;
                }
                Reactions.show(likePillBox, picked -> {
                    Reactions.apply(this, livePost, picked);
                    renderPostReactionBar();
                });
                return true;
            });
            commentBtn.setOnClickListener(v -> {
                Intent ci = new Intent(this, PostDetailActivity.class);
                ci.putExtra("post_id", postId);
                startActivity(ci);
            });
            shareBtn.setOnClickListener(v -> shareUrl(url, barLabel));

            LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1);
            p1.rightMargin = Ui.dp(this, 8);
            LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1);
            p2.rightMargin = Ui.dp(this, 8);
            actRow.addView(likePillBox, p1);
            actRow.addView(commentBtn, p2);
            actRow.addView(shareBtn, new LinearLayout.LayoutParams(0, -2, 1));
            bottomOverlay.addView(actRow);
            renderPostReactionBar();
        }

        root.addView(bottomOverlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (postId != null && !postId.isEmpty()) {
            FastCache.addReactionObserver(reactionObserver);
            postReg = Fb.db().collection("posts").document(postId).addSnapshotListener((d, e) -> {
                if (d == null || !d.exists()) return;
                livePost = Models.Post.fromDoc(d);
                Reactions.syncPostReactions(this, livePost);
                renderPostReactionBar();
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        FastCache.removeReactionObserver(reactionObserver);
        if (postReg != null) {
            postReg.remove();
            postReg = null;
        }
    }

    private void renderPostReactionBar() {
        if (postId == null || likePillBox == null || livePost == null) return;
        Reactions.hydrateFromPostInstant(livePost);
        String currentUid = Fb.uid();
        String myReact = (currentUid != null && !currentUid.isEmpty())
                ? Reactions.resolveCurrentUserReaction(livePost) : null;
        boolean iReacted = myReact != null && !myReact.isEmpty();
        int total = FastCache.getReactionTotal(postId, livePost.likes);
        int effectiveTotal = iReacted ? Math.max(1, total) : Math.max(0, total);

        // 1. Separate Total Count Summary Row (never colors the reaction button for another user!)
        if (totalCountSummaryTv != null) {
            StringBuilder sb = new StringBuilder();
            if (effectiveTotal > 0) {
                List<String> types = FastCache.getReactionTypes(postId);
                if (types.isEmpty() && livePost.reactionsMap != null) {
                    types = Reactions.computeTopTypesFromMap(livePost.reactionsMap);
                }
                int shown = 0;
                for (String t : types) {
                    sb.append(Reactions.emojiFor(t));
                    if (++shown >= 3) break;
                }
                if (shown > 0) sb.append(" ");
                if (iReacted) {
                    sb.append(effectiveTotal == 1 ? "You"
                            : (effectiveTotal == 2 ? "You and 1 other"
                            : ("You and " + Format.count(effectiveTotal - 1) + " others")));
                } else {
                    sb.append(Format.count(effectiveTotal))
                            .append(effectiveTotal == 1 ? " reaction" : " reactions");
                }
            }
            if (livePost.comments > 0) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(Format.count(livePost.comments))
                        .append(livePost.comments == 1 ? " comment" : " comments");
            }
            totalCountSummaryTv.setText(sb.toString());
            totalCountSummaryTv.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);
        }

        if (commentBtn != null) {
            commentBtn.setText(livePost.comments > 0
                    ? ("💬 " + Format.count(livePost.comments)) : "💬 Comment");
        }

        // 2. Reaction Button: Colored ONLY when `currentUserId` has reacted; 100% neutral otherwise!
        if (!iReacted) {
            likePillBox.setBackground(Ui.round(this, 0x38FFFFFF, 20));
            likeOutlineIv.setVisibility(View.VISIBLE);
            likeOutlineIv.setImageResource(com.friendbook.social.R.drawable.ic_like_outline);
            likeOutlineIv.setColorFilter(Color.WHITE);
            likeEmojiTv.setVisibility(View.GONE);
            likeLabelTv.setText("Like");
            likeLabelTv.setTextColor(Color.WHITE);
        } else if ("like".equals(myReact)) {
            likePillBox.setBackground(Ui.roundStroke(this, 0x551877F2, 20, 0xFF1877F2, 1));
            likeOutlineIv.setVisibility(View.VISIBLE);
            likeOutlineIv.setImageResource(com.friendbook.social.R.drawable.ic_like_filled);
            likeOutlineIv.clearColorFilter();
            likeEmojiTv.setVisibility(View.GONE);
            likeLabelTv.setText("Like");
            likeLabelTv.setTextColor(0xFF66B2FF);
        } else {
            int rxColor = Reactions.colorFor(myReact);
            int bg = (rxColor & 0x00FFFFFF) | 0x44000000;
            int stroke = (rxColor & 0x00FFFFFF) | 0xCC000000;
            likePillBox.setBackground(Ui.roundStroke(this, bg, 20, stroke, 1));
            likeOutlineIv.setVisibility(View.GONE);
            likeEmojiTv.setText(Reactions.emojiFor(myReact));
            likeEmojiTv.setVisibility(View.VISIBLE);
            likeLabelTv.setText(Reactions.labelFor(myReact));
            likeLabelTv.setTextColor("love".equals(myReact) ? 0xFFFF6B81 : 0xFFFFD666);
        }
    }

    private TextView pillBtn(String txt) {
        TextView t = Ui.tvBold(this, txt, 13, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(this, 0x38FFFFFF, 20));
        Ui.pad(t, 8, 8, 8, 8);
        return t;
    }

    private void showMoreMenu(View anchor, String url, boolean guarded) {
        PopupMenu m = new PopupMenu(this, anchor);
        if (!guarded && url != null && !url.isEmpty()) {
            m.getMenu().add(0, 1, 0, "⬇ Save photo to phone");
            m.getMenu().add(0, 2, 0, "📤 Share photo");
            m.getMenu().add(0, 3, 0, "🔗 Copy photo link");
            m.getMenu().add(0, 4, 0, "👤 Make my profile picture");
            m.getMenu().add(0, 5, 0, "🖼 Make my cover photo");
        } else {
            m.getMenu().add(0, 9, 0, "🛡️ Protected by Profile Picture Guard");
        }
        m.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id == 1) downloadPhoto(url);
            else if (id == 2) shareUrl(url, "Check out this photo on friendbook");
            else if (id == 3) Ui.copy(this, url);
            else if (id == 4 && Fb.signedIn()) {
                Map<String, Object> up = new HashMap<>();
                up.put("photoUrl", url);
                Fb.patchMe(up);
                Ui.toast(this, "Updated your profile picture ✓");
            } else if (id == 5 && Fb.signedIn()) {
                Map<String, Object> up = new HashMap<>();
                up.put("coverUrl", url);
                Fb.patchMe(up);
                Ui.toast(this, "Updated your cover photo ✓");
            }
            return true;
        });
        m.show();
    }

    private void shareUrl(String url, String title) {
        if (url == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, title + "\n" + url);
        startActivity(Intent.createChooser(i, "Share"));
    }

    private void downloadPhoto(String url) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null || url == null) return;
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(Ui.hdImageUrl(url)));
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String fname = "friendbook_" + System.currentTimeMillis() + ".jpg";
            req.setTitle(fname);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fname);
            dm.enqueue(req);
            Ui.toast(this, "⬇ Saving full HD photo to Downloads…");
        } catch (Throwable t) {
            Ui.openUrl(this, url);
        }
    }
}
