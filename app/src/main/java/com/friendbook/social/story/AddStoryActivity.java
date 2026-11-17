package com.friendbook.social.story;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FieldValue;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 📖 AddStoryActivity — Full friendbook Story Creator:
 * Supports 🖼️ Photo, 🎥 Video, AND 🎨 Aa Text Story (10 vibrant gradients, font styles,
 * 🎵 Music/Mood badge & Story Privacy selector).
 */
public class AddStoryActivity extends Activity {

    private static final int PICK_IMAGE = 31, PICK_VIDEO = 32;

    private static final int[] STORY_BG = {
            0xFF1877F2, 0xFFE41E3F, 0xFF9360F7, 0xFF2ABBA7, 0xFFE66A23,
            0xFF111111, 0xFFFF4081, 0xFF00A400, 0xFF5D4067, 0xFF0A3D91
    };
    private static final String[] MOODS = {
            "", "🎵 Chill Vibes", "🔥 Feeling Lit", "❤️ Grateful", "🌙 Late Night",
            "🎉 Celebrating", "🤲 Alhamdulillah", "🏏 Match Day", "☕ Coffee Time"
    };

    private FrameLayout preview;
    private LinearLayout emptyBox;
    private ImageView img;
    private VideoView video;
    private EditText textStoryInput, caption;
    private TextView share, privacyChip, moodChip, colorChip;
    private Uri mediaUri;
    private String mediaType = null; // "image" | "video" | "text"
    private int bgIdx = 0, fontIdx = 0, moodIdx = 0;
    private String storyPrivacy;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        storyPrivacy = Prefs.storyPrivacy(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Color.BLACK);

        // Top bar: close + title + privacy + mood + color cycle
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Color.BLACK);
        Ui.pad(bar, 6, 8, 10, 8);
        TextView back = Ui.tv(this, "✕", 20, Color.WHITE);
        back.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = Ui.tvBold(this, "Create story", 17, Color.WHITE);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        moodChip = pill("🎵 Music");
        moodChip.setOnClickListener(v -> {
            moodIdx = (moodIdx + 1) % MOODS.length;
            moodChip.setText(MOODS[moodIdx].isEmpty() ? "🎵 Music" : MOODS[moodIdx]);
        });
        bar.addView(moodChip);

        colorChip = pill("🎨 Color");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = Ui.dp(this, 6);
        colorChip.setOnClickListener(v -> {
            enterTextMode();
            bgIdx = (bgIdx + 1) % STORY_BG.length;
            fontIdx = (fontIdx + 1) % 3;
            applyTextStyle();
        });
        bar.addView(colorChip, clp);

        privacyChip = pill("🌐 " + storyPrivacy);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.leftMargin = Ui.dp(this, 6);
        privacyChip.setOnClickListener(v -> Ui.choose(this, "Story privacy",
                new String[]{"🌐 Public", "👥 Friends"}, w -> {
                    storyPrivacy = w == 1 ? "Friends" : "Public";
                    Prefs.setStoryPrivacy(this, storyPrivacy);
                    privacyChip.setText((w == 1 ? "👥 " : "🌐 ") + storyPrivacy);
                }));
        bar.addView(privacyChip, plp);
        root.addView(bar);

        // Center preview canvas
        preview = new FrameLayout(this);
        preview.setBackgroundColor(0xFF141517);

        emptyBox = Ui.col(this);
        emptyBox.setGravity(Gravity.CENTER);
        TextView e1 = Ui.tv(this, "📖", 42, Color.WHITE);
        e1.setGravity(Gravity.CENTER);
        TextView e2 = Ui.tv(this, "Tap Aa Text, Photo or Video below", 14, 0xBBFFFFFF);
        e2.setGravity(Gravity.CENTER);
        emptyBox.addView(e1);
        emptyBox.addView(e2);
        preview.addView(emptyBox, new FrameLayout.LayoutParams(-1, -1));

        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setVisibility(View.GONE);
        preview.addView(img, new FrameLayout.LayoutParams(-1, -1));

        video = new VideoView(this);
        video.setVisibility(View.GONE);
        preview.addView(video, new FrameLayout.LayoutParams(-1, -1));

        textStoryInput = new EditText(this);
        textStoryInput.setHint("Tap to type your story…");
        textStoryInput.setHintTextColor(0x99FFFFFF);
        textStoryInput.setTextColor(Color.WHITE);
        textStoryInput.setTextSize(26);
        textStoryInput.setTypeface(Typeface.DEFAULT_BOLD);
        textStoryInput.setGravity(Gravity.CENTER);
        textStoryInput.setBackgroundColor(Color.TRANSPARENT);
        Ui.pad(textStoryInput, 24, 24, 24, 24);
        textStoryInput.setVisibility(View.GONE);
        preview.addView(textStoryInput, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));

        // Bottom controls
        LinearLayout bottom = Ui.col(this);
        bottom.setBackgroundColor(Color.BLACK);
        Ui.pad(bottom, 12, 10, 12, 14);
        caption = Ui.input(this, "Add a caption…");
        caption.setTextColor(Color.WHITE);
        caption.setHintTextColor(0x88FFFFFF);
        caption.setBackground(Ui.round(this, 0x33FFFFFF, 22));
        bottom.addView(caption, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));

        LinearLayout buttons = Ui.row(this);
        buttons.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView textBtn = darkBtn("Aa Text");
        TextView photoBtn = darkBtn("🖼️ Photo");
        TextView videoBtn = darkBtn("🎥 Video");
        share = Ui.btn(this, "Share story");

        textBtn.setOnClickListener(v -> enterTextMode());
        photoBtn.setOnClickListener(v -> pick(PICK_IMAGE, "image/*"));
        videoBtn.setOnClickListener(v -> pick(PICK_VIDEO, "video/*"));
        share.setOnClickListener(v -> upload());

        buttons.addView(textBtn, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1);
        p1.leftMargin = Ui.dp(this, 6);
        buttons.addView(photoBtn, p1);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1);
        p2.leftMargin = Ui.dp(this, 6);
        p2.rightMargin = Ui.dp(this, 6);
        buttons.addView(videoBtn, p2);
        buttons.addView(share, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1.4f));
        bottom.addView(buttons);
        root.addView(bottom);

        setContentView(root);

        if ("text".equals(getIntent().getStringExtra("mode"))) {
            enterTextMode();
        }
    }

    private TextView pill(String text) {
        TextView t = Ui.tvBold(this, text, 11, Color.WHITE);
        t.setBackground(Ui.round(this, 0x33FFFFFF, 14));
        Ui.pad(t, 10, 6, 10, 6);
        return t;
    }

    private TextView darkBtn(String label) {
        TextView t = Ui.tvBold(this, label, 13, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(this, 0x33FFFFFF, 10));
        t.setMinHeight(Ui.dp(this, 44));
        return t;
    }

    private void enterTextMode() {
        mediaType = "text";
        mediaUri = null;
        video.stopPlayback();
        video.setVisibility(View.GONE);
        img.setVisibility(View.GONE);
        emptyBox.setVisibility(View.GONE);
        caption.setVisibility(View.GONE);
        textStoryInput.setVisibility(View.VISIBLE);
        applyTextStyle();
        textStoryInput.requestFocus();
    }

    private void applyTextStyle() {
        int c = STORY_BG[bgIdx];
        preview.setBackground(Ui.gradient(c, Ui.blend(Color.BLACK, c, 0.45f), 0));
        Typeface tf = fontIdx == 1 ? Typeface.SERIF
                : fontIdx == 2 ? Typeface.MONOSPACE : Typeface.DEFAULT_BOLD;
        textStoryInput.setTypeface(tf, Typeface.BOLD);
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
        textStoryInput.setVisibility(View.GONE);
        caption.setVisibility(View.VISIBLE);
        preview.setBackgroundColor(Color.BLACK);
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
        if ("text".equals(mediaType)) {
            String txt = textStoryInput.getText().toString().trim();
            if (txt.isEmpty()) { Ui.toast(this, "Write something for your text story"); return; }
            com.friendbook.social.core.ContentSafetyEngine.Verdict tv =
                    com.friendbook.social.core.ContentSafetyEngine.checkText(txt);
            if (tv.unsafe) {
                Ui.info(this, "🛡 Story Blocked", tv.message);
                return;
            }
            busy = true;
            share.setText("Sharing…");
            Map<String, Object> m = new HashMap<>();
            m.put("authorId", Fb.uid());
            m.put("authorName", Fb.myName());
            m.put("authorPhotoUrl", Fb.myPhoto());
            m.put("mediaUrl", "");
            m.put("mediaType", "text");
            m.put("caption", txt);
            m.put("bgColor", STORY_BG[bgIdx]);
            m.put("fontStyle", fontIdx);
            m.put("musicMood", MOODS[moodIdx]);
            m.put("privacy", storyPrivacy);
            m.put("viewedBy", new ArrayList<String>());
            m.put("createdAt", FieldValue.serverTimestamp());
            m.put("expiresAt", new Timestamp(new Date(System.currentTimeMillis() + 24L * 3600_000L)));
            // One write, one id. There were two here - a put under a generated id and an add
            // under a different one - so every text story was stored twice.
            com.friendbook.social.core.DataRouter.add("stories", m, (id, error) -> {
                if (error != null) { fail(); return; }
                Ui.toast(this, "Text story shared 📖");
                finish();
            });
            return;
        }
        if (mediaUri == null) { Ui.toast(this, "Choose Aa Text, Photo or Video first"); return; }
        String cap = caption.getText().toString().trim();
        busy = true;
        share.setText("Checking safety…");
        java.util.List<Uri> single = new ArrayList<>();
        single.add(mediaUri);
        com.friendbook.social.core.ContentSafetyEngine.scanMediaAsync(
                this, single, "video".equals(mediaType), cap, v -> {
                    if (isFinishing()) return;
                    if (v.unsafe) {
                        video.stopPlayback();
                        video.setVisibility(View.GONE);
                        img.setVisibility(View.GONE);
                        emptyBox.setVisibility(View.VISIBLE);
                        mediaUri = null;
                        busy = false;
                        share.setText("Share story");
                        Ui.info(this, "🛡 Unsafe Story Auto-Deleted", v.message);
                        return;
                    }
                    share.setText("Sharing…");
                    String ext = "video".equals(mediaType) ? ".mp4" : ".jpg";
                    String path = "stories/" + Fb.uid() + "/" + System.currentTimeMillis() + ext;
                    com.friendbook.social.core.Cloudinary.uploadOrFirebase(this, path, mediaUri)
                            .addOnSuccessListener(url -> {
                                Map<String, Object> m = new HashMap<>();
                                m.put("authorId", Fb.uid());
                                m.put("authorName", Fb.myName());
                                m.put("authorPhotoUrl", Fb.myPhoto());
                                m.put("mediaUrl", url.toString());
                                m.put("mediaType", mediaType);
                                m.put("caption", cap);
                                m.put("musicMood", MOODS[moodIdx]);
                                m.put("privacy", storyPrivacy);
                                m.put("viewedBy", new ArrayList<String>());
                                m.put("createdAt", FieldValue.serverTimestamp());
                                m.put("expiresAt", new Timestamp(new Date(System.currentTimeMillis() + 24L * 3600_000L)));
                                // Previously a put and an add under two different ids, which
                                // stored the same story twice.
                                com.friendbook.social.core.DataRouter.add("stories", m, (id, error) -> {
                                    if (error != null) { fail(); return; }
                                    Ui.toast(this, "Story shared ✓");
                                    finish();
                                });
                            })
                            .addOnFailureListener(e -> fail());
                });
    }

    private void fail() {
        busy = false;
        share.setText("Share story");
        Ui.toast(this, "Upload failed — check connection");
    }
}
