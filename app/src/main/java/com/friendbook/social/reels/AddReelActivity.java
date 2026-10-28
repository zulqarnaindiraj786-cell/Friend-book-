package com.friendbook.social.reels;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Ui;
import com.google.firebase.firestore.FieldValue;

import java.util.ArrayList;
import java.util.Map;

/** Upload a reel: pick a video + caption. */
public class AddReelActivity extends Activity {

    private static final int PICK = 41;
    private VideoView video;
    private TextView pickBtn, shareBtn;
    private EditText caption;
    private Uri videoUri;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Create reel"));

        pickBtn = Ui.btnGrey(this, "🎥  Choose video");
        Ui.pad(pickBtn, 0, 12, 0, 12);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.setMargins(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 8));
        root.addView(pickBtn, pp);
        pickBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("video/*");
            startActivityForResult(i, PICK);
        });

        LinearLayout videoWrap = Ui.col(this);
        videoWrap.setBackgroundColor(android.graphics.Color.BLACK);
        video = new VideoView(this);
        videoWrap.addView(video, new LinearLayout.LayoutParams(-1, Ui.dp(this, 320)));
        videoWrap.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
        vp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
        root.addView(videoWrap, vp);

        caption = Ui.multi(this, "Describe your reel…", 3);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
        root.addView(caption, cp);

        shareBtn = Ui.btn(this, "Share reel");
        shareBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        sp.setMargins(Ui.dp(this, 12), 4, Ui.dp(this, 12), 12);
        root.addView(shareBtn, sp);
        shareBtn.setOnClickListener(v -> upload());

        setContentView(root);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) {
            videoUri = data.getData();
            ((LinearLayout) video.getParent()).setVisibility(android.view.View.VISIBLE);
            video.setVideoURI(videoUri);
            video.setOnPreparedListener(mp -> { mp.setLooping(true); video.start(); });
            pickBtn.setText("🎥  Change video");
            java.util.List<Uri> single = new ArrayList<>();
            single.add(videoUri);
            com.friendbook.social.core.ContentSafetyEngine.scanMediaAsync(this, single, true, "", v -> {
                if (isFinishing() || !v.unsafe) return;
                video.stopPlayback();
                ((LinearLayout) video.getParent()).setVisibility(android.view.View.GONE);
                videoUri = null;
                pickBtn.setText("🎥  Choose video");
                Ui.info(this, "🛡 Unsafe Video Auto-Removed", v.message);
            });
        }
    }

    private void upload() {
        if (busy) return;
        if (videoUri == null) { Ui.toast(this, "Choose a video first"); return; }
        String cap = caption.getText().toString().trim();
        com.friendbook.social.core.ContentSafetyEngine.Verdict tv =
                com.friendbook.social.core.ContentSafetyEngine.checkText(cap);
        if (tv.unsafe) {
            Ui.info(this, "🛡 Reel Blocked", tv.message);
            return;
        }
        busy = true;
        shareBtn.setText("Checking safety…");
        java.util.List<Uri> single = new ArrayList<>();
        single.add(videoUri);
        com.friendbook.social.core.ContentSafetyEngine.scanMediaAsync(this, single, true, cap, v -> {
            if (isFinishing()) return;
            if (v.unsafe) {
                video.stopPlayback();
                ((LinearLayout) video.getParent()).setVisibility(android.view.View.GONE);
                videoUri = null;
                busy = false;
                shareBtn.setText("Share reel");
                Ui.info(this, "🛡 Unsafe Reel Auto-Deleted", v.message);
                return;
            }
            shareBtn.setText("Uploading…");
            String path = "reels/" + Fb.uid() + "/" + System.currentTimeMillis() + ".mp4";
            com.friendbook.social.core.Cloudinary.uploadOrFirebase(this, path, videoUri)
                    .addOnSuccessListener(url -> {
                        Map<String, Object> m = Fb.map(
                                "authorId", Fb.uid(),
                                "authorName", Fb.myName(),
                                "authorPhotoUrl", Fb.myPhoto(),
                                "videoUrl", url.toString(),
                                "caption", cap,
                                "likes", 0, "comments", 0, "shares", 0,
                                "likedBy", new ArrayList<String>(),
                                "createdAt", FieldValue.serverTimestamp());
                        Fb.db().collection("reels").add(m)
                                .addOnSuccessListener(x -> { Ui.toast(this, "Reel shared 🎉"); finish(); })
                                .addOnFailureListener(e -> fail());
                    })
                    .addOnFailureListener(e -> fail());
        });
    }

    private void fail() {
        busy = false;
        shareBtn.setText("Share reel");
        Ui.toast(this, "Upload failed — try again");
    }
}
