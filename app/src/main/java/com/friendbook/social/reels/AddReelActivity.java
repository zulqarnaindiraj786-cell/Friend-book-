package com.friendbook.social.reels;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import com.friendbook.social.core.Cloudinary;
import com.friendbook.social.core.ContentSafetyEngine;
import com.friendbook.social.core.Ui;

import java.util.Locale;

/**
 * 🎬 friendbook Pro Reels Upload Studio:
 * - Pick from Gallery OR Record Camera Video directly.
 * - Pre-verifies ContentSafetyEngine ONCE on video selection (zero double-scan wait on Upload!).
 * - Extracts instant cover thumbnail + duration/resolution/size metadata in ~25ms.
 * - Live % + MB progress bar + 1-tap "Continue in Background & Watch Reels" instant publishing.
 */
public class AddReelActivity extends Activity {

    private static final int RC_PICK_VIDEO = 81;
    private static final int RC_RECORD_VIDEO = 82;
    private static final int RC_CAM_PERM = 83;

    private Uri videoUri;
    private Cloudinary.VideoMeta videoMeta;
    private boolean safetyVerified = false;
    private boolean safetyScanning = false;
    private boolean previewMuted = false;

    private VideoView previewVideo;
    private ImageView previewPoster;
    private LinearLayout emptyPlaceholder;
    private TextView metaBadge, safetyBadge, soundBadge;
    private EditText captionInput;
    private LinearLayout progressCard;
    private ProgressBar progressBar;
    private TextView progressTitle, progressPercent;
    private TextView shareBtn;
    private TextView headerShareChip;
    private android.media.MediaPlayer previewMp;

    private final ReelUploadManager.Listener uploadListener =
            (active, percent, sentBytes, totalBytes, message, succeeded, error) -> {
                if (isFinishing() || isDestroyed()) return;
                if (active) {
                    progressCard.setVisibility(View.VISIBLE);
                    progressBar.setProgress(Math.max(2, percent));
                    progressPercent.setText(percent + "%");
                    progressTitle.setText(message != null && !message.isEmpty()
                            ? message : ("Uploading Reel… " + percent + "%"));
                    shareBtn.setEnabled(false);
                    shareBtn.setText("⏫ Uploading… " + percent + "%");
                    if (headerShareChip != null) {
                        headerShareChip.setEnabled(false);
                        headerShareChip.setAlpha(0.6f);
                    }
                } else if (succeeded) {
                    finish();
                } else if (error != null && !error.isEmpty()) {
                    progressCard.setVisibility(View.GONE);
                    shareBtn.setEnabled(true);
                    shareBtn.setText("🚀 Upload Reel Now");
                    if (headerShareChip != null) {
                        headerShareChip.setEnabled(true);
                        headerShareChip.setAlpha(1f);
                    }
                    Ui.toast(this, error);
                }
            };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarLight(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // 1. Crisp White Top Bar: [ ← ] Upload Reel                      [ Upload ]
        LinearLayout bar = Ui.pageBar(this, "Upload Reel");
        headerShareChip = Ui.roundChip(this, "Upload", Ui.BLUE, Color.WHITE, 13);
        Ui.pad(headerShareChip, 16, 7, 16, 7);
        headerShareChip.setOnClickListener(v -> startReelUpload());
        bar.addView(headerShareChip);
        root.addView(bar);
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 14, 14, 14, 24);

        // 2. Source Selector Row: [ 🖼️ Pick from Gallery ]  [ 📷 Record Video ]
        LinearLayout pickRow = Ui.row(this);
        pickRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView galleryBtn = Ui.tvBold(this, "🖼️ Pick Video from Gallery", 13.5f, Color.WHITE);
        galleryBtn.setGravity(Gravity.CENTER);
        galleryBtn.setBackground(Ui.round(this, Ui.BLUE, 12));
        Ui.pad(galleryBtn, 12, 12, 12, 12);
        galleryBtn.setOnClickListener(v -> openGalleryPicker());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0, -2, 1f);
        glp.rightMargin = Ui.dp(this, 8);
        pickRow.addView(galleryBtn, glp);

        TextView recordBtn = Ui.tvBold(this, "📷 Record Camera", 13.5f, Ui.txt(this));
        recordBtn.setGravity(Gravity.CENTER);
        recordBtn.setBackground(Ui.roundStroke(this, Ui.card(this), 12, Ui.div(this), 1));
        Ui.pad(recordBtn, 12, 12, 12, 12);
        recordBtn.setOnClickListener(v -> openCameraRecorder());
        pickRow.addView(recordBtn, new LinearLayout.LayoutParams(0, -2, 0.85f));
        body.addView(pickRow);

        // 3. Vertical 9:16 Theater Preview Stage
        FrameLayout stage = new FrameLayout(this);
        stage.setBackground(Ui.roundStroke(this, 0xFF0E1015, 16, Ui.div(this), 1));
        stage.setClipToOutline(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 300));
        slp.topMargin = Ui.dp(this, 12);
        body.addView(stage, slp);

        previewVideo = new VideoView(this);
        stage.addView(previewVideo, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        previewPoster = new ImageView(this);
        previewPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewPoster.setVisibility(View.GONE);
        stage.addView(previewPoster, new FrameLayout.LayoutParams(-1, -1));

        emptyPlaceholder = Ui.col(this);
        emptyPlaceholder.setGravity(Gravity.CENTER);
        Ui.pad(emptyPlaceholder, 24, 24, 24, 24);
        TextView bigIcon = Ui.tv(this, "🎬", 48, Color.WHITE);
        bigIcon.setGravity(Gravity.CENTER);
        emptyPlaceholder.addView(bigIcon);
        TextView emptyTitle = Ui.tvBold(this, "Tap to Select Your Reel Video", 16, Color.WHITE);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        emptyPlaceholder.addView(emptyTitle);
        TextView emptySub = Ui.tv(this,
                "Vertical 9:16 videos get the best full-screen reach · Turbo upload enabled ⚡",
                12.5f, 0xFFB0B3B8);
        emptySub.setGravity(Gravity.CENTER);
        emptyPlaceholder.addView(emptySub);
        emptyPlaceholder.setOnClickListener(v -> openGalleryPicker());
        stage.addView(emptyPlaceholder, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // Top-left video info badge (duration · MB · resolution)
        metaBadge = Ui.tvBold(this, "", 11.5f, Color.WHITE);
        metaBadge.setBackground(Ui.round(this, 0xAA000000, 10));
        Ui.pad(metaBadge, 10, 5, 10, 5);
        metaBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams mblp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        mblp.setMargins(Ui.dp(this, 10), Ui.dp(this, 10), 0, 0);
        stage.addView(metaBadge, mblp);

        // Top-right mute toggle badge
        soundBadge = Ui.tvBold(this, "🔊 Sound On", 11.5f, Color.WHITE);
        soundBadge.setBackground(Ui.round(this, 0xAA000000, 10));
        Ui.pad(soundBadge, 10, 5, 10, 5);
        soundBadge.setVisibility(View.GONE);
        soundBadge.setOnClickListener(v -> {
            previewMuted = !previewMuted;
            soundBadge.setText(previewMuted ? "🔇 Muted" : "🔊 Sound On");
            if (previewMp != null) {
                try {
                    float vol = previewMuted ? 0f : 1f;
                    previewMp.setVolume(vol, vol);
                } catch (Throwable ignored) { }
            }
        });
        FrameLayout.LayoutParams sblp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sblp.setMargins(0, Ui.dp(this, 10), Ui.dp(this, 10), 0);
        stage.addView(soundBadge, sblp);

        // Bottom-left instant safety verification badge
        safetyBadge = Ui.tvBold(this, "", 11.5f, Color.WHITE);
        safetyBadge.setBackground(Ui.round(this, 0xCC1E293B, 10));
        Ui.pad(safetyBadge, 10, 5, 10, 5);
        safetyBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams sfblp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        sfblp.setMargins(Ui.dp(this, 10), 0, 0, Ui.dp(this, 10));
        stage.addView(safetyBadge, sfblp);

        // 4. Live Upload Progress Card (with 1-tap Background Upload handoff!)
        progressCard = Ui.col(this);
        progressCard.setBackground(Ui.roundStroke(this, Ui.card(this), 14, Ui.BLUE, 1));
        Ui.pad(progressCard, 14, 12, 14, 12);
        progressCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(-1, -2);
        pclp.topMargin = Ui.dp(this, 12);

        LinearLayout progTop = Ui.row(this);
        progTop.setGravity(Gravity.CENTER_VERTICAL);
        progressTitle = Ui.tvBold(this, "Uploading Reel…", 13.5f, Ui.txt(this));
        progTop.addView(progressTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        progressPercent = Ui.tvBold(this, "0%", 14f, Ui.BLUE);
        progTop.addView(progressPercent);
        progressCard.addView(progTop);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 10));
        pblp.topMargin = Ui.dp(this, 8);
        pblp.bottomMargin = Ui.dp(this, 10);
        progressCard.addView(progressBar, pblp);

        TextView bgContinueBtn = Ui.tvBold(this,
                "⚡ Continue in Background & Watch Reels ›", 13f, Ui.BLUE);
        bgContinueBtn.setGravity(Gravity.CENTER);
        bgContinueBtn.setBackground(Ui.round(this, Ui.unread(this), 10));
        Ui.pad(bgContinueBtn, 12, 9, 12, 9);
        bgContinueBtn.setOnClickListener(v -> {
            Ui.toast(this, "⏫ Uploading in background — your Reel is ready to watch!");
            finish();
        });
        progressCard.addView(bgContinueBtn, new LinearLayout.LayoutParams(-1, -2));
        body.addView(progressCard, pclp);

        // 5. Caption + Quick Hashtag Chips Card
        LinearLayout capCard = Ui.col(this);
        capCard.setBackground(Ui.roundStroke(this, Ui.card(this), 14, Ui.div(this), 1));
        Ui.pad(capCard, 12, 12, 12, 12);
        LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(-1, -2);
        cclp.topMargin = Ui.dp(this, 12);

        TextView capLabel = Ui.tvBold(this, "Caption & Hashtags", 13f, Ui.txt(this));
        capCard.addView(capLabel);

        captionInput = Ui.input(this, "Write a catchy caption for your Reel… #Trending");
        captionInput.setMinLines(2);
        captionInput.setMaxLines(4);
        captionInput.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams cilp = new LinearLayout.LayoutParams(-1, -2);
        cilp.topMargin = Ui.dp(this, 8);
        capCard.addView(captionInput, cilp);

        HorizontalScrollView tagScroll = new HorizontalScrollView(this);
        tagScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tagRow = Ui.row(this);
        tagRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        String[] quickTags = {"#Trending", "#Viral", "#Funny", "#Music", "#Moments", "#Friends", "#Shorts"};
        for (final String tag : quickTags) {
            TextView chip = Ui.roundChip(this, tag, Ui.inputBg(this), Ui.BLUE, 12);
            Ui.pad(chip, 11, 5, 11, 5);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.rightMargin = Ui.dp(this, 6);
            chip.setOnClickListener(v -> appendTag(tag));
            tagRow.addView(chip, tlp);
        }
        tagScroll.addView(tagRow);
        capCard.addView(tagScroll);
        body.addView(capCard, cclp);

        // 6. Primary High-Visibility Upload Button
        shareBtn = Ui.btn(this, "🚀 Upload Reel Now");
        LinearLayout.LayoutParams sbtnLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
        sbtnLp.topMargin = Ui.dp(this, 14);
        shareBtn.setOnClickListener(v -> startReelUpload());
        body.addView(shareBtn, sbtnLp);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ReelUploadManager.addListener(uploadListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ReelUploadManager.removeListener(uploadListener);
        try { previewVideo.pause(); } catch (Throwable ignored) { }
    }

    private void appendTag(String tag) {
        String cur = captionInput.getText() == null ? "" : captionInput.getText().toString();
        if (cur.contains(tag)) return;
        String next = (cur.isEmpty() || cur.endsWith(" ")) ? (cur + tag + " ") : (cur + " " + tag + " ");
        captionInput.setText(next);
        captionInput.setSelection(next.length());
    }

    private void openGalleryPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("video/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Select Reel Video"), RC_PICK_VIDEO);
        } catch (Throwable t) {
            Intent fallback = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            startActivityForResult(fallback, RC_PICK_VIDEO);
        }
    }

    private void openCameraRecorder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, RC_CAM_PERM);
            return;
        }
        launchVideoCaptureIntent();
    }

    private void launchVideoCaptureIntent() {
        try {
            Intent takeVideo = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            takeVideo.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60);
            takeVideo.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
            startActivityForResult(takeVideo, RC_RECORD_VIDEO);
        } catch (Throwable t) {
            Ui.toast(this, "Camera recorder unavailable — pick from gallery instead");
            openGalleryPicker();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_CAM_PERM && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchVideoCaptureIntent();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if ((req == RC_PICK_VIDEO || req == RC_RECORD_VIDEO) && res == RESULT_OK
                && data != null && data.getData() != null) {
            onVideoPicked(data.getData());
        }
    }

    private void onVideoPicked(final Uri picked) {
        videoUri = picked;
        videoMeta = null;
        safetyVerified = false;
        safetyScanning = true;

        emptyPlaceholder.setVisibility(View.GONE);
        soundBadge.setVisibility(View.VISIBLE);
        safetyBadge.setVisibility(View.VISIBLE);
        safetyBadge.setText("🛡 Checking video safety…");
        safetyBadge.setBackground(Ui.round(this, 0xCC1E293B, 10));
        Ui.pad(safetyBadge, 10, 5, 10, 5);

        // Start instant preview
        previewVideo.setVideoURI(picked);
        previewVideo.setOnPreparedListener(mp -> {
            previewMp = mp;
            mp.setLooping(true);
            float vol = previewMuted ? 0f : 1f;
            mp.setVolume(vol, vol);
            previewPoster.setVisibility(View.GONE);
            previewVideo.start();
        });

        // Extract duration, MB size, resolution & instant poster thumbnail off main thread (~25ms)
        new Thread(() -> {
            final Cloudinary.VideoMeta m = Cloudinary.inspectVideo(getApplicationContext(), picked);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !picked.equals(videoUri)) return;
                videoMeta = m;
                if (m.posterJpeg != null && m.posterJpeg.length > 0 && !previewVideo.isPlaying()) {
                    previewPoster.setImageBitmap(
                            BitmapFactory.decodeByteArray(m.posterJpeg, 0, m.posterJpeg.length));
                    previewPoster.setVisibility(View.VISIBLE);
                }
                StringBuilder sb = new StringBuilder();
                if (m.durationSec > 0) {
                    sb.append(String.format(Locale.US, "⏱ %d:%02d", m.durationSec / 60, m.durationSec % 60));
                }
                if (m.sizeBytes > 0) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(String.format(Locale.US, "%.1f MB", m.sizeBytes / 1048576f));
                }
                if (m.width > 0 && m.height > 0) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(m.width).append("×").append(m.height);
                }
                if (sb.length() > 0) {
                    metaBadge.setText(sb.toString());
                    metaBadge.setVisibility(View.VISIBLE);
                }
            });
        }, "reel-meta-inspect").start();

        // Run ContentSafetyEngine ONCE right now so Upload button starts in 0.0ms!
        ContentSafetyEngine.scanMediaAsync(this, java.util.Collections.singletonList(picked), true, "", verdict -> {
            if (isFinishing() || isDestroyed() || !picked.equals(videoUri)) return;
            safetyScanning = false;
            if (verdict != null && verdict.unsafe) {
                videoUri = null;
                videoMeta = null;
                safetyVerified = false;
                try { previewVideo.stopPlayback(); } catch (Throwable ignored) { }
                previewPoster.setVisibility(View.GONE);
                metaBadge.setVisibility(View.GONE);
                soundBadge.setVisibility(View.GONE);
                safetyBadge.setVisibility(View.GONE);
                emptyPlaceholder.setVisibility(View.VISIBLE);
                Ui.toast(this, verdict.message);
            } else {
                safetyVerified = true;
                safetyBadge.setText("🛡 Ready for Turbo Upload ✓");
                safetyBadge.setBackground(Ui.round(this, 0xCC065F46, 10));
                Ui.pad(safetyBadge, 10, 5, 10, 5);
            }
        });
    }

    private void startReelUpload() {
        if (videoUri == null) {
            Ui.toast(this, "Pick or record a video first 🎬");
            openGalleryPicker();
            return;
        }
        if (safetyScanning) {
            Ui.toast(this, "⏳ Finishing 1-second safety check, tap again in a moment…");
            return;
        }
        String cap = captionInput.getText() == null ? "" : captionInput.getText().toString().trim();
        ContentSafetyEngine.Verdict textV = ContentSafetyEngine.checkText(cap);
        if (textV.unsafe) {
            Ui.toast(this, textV.message);
            return;
        }
        try { previewVideo.pause(); } catch (Throwable ignored) { }
        ReelUploadManager.startUpload(this, videoUri, cap, videoMeta, safetyVerified);
    }
}
