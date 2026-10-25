package com.friendbook.story;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import com.friendbook.core.Fb;
import com.friendbook.core.Ui;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FieldValue;

import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

/** Add a story: photo or video + caption overlay. Expires after 24 hours. */
public class AddStoryActivity extends Activity {

    private static final int PICK_IMAGE = 31, PICK_VIDEO = 32;

    private FrameLayout preview;
    private LinearLayout emptyBox;
    private ImageView img;
    private VideoView video;
    private EditText caption;
    private TextView share;
    private Uri mediaUri;
    private String mediaType = null;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Color.BLACK);
        TextView back = Ui.tv(this, "✕", 20, Color.WHITE);
        back.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = Ui.tvBold(this, "Add to story", 18, Color.WHITE);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = Ui.tv(this, "Visible for 24 hours", 12, 0x99FFFFFF);
        bar.addView(hint);
        root.addView(bar);

        preview = new FrameLayout(this);
        preview.setBackgroundColor(0xFF111111);
        LinearLayout empty = Ui.col(this);
        emptyBox = empty;
        empty.setGravity(Gravity.CENTER);
        TextView e1 = Ui.tv(this, "🖼️", 42, Color.WHITE);
        TextView e2 = Ui.tv(this, "Choose a photo or video for your story", 14, 0xAAFFFFFF);
        e1.setGravity(Gravity.CENTER);
        e2.setGravity(Gravity.CENTER);
        empty.addView(e1);
        empty.addView(e2);
        preview.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setVisibility(View.GONE);
        preview.addView(img, new FrameLayout.LayoutParams(-1, -1));
        video = new VideoView(this);
        video.setVisibility(View.GONE);
        preview.addView(video, new FrameLayout.LayoutParams(-1, -1));
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = Ui.col(this);
        bottom.setBackgroundColor(Color.BLACK);
        Ui.pad(bottom, 12, 10, 12, 14);
        caption = Ui.input(this, "Add a caption…");
        caption.setTextColor(Color.WHITE);
        caption.setHintTextColor(0x88FFFFFF);
        caption.setBackground(Ui.round(this, 0x33FFFFFF, 22));
        bottom.addView(caption, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));

        LinearLayout buttons = Ui.row(this);
        buttons.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView photoBtn = darkBtn("🖼️ Photo");
        TextView videoBtn = darkBtn("🎥 Video");
        share = Ui.btn(this, "Share to story");
        photoBtn.setOnClickListener(v -> pick(PICK_IMAGE, "image/*"));
        videoBtn.setOnClickListener(v -> pick(PICK_VIDEO, "video/*"));
        share.setOnClickListener(v -> upload());
        buttons.addView(photoBtn, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, -2, 1);
        vp.leftMargin = Ui.dp(this, 8); vp.rightMargin = Ui.dp(this, 8);
        buttons.addView(videoBtn, vp);
        buttons.addView(share, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1.6f));
        bottom.addView(buttons);
        root.addView(bottom);

        setContentView(root);
    }

    private TextView darkBtn(String label) {
        TextView t = Ui.tvBold(this, label, 14, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(this, 0x33FFFFFF, 10));
        t.setMinHeight(Ui.dp(this, 44));
        return t;
    }

    private void pick(int code, String type) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(type);
        startActivityForResult(i, code);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        mediaUri = data.getData();
        mediaType = req == PICK_IMAGE ? "image" : "video";
        emptyBox.setVisibility(View.GONE);
        if ("image".equals(mediaType)) {
            video.stopPlayback();
            video.setVisibility(View.GONE);
            img.setVisibility(View.VISIBLE);
            img.setImageURI(mediaUri);
        } else {
            img.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            video.setVideoURI(mediaUri);
            video.setOnPreparedListener(mp -> { mp.setLooping(true); video.start(); });
        }
    }

    private void upload() {
        if (busy) return;
        if (mediaUri == null) { Ui.toast(this, "Choose a photo or video first"); return; }
        busy = true;
        share.setText("Sharing…");
        String ext = "video".equals(mediaType) ? ".mp4" : ".jpg";
        String path = "stories/" + Fb.uid() + "/" + System.currentTimeMillis() + ext;
        com.friendbook.core.Cloudinary.uploadOrFirebase(this, path, mediaUri)
                .addOnSuccessListener(url -> {
                    Map<String, Object> m = Fb.map(
                            "authorId", Fb.uid(),
                            "authorName", Fb.myName(),
                            "authorPhotoUrl", Fb.myPhoto(),
                            "mediaUrl", url.toString(),
                            "mediaType", mediaType,
                            "caption", caption.getText().toString().trim(),
                            "viewedBy", new ArrayList<String>(),
                            "createdAt", FieldValue.serverTimestamp(),
                            "expiresAt", new Timestamp(new Date(System.currentTimeMillis() + 24L * 3600_000L)));
                    Fb.db().collection("stories").add(m)
                            .addOnSuccessListener(x -> { Ui.toast(this, "Story shared ✓"); finish(); })
                            .addOnFailureListener(e -> fail());
                })
                .addOnFailureListener(e -> fail());
    }

    private void fail() {
        busy = false;
        share.setText("Share to story");
        Ui.toast(this, "Upload failed — check connection");
    }
}
