package com.friendbook.story;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Full-screen story viewer: segments, auto-advance, tap zones, reply, viewed marking. */
public class StoryViewerActivity extends Activity {

    private final List<Models.Story> stories = new ArrayList<>();
    private int index = 0;
    private ListenerRegistration reg;
    private String authorId;
    private ImageView img;
    private VideoView video;
    private TextView nameT, timeT, captionT, viewersT;
    private LinearLayout segments;
    private LinearLayout replyRow, ownerRow;
    private EditText replyInput;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private Runnable timer;
    private ValueAnimator anim;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        authorId = getIntent().getStringExtra("author");
        if (authorId == null) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));
        video = new VideoView(this);
        video.setVisibility(View.GONE);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));

        // tap zones (prev / next)
        View left = new View(this);
        View right = new View(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, -1);
        lp.width = getResources().getDisplayMetrics().widthPixels * 30 / 100;
        left.setLayoutParams(new FrameLayout.LayoutParams(lp));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(lp);
        rp.gravity = Gravity.END;
        right.setLayoutParams(rp);
        root.addView(left);
        root.addView(right);
        left.setOnClickListener(v -> step(-1));
        right.setOnClickListener(v -> step(1));

        // top overlay
        LinearLayout top = Ui.col(this);
        Ui.pad(top, 12, 30, 12, 0);
        segments = Ui.row(this);
        top.addView(segments, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));
        LinearLayout head = Ui.row(this);
        head.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView close = Ui.tvBold(this, "✕", 18, Color.WHITE);
        close.setPadding(0, 0, Ui.dp(this, 10), 0);
        close.setOnClickListener(v -> finish());
        head.addView(close);
        ImageView av = new ImageView(this);
        int s = Ui.dp(this, 34);
        head.addView(av, new LinearLayout.LayoutParams(s, s));
        LinearLayout nc = Ui.col(this);
        LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(0, -2, 1);
        ncp.leftMargin = Ui.dp(this, 8);
        nameT = Ui.tvBold(this, "", 14, Color.WHITE);
        timeT = Ui.tv(this, "", 12, 0xB3FFFFFF);
        nc.addView(nameT);
        nc.addView(timeT);
        head.addView(nc, ncp);
        TextView viewProf = Ui.tvBold(this, "View profile", 12, Color.WHITE);
        viewProf.setBackground(Ui.round(this, 0x44FFFFFF, 12));
        viewProf.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        head.addView(viewProf);
        top.addView(head);
        root.addView(top);

        av.setOnClickListener(v -> openProfile());
        nameT.setOnClickListener(v -> openProfile());
        viewProf.setOnClickListener(v -> openProfile());
        Ui.avatar(this, av, getIntent().getStringExtra("authorPhoto"));

        // caption (center-bottom)
        captionT = Ui.tvBold(this, "", 15, Color.WHITE);
        captionT.setGravity(Gravity.CENTER);
        captionT.setBackgroundColor(0x55000000);
        Ui.pad(captionT, 14, 8, 14, 8);
        FrameLayout.LayoutParams capp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        capp.setMargins(0, 0, 0, Ui.dp(this, 120));
        root.addView(captionT, capp);

        // owner tools
        ownerRow = Ui.row(this);
        ownerRow.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        op.setMargins(0, 0, 0, Ui.dp(this, 60));
        ownerRow.setLayoutParams(op);
        viewersT = Ui.tvBold(this, "", 13, Color.WHITE);
        viewersT.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        TextView del = Ui.tvBold(this, "🗑 Delete", 13, Color.WHITE);
        del.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        ownerRow.addView(viewersT, new LinearLayout.LayoutParams(0, -2, 1));
        ownerRow.addView(del);
        ownerRow.setVisibility(View.GONE);
        root.addView(ownerRow);
        del.setOnClickListener(v -> deleteCurrent());
        viewersT.setOnClickListener(v -> showViewers());

        // reply row (for other people's stories)
        replyRow = Ui.row(this);
        FrameLayout.LayoutParams rr = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        rr.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 18));
        replyRow.setLayoutParams(rr);
        replyInput = Ui.input(this, "Reply to this story…");
        replyInput.setTextColor(Color.WHITE);
        replyInput.setHintTextColor(0x88FFFFFF);
        replyInput.setBackground(Ui.roundStroke(this, Color.TRANSPARENT, 22, 0x66FFFFFF, 1));
        replyRow.addView(replyInput, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        TextView sendReply = Ui.tv(this, "➤", 22, Color.WHITE);
        sendReply.setPadding(Ui.dp(this, 10), 0, 0, 0);
        replyRow.addView(sendReply);
        replyRow.setVisibility(View.GONE);
        root.addView(replyRow);
        sendReply.setOnClickListener(v -> sendReply());

        setContentView(root);
    }

    private void openProfile() {
        Intent i = new Intent(this, ProfileActivity.class);
        i.putExtra("uid", authorId);
        startActivity(i);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("stories").whereEqualTo("authorId", authorId)
                .addSnapshotListener((snap, err) -> {
                    stories.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Story s = Models.Story.fromDoc(d);
                            if (!s.expired()) stories.add(s);
                        }
                    }
                    Collections.sort(stories, (a, b2) -> Long.compare(a.createdAt, b2.createdAt));
                    if (stories.isEmpty()) { finish(); return; }
                    if (index >= stories.size()) index = stories.size() - 1;
                    show();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
        stopTimer();
    }

    private void show() {
        stopTimer();
        Models.Story s = stories.get(index);
        nameT.setText(s.authorName);
        timeT.setText(Format.ago(s.createdAt));
        captionT.setText(s.caption == null ? "" : s.caption);
        captionT.setVisibility(s.caption == null || s.caption.isEmpty() ? View.GONE : View.VISIBLE);

        boolean mine = s.authorId.equals(Fb.uid());
        ownerRow.setVisibility(mine ? View.VISIBLE : View.GONE);
        replyRow.setVisibility(mine ? View.GONE : View.VISIBLE);
        viewersT.setText("👁 " + s.viewedBy.size() + " views");

        buildSegments();

        if ("video".equals(s.mediaType)) {
            img.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            video.setVideoURI(android.net.Uri.parse(s.mediaUrl));
            video.setOnPreparedListener(mp -> video.start());
            video.setOnCompletionListener(mp -> step(1));
        } else {
            video.stopPlayback();
            video.setVisibility(View.GONE);
            img.setVisibility(View.VISIBLE);
            Ui.imgFit(this, img, s.mediaUrl);
        }

        // mark viewed
        String me = Fb.uid();
        if (me != null && !mine && !s.viewedBy.contains(me)) {
            Fb.db().collection("stories").document(s.id)
                    .update("viewedBy", FieldValue.arrayUnion(me));
        }

        // image auto-advance after 5s
        if (!"video".equals(s.mediaType)) {
            animateSegment(5000);
            timer = () -> step(1);
            H.postDelayed(timer, 5000);
        } else {
            animateSegment(15000);
        }
    }

    private void buildSegments() {
        segments.removeAllViews();
        for (int i = 0; i < stories.size(); i++) {
            FrameLayout seg = new FrameLayout(this);
            seg.setBackgroundColor(0x55FFFFFF);
            View fill = new View(this);
            fill.setBackgroundColor(Color.WHITE);
            fill.setTag("fill");
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(0, -1);
            seg.addView(fill, fp);
            if (i < index) fp.width = -1;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) p.leftMargin = Ui.dp(this, 4);
            segments.addView(seg, p);
        }
    }

    private void animateSegment(long durMs) {
        if (anim != null) anim.cancel();
        if (segments.getChildCount() <= index) return;
        View seg = segments.getChildAt(index);
        View fill = seg.findViewWithTag("fill");
        if (fill == null) return;
        FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) fill.getLayoutParams();
        int full = seg.getWidth() > 0 ? seg.getWidth() : getResources().getDisplayMetrics().widthPixels / Math.max(1, stories.size());
        anim = ValueAnimator.ofInt(0, full);
        anim.setDuration(durMs);
        anim.addUpdateListener(a -> {
            fp.width = (int) a.getAnimatedValue();
            fill.setLayoutParams(fp);
        });
        anim.start();
    }

    private void stopTimer() {
        if (timer != null) H.removeCallbacks(timer);
        if (anim != null) anim.cancel();
    }

    private void step(int delta) {
        int next = index + delta;
        if (next < 0) next = 0;
        if (next >= stories.size()) { finish(); return; }
        index = next;
        show();
    }

    private void deleteCurrent() {
        if (stories.isEmpty()) return;
        Models.Story s = stories.get(index);
        Ui.confirm(this, "Delete this story?", "", "Delete", () -> {
            Fb.db().collection("stories").document(s.id).delete();
            Ui.toast(this, "Story deleted");
        });
    }

    private void showViewers() {
        if (stories.isEmpty()) return;
        List<String> viewers = stories.get(index).viewedBy;
        if (viewers.isEmpty()) { Ui.toast(this, "No views yet"); return; }
        List<String> names = new ArrayList<>();
        for (String uid : viewers) {
            Fb.getUser(uid).addOnSuccessListener(u -> {
                names.add(u != null ? u.displayName : "User");
                if (names.size() == viewers.size()) {
                    Ui.info(this, "Viewed by", android.text.TextUtils.join("\n", names));
                }
            });
        }
    }

    private void sendReply() {
        String text = replyInput.getText().toString().trim();
        if (text.isEmpty()) return;
        String me = Fb.uid();
        if (me == null) return;
        Models.Story s = stories.get(index);
        String chatId = Fb.chatId(me, s.authorId);
        java.util.Map<String, Object> msg = Fb.map(
                "senderId", me,
                "receiverId", s.authorId,
                "text", "📖 Story reply: " + text,
                "imageUrl", null,
                "type", "text",
                "isSeen", false,
                "isDelivered", false,
                "createdAt", FieldValue.serverTimestamp());
        com.google.firebase.firestore.WriteBatch batch = Fb.db().batch();
        batch.set(Fb.db().collection("chats").document(chatId).collection("messages").document(), msg);
        batch.set(Fb.db().collection("chats").document(chatId), Fb.map(
                "participants", java.util.Arrays.asList(me, s.authorId),
                "lastMessage", "📖 " + text,
                "lastMessageTime", FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge());
        batch.commit().addOnSuccessListener(x -> {
            replyInput.setText("");
            Ui.hideKeyboard(this);
            Ui.toast(this, "Reply sent 💬");
        });
    }
}
