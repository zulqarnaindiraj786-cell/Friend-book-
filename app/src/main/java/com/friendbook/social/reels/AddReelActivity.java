package com.friendbook.social.reels;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.friendbook.social.core.Cloudinary;
import com.friendbook.social.core.ContentSafetyEngine;
import com.friendbook.social.core.Ui;

import java.util.Locale;

/**
 * 🎬 friendbook Reels Upload & Video Editor:
 * - Pick from Gallery OR Record Camera Video directly.
 * - Live interactive video editing & overlays before upload:
 *   Aa Draggable Text Overlay, 😀 Draggable Sticker/Emoji, 🎨 Color Filters,
 *   ✂️ Start/End MP4 Trim, ⚡ Playback Speed, ♫ Audio Track/Mute, 🖼️ Cover Frame Scrubber.
 */
public class AddReelActivity extends Activity {

    private static final int RC_PICK_VIDEO = 81;
    private static final int RC_RECORD_VIDEO = 82;
    private static final int RC_CAM_PERM = 83;

    private static final String[] FILTER_KEYS = {"none", "vivid", "warm", "cool", "vintage", "bw", "neon", "rose"};
    private static final String[] FILTER_LABELS = {"Original", "✨ Vivid", "🌅 Warm", "❄️ Cool", "🎞️ Vintage", "🖤 B&W", "💜 Neon", "🌸 Rose"};
    private static final int[] FILTER_TINTS = {0x00000000, 0x22FF4081, 0x2CFF8F00, 0x2800B0FF, 0x30D4A359, 0x44212121, 0x2E7C4DFF, 0x28F06292};
    private static final float[] SPEED_STEPS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private Uri videoUri;
    private Cloudinary.VideoMeta videoMeta;
    private boolean safetyVerified = false;
    private boolean safetyScanning = false;
    private boolean previewMuted = false;

    private final ReelUploadManager.EditOptions editOpts = new ReelUploadManager.EditOptions();
    private int totalDurationMs = 0;

    private FrameLayout stage;
    private VideoView previewVideo;
    private ImageView previewPoster;
    private View stageFilterTint;
    private TextView stageTextPill, stageStickerTv;
    private LinearLayout emptyPlaceholder;
    private TextView metaBadge, safetyBadge, soundBadge;

    private LinearLayout editToolsCard, trimPanel, coverPanel;
    private TextView textToolChip, stickerToolChip, filterToolChip, trimToolChip, speedToolChip, audioToolChip, coverToolChip;
    private HorizontalScrollView filterStripScroll;
    private TextView trimRangeLabel, coverTimeLabel;
    private SeekBar trimStartSeek, trimEndSeek, coverSeek;

    private EditText captionInput;
    private LinearLayout progressCard;
    private ProgressBar progressBar;
    private TextView progressTitle, progressPercent;
    private TextView shareBtn;
    private TextView headerShareChip;
    private android.media.MediaPlayer previewMp;
    private android.media.MediaPlayer attachedSoundMp;
    private String initialSoundUrl = "";
    private String initialSoundTitle = "";
    private String initialSoundPhoto = "";
    private LinearLayout attachedSoundBanner;
    private TextView attachedSoundTitleTv;

    private final Handler trimLoopHandler = new Handler(Looper.getMainLooper());
    private final Runnable trimLoopRunnable = new Runnable() {
        @Override public void run() {
            if (previewVideo != null && videoUri != null) {
                try {
                    if (previewVideo.isPlaying()) {
                        int pos = previewVideo.getCurrentPosition();
                        int end = editOpts.trimEndMs > editOpts.trimStartMs ? editOpts.trimEndMs : totalDurationMs;
                        if (end > 0 && pos >= end) previewVideo.seekTo(Math.max(0, editOpts.trimStartMs));
                    }
                } catch (Throwable ignored) { }
                trimLoopHandler.postDelayed(this, 120L);
            }
        }
    };

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
        if (!com.friendbook.social.core.Admin.checkNotSuspended(this)) {
            finish();
            return;
        }

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

        if (getIntent() != null) {
            String sUrl = getIntent().getStringExtra("use_sound_url");
            String sTitle = getIntent().getStringExtra("use_sound_title");
            String sPhoto = getIntent().getStringExtra("use_sound_author_photo");
            if (sUrl != null && !sUrl.trim().isEmpty()) {
                initialSoundUrl = sUrl.trim();
                initialSoundTitle = (sTitle != null && !sTitle.trim().isEmpty())
                        ? sTitle.trim() : "Original sound";
                initialSoundPhoto = sPhoto != null ? sPhoto.trim() : "";
                editOpts.audioUrl = initialSoundUrl;
                editOpts.audioTitle = initialSoundTitle;
                editOpts.soundAuthorPhoto = initialSoundPhoto;
                editOpts.muteOriginalAudio = true;
                previewMuted = true;
            }
        }

        // TikTok-Style Attached Sound Banner (shown when user tapped a Reel's sound to use in their video)
        attachedSoundBanner = Ui.row(this);
        attachedSoundBanner.setGravity(Gravity.CENTER_VERTICAL);
        attachedSoundBanner.setBackground(Ui.roundStroke(this, 0xFF161823, 12, 0xFFFE2C55, 1));
        Ui.pad(attachedSoundBanner, 12, 10, 12, 10);
        LinearLayout.LayoutParams sblpTop = new LinearLayout.LayoutParams(-1, -2);
        sblpTop.bottomMargin = Ui.dp(this, 10);
        attachedSoundBanner.setLayoutParams(sblpTop);
        TextView musicIcon = Ui.tvBold(this, "🎵", 18, 0xFF25F4EE);
        attachedSoundBanner.addView(musicIcon);
        attachedSoundTitleTv = Ui.tvBold(this,
                "Using Sound: " + (initialSoundTitle.isEmpty() ? "Original sound" : initialSoundTitle),
                13f, Color.WHITE);
        attachedSoundTitleTv.setSingleLine(true);
        attachedSoundTitleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        attachedSoundTitleTv.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        attachedSoundBanner.addView(attachedSoundTitleTv, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clearSoundBtn = Ui.roundChip(this, "✕ Remove", 0x33FFFFFF, Color.WHITE, 11.5f);
        Ui.pad(clearSoundBtn, 10, 5, 10, 5);
        clearSoundBtn.setOnClickListener(v -> {
            initialSoundUrl = "";
            initialSoundTitle = "";
            initialSoundPhoto = "";
            editOpts.audioUrl = "";
            editOpts.audioTitle = "";
            editOpts.soundAuthorPhoto = "";
            editOpts.muteOriginalAudio = false;
            previewMuted = false;
            stopAttachedSoundPreview();
            attachedSoundBanner.setVisibility(View.GONE);
            if (previewMp != null) {
                try { previewMp.setVolume(1f, 1f); } catch (Throwable ignored) {}
            }
            if (soundBadge != null) soundBadge.setText("🔊 Sound On");
            if (audioToolChip != null) {
                audioToolChip.setText("♫ Audio");
                audioToolChip.setTextColor(Ui.txt(this));
            }
        });
        attachedSoundBanner.addView(clearSoundBtn);
        attachedSoundBanner.setVisibility(initialSoundUrl.isEmpty() ? View.GONE : View.VISIBLE);
        body.addView(attachedSoundBanner);

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
        stage = new FrameLayout(this);
        stage.setBackground(Ui.roundStroke(this, 0xFF0E1015, 16, Ui.div(this), 1));
        stage.setClipToOutline(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 300));
        slp.topMargin = Ui.dp(this, 12);
        body.addView(stage, slp);

        previewVideo = new VideoView(this);
        stage.addView(previewVideo, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        previewPoster = new ImageView(this);
        previewPoster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewPoster.setVisibility(View.GONE);
        stage.addView(previewPoster, new FrameLayout.LayoutParams(-1, -1));

        stageFilterTint = new View(this);
        stageFilterTint.setVisibility(View.GONE);
        stageFilterTint.setClickable(false);
        stage.addView(stageFilterTint, new FrameLayout.LayoutParams(-1, -1));

        stageStickerTv = Ui.tv(this, "", 44, Color.WHITE);
        stageStickerTv.setVisibility(View.GONE);
        stageStickerTv.setElevation(Ui.dp(this, 12));
        stage.addView(stageStickerTv, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        attachStageDrag(stageStickerTv, true);

        stageTextPill = Ui.tvBold(this, "", 15.5f, Color.WHITE);
        stageTextPill.setGravity(Gravity.CENTER);
        stageTextPill.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        stageTextPill.setVisibility(View.GONE);
        stageTextPill.setElevation(Ui.dp(this, 14));
        Ui.pad(stageTextPill, 14, 7, 14, 7);
        stage.addView(stageTextPill, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        attachStageDrag(stageTextPill, false);

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
                "Add text overlays, stickers, filters, trim & custom speed before sharing",
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
        soundBadge.setOnClickListener(v -> toggleSoundMute());
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

        // 3b. Video Editing & Overlay Tools Bar
        buildEditToolsCard(body);

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

        Intent launch = getIntent();
        if (launch != null) {
            Uri initial = launch.getData();
            if (initial == null && launch.getStringExtra("video_uri") != null) {
                try { initial = Uri.parse(launch.getStringExtra("video_uri")); } catch (Throwable ignored) {}
            }
            if (initial != null) onVideoPicked(initial);
        }
    }

    private void buildEditToolsCard(LinearLayout body) {
        editToolsCard = Ui.col(this);
        editToolsCard.setBackground(Ui.roundStroke(this, Ui.card(this), 14, Ui.div(this), 1));
        Ui.pad(editToolsCard, 12, 10, 12, 10);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(-1, -2);
        etLp.topMargin = Ui.dp(this, 10);

        HorizontalScrollView toolsScroll = new HorizontalScrollView(this);
        toolsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout toolsRow = Ui.row(this);
        toolsRow.setGravity(Gravity.CENTER_VERTICAL);
        toolsRow.addView(textToolChip = makeToolChip("Aa Text Overlay", v -> openTextOverlayDialog()));
        toolsRow.addView(stickerToolChip = makeToolChip("😀 Sticker", v -> openStickerPickerDialog()));
        toolsRow.addView(filterToolChip = makeToolChip("🎨 Filter", v -> toggleFilterStrip()));
        toolsRow.addView(trimToolChip = makeToolChip("✂️ Trim", v -> toggleTrimPanel()));
        toolsRow.addView(speedToolChip = makeToolChip("⚡ Speed 1x", v -> cyclePlaybackSpeed()));
        toolsRow.addView(audioToolChip = makeToolChip("♫ Audio", v -> openAudioDialog()));
        toolsRow.addView(coverToolChip = makeToolChip("🖼️ Cover", v -> toggleCoverPanel()));
        toolsRow.addView(makeToolChip("↺ Reset", v -> resetAllEdits()));
        toolsScroll.addView(toolsRow);
        editToolsCard.addView(toolsScroll);

        filterStripScroll = new HorizontalScrollView(this);
        filterStripScroll.setHorizontalScrollBarEnabled(false);
        filterStripScroll.setVisibility(View.GONE);
        LinearLayout filterRow = Ui.row(this);
        filterRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        for (int i = 0; i < FILTER_KEYS.length; i++) {
            final int idx = i;
            TextView fc = Ui.roundChip(this, FILTER_LABELS[i], Ui.inputBg(this), Ui.txt(this), 12);
            Ui.pad(fc, 12, 6, 12, 6);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
            flp.rightMargin = Ui.dp(this, 6);
            fc.setOnClickListener(v -> applyFilterPreset(idx));
            filterRow.addView(fc, flp);
        }
        filterStripScroll.addView(filterRow);
        editToolsCard.addView(filterStripScroll);

        trimPanel = Ui.col(this);
        trimPanel.setVisibility(View.GONE);
        trimPanel.setPadding(0, Ui.dp(this, 10), 0, 0);
        trimRangeLabel = Ui.tvBold(this, "✂️ Trim Range: Full Video", 12.5f, Ui.txt(this));
        trimPanel.addView(trimRangeLabel);
        LinearLayout startRow = Ui.row(this);
        startRow.setGravity(Gravity.CENTER_VERTICAL);
        startRow.setPadding(0, Ui.dp(this, 4), 0, 0);
        startRow.addView(Ui.tvBold(this, "Start", 11.5f, Ui.sub(this)), new LinearLayout.LayoutParams(Ui.dp(this, 42), -2));
        trimStartSeek = new SeekBar(this); trimStartSeek.setMax(1000); trimStartSeek.setProgress(0);
        startRow.addView(trimStartSeek, new LinearLayout.LayoutParams(0, -2, 1f));
        trimPanel.addView(startRow);
        LinearLayout endRow = Ui.row(this);
        endRow.setGravity(Gravity.CENTER_VERTICAL);
        endRow.setPadding(0, Ui.dp(this, 4), 0, 0);
        endRow.addView(Ui.tvBold(this, "End", 11.5f, Ui.sub(this)), new LinearLayout.LayoutParams(Ui.dp(this, 42), -2));
        trimEndSeek = new SeekBar(this); trimEndSeek.setMax(1000); trimEndSeek.setProgress(1000);
        endRow.addView(trimEndSeek, new LinearLayout.LayoutParams(0, -2, 1f));
        trimPanel.addView(endRow);

        SeekBar.OnSeekBarChangeListener trimListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || totalDurationMs <= 0) return;
                int s = trimStartSeek.getProgress(), e = trimEndSeek.getProgress();
                if (sb == trimStartSeek && s >= e - 50) { s = Math.max(0, e - 50); trimStartSeek.setProgress(s); }
                else if (sb == trimEndSeek && e <= s + 50) { e = Math.min(1000, s + 50); trimEndSeek.setProgress(e); }
                editOpts.trimStartMs = (int) ((s / 1000f) * totalDurationMs);
                editOpts.trimEndMs = (int) ((e / 1000f) * totalDurationMs);
                updateTrimLabelAndBadge();
                try { previewVideo.seekTo(sb == trimStartSeek ? editOpts.trimStartMs : Math.max(0, editOpts.trimEndMs - 300)); } catch (Throwable ignored) {}
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                try { previewVideo.seekTo(Math.max(0, editOpts.trimStartMs)); if (!previewVideo.isPlaying()) previewVideo.start(); } catch (Throwable ignored) {}
            }
        };
        trimStartSeek.setOnSeekBarChangeListener(trimListener);
        trimEndSeek.setOnSeekBarChangeListener(trimListener);
        editToolsCard.addView(trimPanel);

        coverPanel = Ui.col(this);
        coverPanel.setVisibility(View.GONE);
        coverPanel.setPadding(0, Ui.dp(this, 10), 0, 0);
        coverTimeLabel = Ui.tvBold(this, "🖼️ Drag to pick cover thumbnail frame", 12.5f, Ui.txt(this));
        coverPanel.addView(coverTimeLabel);
        coverSeek = new SeekBar(this); coverSeek.setMax(1000); coverSeek.setProgress(0);
        coverSeek.setPadding(0, Ui.dp(this, 6), 0, 0);
        coverSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || totalDurationMs <= 0) return;
                long ms = (long) ((progress / 1000f) * totalDurationMs);
                editOpts.coverTimeMs = ms;
                coverTimeLabel.setText(String.format(Locale.US, "🖼️ Cover frame at %d:%02d", (ms / 1000) / 60, (ms / 1000) % 60));
                try { previewVideo.seekTo((int) ms); } catch (Throwable ignored) {}
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (videoUri != null && editOpts.coverTimeMs >= 0L) extractCoverFrameAsync(videoUri, editOpts.coverTimeMs);
            }
        });
        coverPanel.addView(coverSeek);
        editToolsCard.addView(coverPanel);
        body.addView(editToolsCard, etLp);
    }

    private TextView makeToolChip(String label, View.OnClickListener click) {
        TextView chip = Ui.tvBold(this, label, 12.5f, Ui.txt(this));
        chip.setBackground(Ui.roundStroke(this, Ui.inputBg(this), 12, Ui.div(this), 1));
        Ui.pad(chip, 12, 8, 12, 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(this, 7);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(click);
        return chip;
    }

    private void attachStageDrag(final View target, final boolean isSticker) {
        final float[] dxy = new float[4];
        target.setOnTouchListener((v, ev) -> {
            if (stage == null || stage.getWidth() <= 0 || stage.getHeight() <= 0) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    dxy[0] = v.getTranslationX() - ev.getRawX();
                    dxy[1] = v.getTranslationY() - ev.getRawY();
                    dxy[2] = ev.getRawX(); dxy[3] = ev.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float nx = Math.max(Ui.dp(this, 6), Math.min(stage.getWidth() - v.getWidth() - Ui.dp(this, 6), ev.getRawX() + dxy[0]));
                    float ny = Math.max(Ui.dp(this, 36), Math.min(stage.getHeight() - v.getHeight() - Ui.dp(this, 10), ev.getRawY() + dxy[1]));
                    v.setTranslationX(nx); v.setTranslationY(ny);
                    float normX = Math.max(0.08f, Math.min(0.92f, (nx + v.getWidth() / 2f) / (float) stage.getWidth()));
                    float normY = Math.max(0.08f, Math.min(0.92f, (ny + v.getHeight() / 2f) / (float) stage.getHeight()));
                    if (isSticker) { editOpts.stickerX = normX; editOpts.stickerY = normY; }
                    else { editOpts.overlayX = normX; editOpts.overlayY = normY; }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (Math.hypot(ev.getRawX() - dxy[2], ev.getRawY() - dxy[3]) < Ui.dp(this, 8)) {
                        if (isSticker) {
                            editOpts.stickerScale = editOpts.stickerScale < 1.2f ? 1.35f : (editOpts.stickerScale < 1.6f ? 1.7f : (editOpts.stickerScale > 1.5f ? 0.85f : 1.0f));
                            stageStickerTv.setScaleX(editOpts.stickerScale);
                            stageStickerTv.setScaleY(editOpts.stickerScale);
                        } else openTextOverlayDialog();
                    }
                    return true;
            }
            return false;
        });
    }

    private boolean ensureVideoSelected() {
        if (videoUri == null) { Ui.toast(this, "Pick or record a video first 🎬"); openGalleryPicker(); return false; }
        return true;
    }

    private void openTextOverlayDialog() {
        if (!ensureVideoSelected()) return;
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 14, 18, 8);
        final EditText input = Ui.input(this, "Type text to display on video…");
        input.setText(editOpts.overlayText != null ? editOpts.overlayText : "");
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        TextView colorLbl = Ui.tvBold(this, "Text Color", 12.5f, Ui.sub(this));
        colorLbl.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        box.addView(colorLbl);
        final int[] textColors = {0xFFFFFFFF, 0xFFFFEB3B, 0xFF00E5FF, 0xFFFF4081, 0xFF69F0AE, 0xFFFF9100, 0xFFFF5252};
        LinearLayout colorRow = Ui.row(this);
        for (final int c : textColors) {
            View dot = new View(this);
            dot.setBackground(Ui.roundStroke(this, c, 16, 0xFF888888, 1));
            int sz = Ui.dp(this, 30);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(sz, sz);
            dlp.rightMargin = Ui.dp(this, 8);
            dot.setOnClickListener(v -> { editOpts.overlayColor = c; input.setTextColor(c == 0xFFFFFFFF ? Ui.txt(this) : c); });
            colorRow.addView(dot, dlp);
        }
        box.addView(colorRow);

        TextView bgLbl = Ui.tvBold(this, "Background Style", 12.5f, Ui.sub(this));
        bgLbl.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        box.addView(bgLbl);
        final String[] bgNames = {"Dark Pill", "Blue Pill", "Rose Pill", "Frosted", "No Pill"};
        final int[] bgColors = {0xCC000000, 0xDD1877F2, 0xDDE91E63, 0x66000000, 0x00000000};
        HorizontalScrollView bgScroll = new HorizontalScrollView(this);
        bgScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bgRow = Ui.row(this);
        for (int i = 0; i < bgNames.length; i++) {
            final int bg = bgColors[i];
            TextView bc = Ui.roundChip(this, bgNames[i], Ui.inputBg(this), Ui.txt(this), 11.5f);
            Ui.pad(bc, 10, 5, 10, 5);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.rightMargin = Ui.dp(this, 6);
            bc.setOnClickListener(v -> editOpts.overlayBgColor = bg);
            bgRow.addView(bc, blp);
        }
        bgScroll.addView(bgRow);
        box.addView(bgScroll);

        new AlertDialog.Builder(this)
                .setTitle("Aa Text Overlay")
                .setView(box)
                .setPositiveButton("Apply", (d, w) -> {
                    editOpts.overlayText = input.getText() == null ? "" : input.getText().toString().trim();
                    refreshStageOverlays();
                })
                .setNeutralButton("Remove", (d, w) -> { editOpts.overlayText = ""; refreshStageOverlays(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openStickerPickerDialog() {
        if (!ensureVideoSelected()) return;
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 12, 18, 8);
        TextView sub = Ui.tv(this, "Tap a sticker to place on video (drag to move, tap sticker to resize):", 12.5f, Ui.sub(this));
        sub.setPadding(0, 0, 0, Ui.dp(this, 10));
        box.addView(sub);
        final String[] emojis = {"🔥", "😂", "❤️", "👑", "✨", "💯", "🎉", "🚀", "😍", "🎵", "⚡", "👏", "🤩", "😎", "🙌", "💥", "💎", "🌟"};
        final AlertDialog[] dlgRef = new AlertDialog[1];
        for (int row = 0; row < 3; row++) {
            LinearLayout r = Ui.row(this); r.setGravity(Gravity.CENTER); r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            for (int col = 0; col < 6; col++) {
                final String em = emojis[row * 6 + col];
                TextView tv = Ui.tv(this, em, 26, Ui.txt(this));
                tv.setGravity(Gravity.CENTER);
                tv.setBackground(Ui.round(this, Ui.inputBg(this), 10));
                int sz = Ui.dp(this, 44);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
                lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
                tv.setOnClickListener(v -> {
                    editOpts.stickerText = em;
                    refreshStageOverlays();
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                });
                r.addView(tv, lp);
            }
            box.addView(r);
        }
        dlgRef[0] = new AlertDialog.Builder(this)
                .setTitle("😀 Add Sticker Overlay")
                .setView(box)
                .setNeutralButton("Remove Sticker", (d, w) -> { editOpts.stickerText = ""; refreshStageOverlays(); })
                .setNegativeButton("Close", null)
                .show();
    }

    private void toggleFilterStrip() {
        if (!ensureVideoSelected()) return;
        boolean show = filterStripScroll.getVisibility() != View.VISIBLE;
        filterStripScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { trimPanel.setVisibility(View.GONE); coverPanel.setVisibility(View.GONE); }
    }

    private void applyFilterPreset(int index) {
        if (index < 0 || index >= FILTER_KEYS.length) return;
        editOpts.filterPreset = FILTER_KEYS[index];
        editOpts.filterOverlayColor = FILTER_TINTS[index];
        if (editOpts.filterOverlayColor != 0) {
            stageFilterTint.setBackgroundColor(editOpts.filterOverlayColor);
            stageFilterTint.setVisibility(View.VISIBLE);
            filterToolChip.setText("🎨 " + FILTER_LABELS[index]);
            filterToolChip.setTextColor(Ui.BLUE);
        } else {
            stageFilterTint.setVisibility(View.GONE);
            filterToolChip.setText("🎨 Filter");
            filterToolChip.setTextColor(Ui.txt(this));
        }
    }

    private void toggleTrimPanel() {
        if (!ensureVideoSelected()) return;
        boolean show = trimPanel.getVisibility() != View.VISIBLE;
        trimPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { filterStripScroll.setVisibility(View.GONE); coverPanel.setVisibility(View.GONE); }
    }

    private void updateTrimLabelAndBadge() {
        int sSec = Math.max(0, Math.round(editOpts.trimStartMs / 1000f));
        int eSec = Math.max(sSec + 1, Math.round((editOpts.trimEndMs > 0 ? editOpts.trimEndMs : totalDurationMs) / 1000f));
        int durSec = Math.max(1, eSec - sSec);
        trimRangeLabel.setText(String.format(Locale.US, "✂️ Trim: %d:%02d – %d:%02d (%ds)", sSec / 60, sSec % 60, eSec / 60, eSec % 60, durSec));
        trimToolChip.setText(String.format(Locale.US, "✂️ %ds", durSec));
        trimToolChip.setTextColor(Ui.BLUE);
        refreshMetaBadgeText();
    }

    private void cyclePlaybackSpeed() {
        if (!ensureVideoSelected()) return;
        int nextIdx = 0;
        for (int i = 0; i < SPEED_STEPS.length; i++) {
            if (Math.abs(editOpts.playbackSpeed - SPEED_STEPS[i]) < 0.05f) { nextIdx = (i + 1) % SPEED_STEPS.length; break; }
        }
        editOpts.playbackSpeed = SPEED_STEPS[nextIdx];
        speedToolChip.setText(editOpts.playbackSpeed == (int) editOpts.playbackSpeed
                ? String.format(Locale.US, "⚡ Speed %dx", (int) editOpts.playbackSpeed)
                : String.format(Locale.US, "⚡ Speed %sx", editOpts.playbackSpeed));
        speedToolChip.setTextColor(Math.abs(editOpts.playbackSpeed - 1.0f) > 0.02f ? Ui.BLUE : Ui.txt(this));
        if (previewMp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { PlaybackParams p = previewMp.getPlaybackParams(); p.setSpeed(editOpts.playbackSpeed); previewMp.setPlaybackParams(p); }
            catch (Throwable ignored) {}
        }
    }

    private void toggleSoundMute() {
        previewMuted = !previewMuted;
        editOpts.muteOriginalAudio = previewMuted;
        soundBadge.setText(previewMuted ? "🔇 Muted" : "🔊 Sound On");
        audioToolChip.setText(previewMuted ? "🔇 Muted" : (editOpts.audioTitle != null && !editOpts.audioTitle.isEmpty() ? "♫ " + editOpts.audioTitle : "♫ Audio"));
        if (previewMp != null) {
            try { float vol = previewMuted ? 0f : 1f; previewMp.setVolume(vol, vol); } catch (Throwable ignored) {}
        }
    }

    private void openAudioDialog() {
        if (!ensureVideoSelected()) return;
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 12, 18, 8);
        final TextView muteSwitchBtn = Ui.tvBold(this,
                previewMuted ? "🔇 Original Audio: Muted (Tap to Unmute)" : "🔊 Original Audio: On (Tap to Mute)",
                13f, previewMuted ? 0xFFE53935 : Ui.BLUE);
        muteSwitchBtn.setBackground(Ui.roundStroke(this, Ui.inputBg(this), 10, Ui.div(this), 1));
        Ui.pad(muteSwitchBtn, 12, 10, 12, 10);
        muteSwitchBtn.setOnClickListener(v -> {
            toggleSoundMute();
            muteSwitchBtn.setText(previewMuted ? "🔇 Original Audio: Muted (Tap to Unmute)" : "🔊 Original Audio: On (Tap to Mute)");
            muteSwitchBtn.setTextColor(previewMuted ? 0xFFE53935 : Ui.BLUE);
        });
        box.addView(muteSwitchBtn, new LinearLayout.LayoutParams(-1, -2));

        TextView titleLbl = Ui.tvBold(this, "Custom Audio / Music Label (♫)", 12.5f, Ui.sub(this));
        titleLbl.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        box.addView(titleLbl);
        final EditText audioInput = Ui.input(this, "e.g. Aesthetic Beats · Original Sound");
        audioInput.setText(editOpts.audioTitle != null ? editOpts.audioTitle : "");
        box.addView(audioInput, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle("♫ Audio & Sound")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    editOpts.audioTitle = audioInput.getText() == null ? "" : audioInput.getText().toString().trim();
                    audioToolChip.setText(!editOpts.audioTitle.isEmpty() ? ("♫ " + editOpts.audioTitle) : (previewMuted ? "🔇 Muted" : "♫ Audio"));
                    audioToolChip.setTextColor((!editOpts.audioTitle.isEmpty() || previewMuted) ? Ui.BLUE : Ui.txt(this));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void toggleCoverPanel() {
        if (!ensureVideoSelected()) return;
        boolean show = coverPanel.getVisibility() != View.VISIBLE;
        coverPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { filterStripScroll.setVisibility(View.GONE); trimPanel.setVisibility(View.GONE); }
    }

    private void extractCoverFrameAsync(final Uri src, final long timeMs) {
        new Thread(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(getApplicationContext(), src);
                Bitmap frame = mmr.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    int maxDim = 540, bw = frame.getWidth(), bh = frame.getHeight();
                    if (bw > maxDim || bh > maxDim) {
                        float r = Math.min((float) maxDim / bw, (float) maxDim / bh);
                        Bitmap scaled = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(bw * r)), Math.max(1, Math.round(bh * r)), true);
                        if (scaled != frame) frame.recycle();
                        frame = scaled;
                    }
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(24 * 1024);
                    frame.compress(Bitmap.CompressFormat.JPEG, 76, bos);
                    final byte[] bytes = bos.toByteArray();
                    final Bitmap previewBmp = frame;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (videoMeta != null) videoMeta.posterJpeg = bytes;
                        previewPoster.setImageBitmap(previewBmp);
                        coverToolChip.setText("🖼️ Cover ✓");
                        coverToolChip.setTextColor(Ui.BLUE);
                    });
                }
            } catch (Throwable ignored) {
            } finally { try { mmr.release(); } catch (Throwable ignored) {} }
        }, "reel-cover-pick").start();
    }

    private void resetAllEdits() {
        editOpts.overlayText = ""; editOpts.overlayColor = 0xFFFFFFFF; editOpts.overlayBgColor = 0xCC000000;
        editOpts.overlayX = 0.5f; editOpts.overlayY = 0.24f;
        editOpts.stickerText = ""; editOpts.stickerX = 0.78f; editOpts.stickerY = 0.32f; editOpts.stickerScale = 1.0f;
        editOpts.filterPreset = "none"; editOpts.filterOverlayColor = 0;
        editOpts.playbackSpeed = 1.0f;
        editOpts.trimStartMs = 0; editOpts.trimEndMs = 0; editOpts.coverTimeMs = -1L;
        if (!initialSoundUrl.isEmpty()) {
            editOpts.audioUrl = initialSoundUrl;
            editOpts.audioTitle = initialSoundTitle;
            editOpts.soundAuthorPhoto = initialSoundPhoto;
            editOpts.muteOriginalAudio = true;
            previewMuted = true;
        } else {
            editOpts.muteOriginalAudio = false;
            editOpts.audioTitle = "";
            editOpts.audioUrl = "";
            editOpts.soundAuthorPhoto = "";
            previewMuted = false;
        }
        trimStartSeek.setProgress(0); trimEndSeek.setProgress(1000); coverSeek.setProgress(0);
        filterStripScroll.setVisibility(View.GONE); trimPanel.setVisibility(View.GONE); coverPanel.setVisibility(View.GONE);
        soundBadge.setText(!initialSoundUrl.isEmpty() ? "🎵 Sound Attached" : "🔊 Sound On");
        textToolChip.setText("Aa Text Overlay"); textToolChip.setTextColor(Ui.txt(this));
        stickerToolChip.setText("😀 Sticker"); stickerToolChip.setTextColor(Ui.txt(this));
        filterToolChip.setText("🎨 Filter"); filterToolChip.setTextColor(Ui.txt(this));
        trimToolChip.setText("✂️ Trim"); trimToolChip.setTextColor(Ui.txt(this));
        speedToolChip.setText("⚡ Speed 1x"); speedToolChip.setTextColor(Ui.txt(this));
        audioToolChip.setText(!initialSoundTitle.isEmpty() ? ("♫ " + initialSoundTitle) : "♫ Audio");
        audioToolChip.setTextColor(!initialSoundTitle.isEmpty() ? Ui.BLUE : Ui.txt(this));
        coverToolChip.setText("🖼️ Cover"); coverToolChip.setTextColor(Ui.txt(this));
        if (previewMp != null) {
            try {
                float vol = previewMuted ? 0f : 1f;
                previewMp.setVolume(vol, vol);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) previewMp.setPlaybackParams(previewMp.getPlaybackParams().setSpeed(1.0f));
            } catch (Throwable ignored) {}
        }
        refreshStageOverlays();
        refreshMetaBadgeText();
    }

    private void startAttachedSoundPreview() {
        stopAttachedSoundPreview();
        if (editOpts.audioUrl == null || editOpts.audioUrl.isEmpty()) return;
        try {
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            attachedSoundMp = mp;
            mp.setLooping(true);
            java.io.File cached = ReelVideoCache.getCachedFile(this, editOpts.audioUrl);
            if (cached != null) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(cached)) {
                    mp.setDataSource(fis.getFD());
                }
            } else {
                mp.setDataSource(this, Uri.parse(editOpts.audioUrl));
            }
            mp.setOnPreparedListener(player -> {
                if (player == attachedSoundMp && !isFinishing() && !isDestroyed()) {
                    try { player.start(); } catch (Throwable ignored) {}
                }
            });
            mp.prepareAsync();
        } catch (Throwable ignored) { }
    }

    private void stopAttachedSoundPreview() {
        if (attachedSoundMp != null) {
            final android.media.MediaPlayer old = attachedSoundMp;
            attachedSoundMp = null;
            new Thread(() -> {
                try { old.reset(); } catch (Throwable ignored) {}
                try { old.release(); } catch (Throwable ignored) {}
            }, "attached-sound-stop").start();
        }
    }

    private void refreshStageOverlays() {
        if (editOpts.filterOverlayColor != 0) {
            stageFilterTint.setBackgroundColor(editOpts.filterOverlayColor);
            stageFilterTint.setVisibility(View.VISIBLE);
        } else stageFilterTint.setVisibility(View.GONE);

        if (editOpts.overlayText != null && !editOpts.overlayText.trim().isEmpty()) {
            stageTextPill.setText(editOpts.overlayText.trim());
            stageTextPill.setTextColor(editOpts.overlayColor != 0 ? editOpts.overlayColor : Color.WHITE);
            stageTextPill.setBackground((editOpts.overlayBgColor >>> 24) > 0 ? Ui.round(this, editOpts.overlayBgColor, 14) : null);
            stageTextPill.setVisibility(View.VISIBLE);
            textToolChip.setText("Aa Text ✓"); textToolChip.setTextColor(Ui.BLUE);
            stage.post(() -> {
                int sw = stage.getWidth(), sh = stage.getHeight(), pw = stageTextPill.getWidth(), ph = stageTextPill.getHeight();
                if (sw > 0 && sh > 0) {
                    stageTextPill.setTranslationX(Math.max(Ui.dp(this, 8), Math.min(sw - pw - Ui.dp(this, 8), editOpts.overlayX * sw - pw / 2f)));
                    stageTextPill.setTranslationY(Math.max(Ui.dp(this, 38), Math.min(sh - ph - Ui.dp(this, 10), editOpts.overlayY * sh - ph / 2f)));
                }
            });
        } else {
            stageTextPill.setVisibility(View.GONE);
            textToolChip.setText("Aa Text Overlay"); textToolChip.setTextColor(Ui.txt(this));
        }

        if (editOpts.stickerText != null && !editOpts.stickerText.trim().isEmpty()) {
            stageStickerTv.setText(editOpts.stickerText.trim());
            stageStickerTv.setScaleX(editOpts.stickerScale); stageStickerTv.setScaleY(editOpts.stickerScale);
            stageStickerTv.setVisibility(View.VISIBLE);
            stickerToolChip.setText("😀 " + editOpts.stickerText.trim()); stickerToolChip.setTextColor(Ui.BLUE);
            stage.post(() -> {
                int sw = stage.getWidth(), sh = stage.getHeight(), vw = stageStickerTv.getWidth(), vh = stageStickerTv.getHeight();
                if (sw > 0 && sh > 0) {
                    stageStickerTv.setTranslationX(Math.max(Ui.dp(this, 8), Math.min(sw - vw - Ui.dp(this, 8), editOpts.stickerX * sw - vw / 2f)));
                    stageStickerTv.setTranslationY(Math.max(Ui.dp(this, 38), Math.min(sh - vh - Ui.dp(this, 10), editOpts.stickerY * sh - vh / 2f)));
                }
            });
        } else {
            stageStickerTv.setVisibility(View.GONE);
            stickerToolChip.setText("😀 Sticker"); stickerToolChip.setTextColor(Ui.txt(this));
        }
    }

    private void refreshMetaBadgeText() {
        if (videoMeta == null) return;
        StringBuilder sb = new StringBuilder();
        int durSec = videoMeta.durationSec;
        if (editOpts.trimEndMs > editOpts.trimStartMs && totalDurationMs > 0) {
            durSec = Math.max(1, Math.round((editOpts.trimEndMs - editOpts.trimStartMs) / 1000f));
        }
        if (durSec > 0) sb.append(String.format(Locale.US, "⏱ %d:%02d", durSec / 60, durSec % 60));
        if (videoMeta.sizeBytes > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.US, "%.1f MB", videoMeta.sizeBytes / 1048576f));
        }
        if (videoMeta.width > 0 && videoMeta.height > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(videoMeta.width).append("×").append(videoMeta.height);
        }
        if (videoMeta.frameRate > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(videoMeta.frameRate).append(" FPS");
        }
        if (sb.length() > 0) { metaBadge.setText(sb.toString()); metaBadge.setVisibility(View.VISIBLE); }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ReelUploadManager.addListener(uploadListener);
        if (videoUri != null) { trimLoopHandler.removeCallbacks(trimLoopRunnable); trimLoopHandler.post(trimLoopRunnable); }
    }

    @Override
    protected void onStop() {
        super.onStop();
        ReelUploadManager.removeListener(uploadListener);
        trimLoopHandler.removeCallbacks(trimLoopRunnable);
        stopAttachedSoundPreview();
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
        totalDurationMs = 0;

        emptyPlaceholder.setVisibility(View.GONE);
        soundBadge.setVisibility(View.VISIBLE);
        safetyBadge.setVisibility(View.GONE);
        resetAllEdits();

        previewVideo.setVideoURI(picked);
        previewVideo.setOnPreparedListener(mp -> {
            previewMp = mp;
            mp.setLooping(true);
            int d = mp.getDuration();
            if (d > 0) { totalDurationMs = d; }
            float vol = previewMuted ? 0f : 1f;
            mp.setVolume(vol, vol);
            if (Math.abs(editOpts.playbackSpeed - 1.0f) > 0.02f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(editOpts.playbackSpeed)); } catch (Throwable ignored) {}
            }
            previewPoster.setVisibility(View.GONE);
            previewVideo.start();
            if (editOpts.audioUrl != null && !editOpts.audioUrl.isEmpty()) {
                startAttachedSoundPreview();
            }
            trimLoopHandler.removeCallbacks(trimLoopRunnable);
            trimLoopHandler.post(trimLoopRunnable);
        });

        new Thread(() -> {
            final Cloudinary.VideoMeta m = Cloudinary.inspectVideo(getApplicationContext(), picked);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !picked.equals(videoUri)) return;
                videoMeta = m;
                if (totalDurationMs <= 0 && m.durationSec > 0) {
                    totalDurationMs = m.durationSec * 1000;
                }
                if (m.posterJpeg != null && m.posterJpeg.length > 0 && !previewVideo.isPlaying()) {
                    previewPoster.setImageBitmap(BitmapFactory.decodeByteArray(m.posterJpeg, 0, m.posterJpeg.length));
                    previewPoster.setVisibility(View.VISIBLE);
                }
                refreshMetaBadgeText();
            });
        }, "reel-meta-inspect").start();

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
                safetyBadge.setVisibility(View.GONE);
            }
        });
    }

    private void startReelUpload() {
        if (!com.friendbook.social.core.Admin.checkNotSuspended(this)) return;
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
        ContentSafetyEngine.Verdict textV = ContentSafetyEngine.checkText(
                cap + " " + (editOpts.overlayText != null ? editOpts.overlayText : ""));
        if (textV.unsafe) {
            Ui.toast(this, textV.message);
            return;
        }
        try { previewVideo.pause(); } catch (Throwable ignored) { }
        ReelUploadManager.startUpload(this, videoUri, cap, videoMeta, safetyVerified, editOpts);
    }
}
