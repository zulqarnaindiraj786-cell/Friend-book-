package com.example.frienbook.post;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.core.content.FileProvider;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Ui;
import com.google.firebase.firestore.FieldValue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Create / edit post: text, photo or video, feeling, audience. */
public class CreatePostActivity extends Activity {

    private static final int PICK_IMAGE = 11, PICK_VIDEO = 12, CAPTURE_IMAGE = 13;

    private EditText text;
    private TextView privacyBtn, feelingBtn, postBtn, mediaLabel;
    private ImageView preview;
    private VideoView videoPreview;
    private LinearLayout previewWrap;
    private Uri mediaUri;
    private String mediaType = "none";
    private String privacy;
    private String feeling = "";
    private String editId = null;
    private Uri cameraFile;
    private boolean busy;

    private static final String[] FEELINGS = {
            "😊 happy", "😍 loved", "😎 cool", "🥳 excited", "😢 sad", "😡 angry",
            "🤒 sick", "😴 tired", "🤔 thoughtful", "🙏 blessed", "🎉 celebrating", "✈️ traveling"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        editId = getIntent().getStringExtra("edit_id");
        privacy = Prefs.defaultPrivacy(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // bar
        LinearLayout bar = Ui.pageBar(this, editId == null ? "Create post" : "Edit post");
        postBtn = Ui.btn(this, "POST");
        postBtn.setMinHeight(Ui.dp(this, 34));
        bar.addView(postBtn);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 12, 12, 12);

        // me row
        LinearLayout meRow = Ui.row(this);
        ImageView av = new ImageView(this);
        int s = Ui.dp(this, 44);
        meRow.addView(av, new LinearLayout.LayoutParams(s, s));
        Ui.avatar(this, av, Fb.myPhoto());
        LinearLayout nameCol = Ui.col(this);
        LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(0, -2, 1);
        ncp.leftMargin = Ui.dp(this, 10);
        TextView name = Ui.tvBold(this, Fb.myName(), 15, Ui.txt(this));
        nameCol.addView(name);
        LinearLayout chips = Ui.row(this);
        chips.setPadding(0, Ui.dp(this, 4), 0, 0);
        privacyBtn = chip(privacyLabel());
        feelingBtn = chip(feeling.isEmpty() ? "＋ Feeling" : feeling);
        privacyBtn.setOnClickListener(v -> pickPrivacy());
        feelingBtn.setOnClickListener(v -> pickFeeling());
        chips.addView(privacyBtn);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2);
        fp.leftMargin = Ui.dp(this, 6);
        chips.addView(feelingBtn, fp);
        nameCol.addView(chips);
        meRow.addView(nameCol, ncp);
        body.addView(meRow);

        text = Ui.multi(this, "What's on your mind?", 5);
        text.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(this, 10);
        body.addView(text, tp);

        // media preview
        previewWrap = Ui.col(this);
        previewWrap.setVisibility(View.GONE);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewWrap.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 240)));
        videoPreview = new VideoView(this);
        videoPreview.setVisibility(View.GONE);
        previewWrap.addView(videoPreview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 240)));
        mediaLabel = Ui.tv(this, "", 13, Ui.sub(this));
        mediaLabel.setGravity(Gravity.CENTER);
        Ui.pad(mediaLabel, 0, 4, 0, 4);
        previewWrap.addView(mediaLabel);
        TextView remove = Ui.btnGrey(this, "✕ Remove media");
        remove.setMinHeight(Ui.dp(this, 32));
        remove.setOnClickListener(v -> clearMedia());
        previewWrap.addView(remove, new LinearLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        body.addView(previewWrap);

        // attachment row
        LinearLayout attach = Ui.row(this);
        attach.setBackgroundColor(Ui.card(this));
        Ui.pad(attach, 8, 10, 8, 10);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Ui.dp(this, 14);
        attach.setLayoutParams(ap);
        attach.addView(attachBtn("🖼️ Photo", v -> pick(PICK_IMAGE, "image/*")));
        attach.addView(attachBtn("🎥 Video", v -> pick(PICK_VIDEO, "video/*")));
        attach.addView(attachBtn("📷 Camera", v -> camera()));
        body.addView(attach);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        if (editId != null) loadForEdit();
        postBtn.setOnClickListener(v -> submit());
    }

    private TextView chip(String label) {
        TextView t = Ui.tvBold(this, label, 12, Ui.BLUE);
        t.setBackground(Ui.roundStroke(this, Ui.inputBg(this), 14, Ui.div(this), 1));
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        return t;
    }

    private TextView attachBtn(String label, View.OnClickListener l) {
        TextView t = Ui.tvBold(this, label, 13, Ui.txt(this));
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return t;
    }

    private String privacyLabel() {
        String icon = "🌐";
        if ("Friends".equalsIgnoreCase(privacy)) icon = "👥";
        if ("Only me".equalsIgnoreCase(privacy)) icon = "🔒";
        return icon + " " + privacy + " ▾";
    }

    private void pickPrivacy() {
        String[] opts = {"🌐 Public", "👥 Friends", "🔒 Only me"};
        Ui.choose(this, "Who can see this post?", opts, w -> {
            privacy = w == 1 ? "Friends" : w == 2 ? "Only me" : "Public";
            privacyBtn.setText(privacyLabel());
        });
    }

    private void pickFeeling() {
        Ui.choose(this, "How are you feeling?", FEELINGS, w -> {
            feeling = FEELINGS[w];
            feelingBtn.setText(feeling);
        });
    }

    private void pick(int code, String type) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(type);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Select"), code);
    }

    private void camera() {
        try {
            File f = new File(getCacheDir(), "cam_" + System.currentTimeMillis() + ".jpg");
            cameraFile = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraFile);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, CAPTURE_IMAGE);
        } catch (Throwable t) {
            Ui.toast(this, "Camera not available");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;
        if (req == CAPTURE_IMAGE && cameraFile != null) {
            setMedia(cameraFile, "image");
        } else if ((req == PICK_IMAGE || req == PICK_VIDEO) && data != null && data.getData() != null) {
            setMedia(data.getData(), req == PICK_IMAGE ? "image" : "video");
        }
    }

    private void setMedia(Uri uri, String type) {
        mediaUri = uri;
        mediaType = type;
        previewWrap.setVisibility(View.VISIBLE);
        if ("video".equals(type)) {
            preview.setVisibility(View.GONE);
            videoPreview.setVisibility(View.VISIBLE);
            videoPreview.setVideoURI(uri);
            videoPreview.setOnPreparedListener(mp -> { mp.setLooping(true); videoPreview.start(); });
            mediaLabel.setText("🎥 Video attached");
        } else {
            videoPreview.setVisibility(View.GONE);
            preview.setVisibility(View.VISIBLE);
            preview.setImageURI(uri);
            mediaLabel.setText("🖼️ Photo attached");
        }
    }

    private void clearMedia() {
        mediaUri = null;
        mediaType = "none";
        previewWrap.setVisibility(View.GONE);
        videoPreview.stopPlayback();
    }

    private void loadForEdit() {
        Fb.db().collection("posts").document(editId).get().addOnSuccessListener(d -> {
            if (!d.exists()) { Ui.toast(this, "Post not found"); finish(); return; }
            text.setText(d.getString("content"));
            String f = d.getString("feeling");
            if (f != null && !f.isEmpty()) { feeling = f; feelingBtn.setText(f); }
            String p = d.getString("privacy");
            if (p != null) { privacy = p; privacyBtn.setText(privacyLabel()); }
            String mt = d.getString("mediaType");
            String mu = d.getString("mediaUrl");
            if (mt != null && !"none".equals(mt) && mu != null && !mu.isEmpty()) {
                previewWrap.setVisibility(View.VISIBLE);
                if ("image".equals(mt)) {
                    preview.setVisibility(View.VISIBLE);
                    Ui.img(this, preview, mu);
                    mediaLabel.setText("🖼️ Current photo (remove to replace)");
                } else {
                    videoPreview.setVisibility(View.VISIBLE);
                    videoPreview.setVideoURI(Uri.parse(mu));
                    mediaLabel.setText("🎥 Current video (remove to replace)");
                }
                mediaType = mt; // keep old media unless removed
            }
        });
    }

    private void submit() {
        if (busy) return;
        String content = text.getText().toString().trim();
        if (content.isEmpty() && mediaUri == null && editId == null) {
            Ui.toast(this, "Say something first");
            return;
        }
        busy = true;
        postBtn.setText("Posting…");
        postBtn.setEnabled(false);
        Ui.hideKeyboard(this);

        if (mediaUri != null) {
            String ext = "video".equals(mediaType) ? ".mp4" : ".jpg";
            String path = "posts/" + Fb.uid() + "/" + System.currentTimeMillis() + ext;
            Fb.upload(path, mediaUri)
                    .addOnSuccessListener(url -> save(content, url.toString()))
                    .addOnFailureListener(e -> fail("Upload failed: " + e.getMessage()));
        } else {
            save(content, null);
        }
    }

    private void save(String content, String uploadedUrl) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        m.put("feeling", feeling);
        m.put("privacy", privacy);
        if (uploadedUrl != null) {
            m.put("mediaUrl", uploadedUrl);
            m.put("mediaType", mediaType);
        } else if (mediaType.equals("none") && editId != null) {
            m.put("mediaUrl", "");
            m.put("mediaType", "none");
        }
        if (editId != null) {
            m.put("editedAt", FieldValue.serverTimestamp());
            Fb.db().collection("posts").document(editId).update(m)
                    .addOnSuccessListener(x -> { Ui.toast(this, "Post updated ✓"); finish(); })
                    .addOnFailureListener(e -> fail(e.getMessage()));
            return;
        }
        m.put("authorId", Fb.uid());
        m.put("authorName", Fb.myName());
        m.put("authorPhotoUrl", Fb.myPhoto());
        if (uploadedUrl == null) { m.put("mediaUrl", ""); m.put("mediaType", "none"); }
        m.put("likes", 0);
        m.put("comments", 0);
        m.put("shares", 0);
        m.put("savedBy", new ArrayList<String>());
        m.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("posts").add(m)
                .addOnSuccessListener(x -> { Ui.toast(this, "Posted ✓"); finish(); })
                .addOnFailureListener(e -> fail(e.getMessage()));
    }

    private void fail(String msg) {
        busy = false;
        postBtn.setText("POST");
        postBtn.setEnabled(true);
        Ui.toast(this, msg == null ? "Failed" : msg);
    }
}
