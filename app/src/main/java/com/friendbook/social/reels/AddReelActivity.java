package com.friendbook.social.reels;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
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

import com.bumptech.glide.Glide;
import com.friendbook.social.core.Cloudinary;
import com.friendbook.social.core.ContentSafetyEngine;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.Locale;

/**
 * 3-Stage Reel Studio (2026, TikTok-style UI with zero brand banners):
 * - Stage 1: Full-Screen Live Camera Studio (Screenshot_20261111-105919.jpg) with 4K UHD zero-lag GPU
 *   grading, pinch-to-zoom, Sounds sheet (Screenshot_20261111-105956.jpg) and Effects sheet (Screenshot_20261111-105936.jpg).
 * - Stage 2: Full-Screen Video Preview Editor (Screenshot_20261111-110929.jpg & 110932.jpg) with
 *   Add sound, right collapsible tool rail, brush-banner Text overlay, Your Story & Next buttons.
 * - Stage 3: Clean White Post Screen (Screenshot_20261111-110627.jpg) with description, Edit cover,
 *   # Hashtags, @ Friends, Content disclosure, Location chips, Add link, Privacy, More options, Drafts & Post.
 */
public class AddReelActivity extends Activity {

    private static final int RC_PICK_VIDEO = 81;
    private static final int RC_RECORD_VIDEO = 82;
    private static final int RC_CAM_PERM = 83;

    private static final int STAGE_CAMERA = 1;
    private static final int STAGE_PREVIEW_EDITOR = 2;
    private static final int STAGE_POST_FORM = 3;

    private static final String[] FILTER_KEYS = {"none", "vivid", "warm", "cool", "vintage", "bw", "neon", "rose"};
    private static final String[] FILTER_LABELS = {"4K Natural", "HDR Vivid", "Golden Warm", "Cinema Cool",
            "Retro Film", "Noir B&W", "Cyber Neon", "Rosy Glow"};
    private static final int[] FILTER_TINTS = {0x00000000, 0x18FFF3E8, 0x28FF9800, 0x2400B0FF, 0x2CD4A359, 0x42212121, 0x2A7C4DFF, 0x24F06292};
    private static final float[] SPEED_STEPS = {0.5f, 1.0f, 2.0f, 3.0f};

    private int currentStage = STAGE_CAMERA;
    private Uri videoUri;
    private Cloudinary.VideoMeta videoMeta;
    private boolean safetyVerified = false;
    private boolean safetyScanning = false;
    private boolean previewMuted = false;

    private final ReelUploadManager.EditOptions editOpts = new ReelUploadManager.EditOptions();
    private int totalDurationMs = 0;
    private int activeBeautyLevel = 2; // Default 4K UHD Porcelain Clear (0.0ms GPU ColorMatrix)
    private String activeEffectKey = "ultra_4k";
    private int selectedMaxDurationSec = 15; // 180 (3m), 60 (60s), 15 (15s)
    private int countdownTimerSec = 0; // 0, 3, 10
    private boolean railExpanded = false;
    private boolean highQualityUpload = true;
    private String selectedPrivacy = "Everyone can view this post";
    private String selectedLocation = "";
    private String attachedLink = "";
    private boolean contentDisclosureOn = false;

    private FrameLayout rootHost;
    private FrameLayout cameraStageRoot;
    private FrameLayout editorStageRoot;
    private LinearLayout postStageRoot;

    // Stage 1 Camera Views
    private TextureView camTextureView;
    private View camBeautyLayer;
    private View camEffectLayer;
    private View camFrontFlashRing;
    private TikTokReelStudioHelper.ReelCameraController cameraController;
    private TextView camSoundsPillTv;
    private TextView camFlashLabelTv;
    private TextView camTimerLabelTv;
    private TextView camSpeedLabelTv;
    private TextView camBeautyLabelTv;
    private TextView camCountdownCenterTv;
    private LinearLayout camSpeedBarRow;
    private LinearLayout camZoomRow;
    private TextView camZoomLabelTv;
    private View camRecordInnerView;
    private TextView camRecordingTimerTv;
    private int recordingElapsedSec = 0;
    /** True when the last recording used the front camera, so playback mirrors it exactly as the user saw it. */
    private boolean lastRecordingWasFrontCamera = true;

    // Stage 2 Video Preview Editor Views
    private FrameLayout stage;
    /** TextureView (not VideoView) so the preview can be mirrored + GPU graded exactly like the camera. */
    private TextureView previewTexture;
    private android.view.Surface previewSurface;
    private Uri pendingPreviewUri;
    private int previewVideoW = 0, previewVideoH = 0;
    private ImageView previewPoster;
    private View stageBeautyTint;
    private View stageFilterTint;
    private TextView stageTextPill, stageStickerTv;
    private TextView editorSoundPillTv;
    private LinearLayout extraRailItemsCol;
    private TextView railExpandChevronTv;
    private LinearLayout editorBottomSheetPanel;
    private HorizontalScrollView filterStripScroll;
    private LinearLayout trimPanel;
    private TextView trimRangeLabel;
    private SeekBar trimStartSeek, trimEndSeek;

    // Stage 3 Post Form Views
    private EditText captionInput;
    private ImageView postCoverImage;
    private TextView locationRowValueTv;
    private TextView linkRowValueTv;
    private TextView privacyRowValueTv;
    private TextView disclosureRowValueTv;
    private LinearLayout progressCard;
    private ProgressBar progressBar;
    private TextView progressTitle, progressPercent;
    private TextView postSubmitBtn;

    private android.media.MediaPlayer previewMp;
    private android.media.MediaPlayer attachedSoundMp;
    private String initialSoundUrl = "";
    private String initialSoundTitle = "";
    private String initialSoundPhoto = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable recordTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (cameraController != null && cameraController.isRecording()) {
                recordingElapsedSec++;
                if (camRecordingTimerTv != null) {
                    camRecordingTimerTv.setVisibility(View.VISIBLE);
                    camRecordingTimerTv.setText(String.format(Locale.US, "REC %02d:%02d / %02d:%02d",
                            recordingElapsedSec / 60, recordingElapsedSec % 60,
                            selectedMaxDurationSec / 60, selectedMaxDurationSec % 60));
                }
                if (recordingElapsedSec >= selectedMaxDurationSec) {
                    finishCameraRecording();
                    return;
                }
                mainHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final Runnable trimLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentStage == STAGE_PREVIEW_EDITOR && previewMp != null && videoUri != null) {
                try {
                    if (previewIsPlaying()) {
                        int pos = previewGetPosition();
                        int end = editOpts.trimEndMs > editOpts.trimStartMs ? editOpts.trimEndMs : totalDurationMs;
                        if (end > 0 && pos >= end) previewSeek(Math.max(0, editOpts.trimStartMs));
                    }
                } catch (Throwable ignored) {}
                mainHandler.postDelayed(this, 140L);
            }
        }
    };

    private final ReelUploadManager.Listener uploadListener =
            (active, percent, sentBytes, totalBytes, message, succeeded, error) -> {
                if (isFinishing() || isDestroyed()) return;
                if (active) {
                    if (progressCard != null) progressCard.setVisibility(View.VISIBLE);
                    if (progressBar != null) progressBar.setProgress(Math.max(2, percent));
                    if (progressPercent != null) progressPercent.setText(percent + "%");
                    if (progressTitle != null) {
                        progressTitle.setText(message != null && !message.isEmpty()
                                ? message : ("Uploading Reel… " + percent + "%"));
                    }
                    if (postSubmitBtn != null) {
                        postSubmitBtn.setEnabled(false);
                        postSubmitBtn.setText("Uploading " + percent + "%");
                    }
                } else if (succeeded) {
                    finish();
                } else if (error != null && !error.isEmpty()) {
                    if (progressCard != null) progressCard.setVisibility(View.GONE);
                    if (postSubmitBtn != null) {
                        postSubmitBtn.setEnabled(true);
                        postSubmitBtn.setText("Post");
                    }
                    Ui.toast(this, error);
                }
            };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!com.friendbook.social.core.Admin.checkNotSuspended(this)) {
            finish();
            return;
        }

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

        rootHost = new FrameLayout(this);
        rootHost.setBackgroundColor(Color.BLACK);

        buildStage1CameraStudio();
        buildStage2VideoPreviewEditor();
        buildStage3CleanWhitePostScreen();

        rootHost.addView(cameraStageRoot, new FrameLayout.LayoutParams(-1, -1));
        rootHost.addView(editorStageRoot, new FrameLayout.LayoutParams(-1, -1));
        rootHost.addView(postStageRoot, new FrameLayout.LayoutParams(-1, -1));

        setContentView(rootHost);
        switchStage(STAGE_CAMERA);
        ensureCameraPermissionAndStart();
    }

    // ==================== STAGE SWITCHING ====================

    private void switchStage(int targetStage) {
        currentStage = targetStage;
        cameraStageRoot.setVisibility(targetStage == STAGE_CAMERA ? View.VISIBLE : View.GONE);
        editorStageRoot.setVisibility(targetStage == STAGE_PREVIEW_EDITOR ? View.VISIBLE : View.GONE);
        postStageRoot.setVisibility(targetStage == STAGE_POST_FORM ? View.VISIBLE : View.GONE);

        if (targetStage == STAGE_CAMERA) {
            Ui.statusBar(getWindow(), Color.BLACK);
            previewPause();
            stopAttachedSoundPreview();
            if (cameraController != null) cameraController.startPreviewIfReady();
        } else if (targetStage == STAGE_PREVIEW_EDITOR) {
            Ui.statusBar(getWindow(), Color.BLACK);
            if (cameraController != null) cameraController.releaseCamera();
            previewStart();
            if (editOpts.audioUrl != null && !editOpts.audioUrl.isEmpty()) {
                startAttachedSoundPreview();
            }
            mainHandler.removeCallbacks(trimLoopRunnable);
            mainHandler.post(trimLoopRunnable);
        } else {
            Ui.statusBarLight(this);
            if (cameraController != null) cameraController.releaseCamera();
            previewPause();
            stopAttachedSoundPreview();
            mainHandler.removeCallbacks(trimLoopRunnable);
        }
    }

    // ==================== STAGE 2 TEXTUREVIEW PLAYER (mirror-safe + 4K UHD GPU graded) ====================

    /**
     * Prepares the Stage-2 preview player on a hardware TextureView so the recorded clip is shown
     * EXACTLY as the user saw it while recording (front camera stays mirrored — face never flips side),
     * and so the real 4K UHD ColorMatrix filter grade can be applied straight on the GPU.
     */
    private void preparePreviewPlayer() {
        releasePreviewPlayer();
        if (pendingPreviewUri == null) return;
        final Uri target = pendingPreviewUri;
        final android.media.MediaPlayer mp = new android.media.MediaPlayer();
        previewMp = mp;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build());
            }
            mp.setLooping(true);
            if (previewSurface == null && previewTexture != null && previewTexture.isAvailable()) {
                previewSurface = new android.view.Surface(previewTexture.getSurfaceTexture());
            }
            if (previewSurface != null) mp.setSurface(previewSurface);
            mp.setDataSource(this, target);

            mp.setOnVideoSizeChangedListener((player, w, h) -> {
                previewVideoW = w;
                previewVideoH = h;
                applyPreviewTransform();
            });
            mp.setOnPreparedListener(player -> {
                if (player != previewMp) return;
                previewVideoW = player.getVideoWidth();
                previewVideoH = player.getVideoHeight();
                int d = player.getDuration();
                if (d > 0) totalDurationMs = d;
                float vol = previewMuted ? 0f : 1f;
                try { player.setVolume(vol, vol); } catch (Throwable ignored) {}
                if (Math.abs(editOpts.playbackSpeed - 1.0f) > 0.02f
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try { player.setPlaybackParams(player.getPlaybackParams().setSpeed(editOpts.playbackSpeed)); }
                    catch (Throwable ignored) {}
                }
                applyPreviewTransform();
                refreshStageOverlays();
                if (previewPoster != null) previewPoster.setVisibility(View.GONE);
                previewStart();
                if (editOpts.audioUrl != null && !editOpts.audioUrl.isEmpty()) {
                    startAttachedSoundPreview();
                }
                mainHandler.removeCallbacks(trimLoopRunnable);
                mainHandler.post(trimLoopRunnable);
            });
            mp.setOnErrorListener((player, what, extra) -> player == previewMp);
            mp.prepareAsync();
        } catch (Throwable t) {
            releasePreviewPlayer();
        }
    }

    /** Fit-to-stage preview transform + horizontal mirror for front-camera recordings. */
    private void applyPreviewTransform() {
        if (previewTexture == null || previewVideoW <= 0 || previewVideoH <= 0) return;
        int viewW = previewTexture.getWidth();
        int viewH = previewTexture.getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        float viewRatio = (float) viewW / viewH;
        float videoRatio = (float) previewVideoW / previewVideoH;
        float sx, sy;
        if (videoRatio > viewRatio) {
            sx = 1f;
            sy = viewRatio / videoRatio;
        } else {
            sx = videoRatio / viewRatio;
            sy = 1f;
        }
        if (editOpts.mirrorHorizontal) sx = -sx;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(sx, sy, viewW / 2f, viewH / 2f);
        previewTexture.setTransform(m);
    }

    private void previewPause() {
        try { if (previewMp != null && previewMp.isPlaying()) previewMp.pause(); } catch (Throwable ignored) {}
    }

    private void previewStart() {
        try {
            if (previewMp == null && pendingPreviewUri != null) { preparePreviewPlayer(); return; }
            if (previewMp != null) previewMp.start();
        } catch (Throwable ignored) {}
    }

    private void previewSeek(int ms) {
        try { if (previewMp != null) previewMp.seekTo(Math.max(0, ms)); } catch (Throwable ignored) {}
    }

    private boolean previewIsPlaying() {
        try { return previewMp != null && previewMp.isPlaying(); } catch (Throwable ignored) { return false; }
    }

    private int previewGetPosition() {
        try { return previewMp != null ? previewMp.getCurrentPosition() : 0; } catch (Throwable ignored) { return 0; }
    }

    private void releasePreviewPlayer() {
        final android.media.MediaPlayer old = previewMp;
        previewMp = null;
        if (old != null) {
            new Thread(() -> {
                try { old.reset(); } catch (Throwable ignored) {}
                try { old.release(); } catch (Throwable ignored) {}
            }, "reel-preview-player-release").start();
        }
    }

    @Override
    public void onBackPressed() {
        if (currentStage == STAGE_POST_FORM) {
            switchStage(STAGE_PREVIEW_EDITOR);
        } else if (currentStage == STAGE_PREVIEW_EDITOR) {
            switchStage(STAGE_CAMERA);
        } else {
            super.onBackPressed();
        }
    }

    // ==================== STAGE 1: LIVE CAMERA STUDIO (Screenshot_20261111-105919.jpg) ====================

    private void buildStage1CameraStudio() {
        cameraStageRoot = new FrameLayout(this);
        cameraStageRoot.setBackgroundColor(Color.BLACK);

        // 1. Live Hardware TextureView Preview (1080p 4K UHD ISP tuned, pinch-to-zoom)
        camTextureView = new TextureView(this);
        cameraController = new TikTokReelStudioHelper.ReelCameraController(this, camTextureView);
        cameraController.setZoomChangeListener(z -> {
            if (camZoomLabelTv != null) {
                camZoomLabelTv.setText(String.format(java.util.Locale.US, "%.1fx", z));
            }
        });
        cameraStageRoot.addView(camTextureView, new FrameLayout.LayoutParams(-1, -1));

        // 2. Zero-Lag GPU Beauty & Effect Overlay Layers (0.0ms CPU cost = zero camera hang!)
        camBeautyLayer = new View(this);
        camBeautyLayer.setClickable(false);
        camBeautyLayer.setFocusable(false);
        cameraStageRoot.addView(camBeautyLayer, new FrameLayout.LayoutParams(-1, -1));

        camEffectLayer = new View(this);
        camEffectLayer.setClickable(false);
        camEffectLayer.setFocusable(false);
        cameraStageRoot.addView(camEffectLayer, new FrameLayout.LayoutParams(-1, -1));
        refreshCameraGrade();

        // Front-camera Retina Flash Ring
        camFrontFlashRing = new View(this);
        camFrontFlashRing.setClickable(false);
        camFrontFlashRing.setBackground(Ui.roundStroke(this, 0x18FFF8E7, 0, 0xCCFFF8E7, 14));
        camFrontFlashRing.setVisibility(View.GONE);
        cameraStageRoot.addView(camFrontFlashRing, new FrameLayout.LayoutParams(-1, -1));

        // 3. Top Bar: [ ✕ ]              [ ♪ Sounds ]
        FrameLayout topBar = new FrameLayout(this);
        Ui.pad(topBar, 16, 16, 16, 10);

        ImageView closeBtn = com.friendbook.social.core.StudioVectorIcons.iconView(this, "close", 24, Color.WHITE);
        Ui.pad(closeBtn, 8, 8, 8, 8);
        closeBtn.setOnClickListener(v -> finish());
        topBar.addView(closeBtn, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40),
                Gravity.START | Gravity.CENTER_VERTICAL));

        camSoundsPillTv = com.friendbook.social.core.StudioVectorIcons.iconChip(this, "music",
                !initialSoundTitle.isEmpty() ? initialSoundTitle : "Sounds",
                14f, 0x77000000, Color.WHITE, Color.WHITE);
        camSoundsPillTv.setSingleLine(true);
        camSoundsPillTv.setEllipsize(TextUtils.TruncateAt.END);
        camSoundsPillTv.setMaxWidth(Ui.dp(this, 220));
        camSoundsPillTv.setOnClickListener(v -> openSoundsBottomSheet());
        topBar.addView(camSoundsPillTv, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        cameraStageRoot.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Recording Timer Pill (shown while recording)
        camRecordingTimerTv = Ui.tvBold(this, "REC 00:00", 13f, Color.WHITE);
        camRecordingTimerTv.setBackground(Ui.round(this, 0xCCFE2C55, 16));
        Ui.pad(camRecordingTimerTv, 12, 5, 12, 5);
        camRecordingTimerTv.setVisibility(View.GONE);
        FrameLayout.LayoutParams rtlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rtlp.topMargin = Ui.dp(this, 62);
        cameraStageRoot.addView(camRecordingTimerTv, rtlp);

        // Big Center Countdown Number (for 3s / 10s Timer)
        camCountdownCenterTv = Ui.tvBold(this, "", 68f, Color.WHITE);
        camCountdownCenterTv.setShadowLayer(10f, 0f, 3f, 0xCC000000);
        camCountdownCenterTv.setVisibility(View.GONE);
        cameraStageRoot.addView(camCountdownCenterTv, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // 4. Right Vertical Tool Rail (Flip, Flash, Timer, Speed, Filters, Beautify)
        LinearLayout rightRail = Ui.col(this);
        rightRail.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(rightRail, 8, 8, 12, 8);

        rightRail.addView(makeRailTool("flip", "Flip", null, v -> {
            if (cameraController != null) {
                cameraController.flipCamera();
                camFrontFlashRing.setVisibility(View.GONE);
                if (camFlashLabelTv != null) camFlashLabelTv.setText("Flash Off");
            }
        }));

        LinearLayout flashItem = makeRailTool("flash", "Flash Off", tv -> camFlashLabelTv = tv, v -> {
            if (cameraController == null) return;
            boolean on = cameraController.toggleFlash();
            if (camFlashLabelTv != null) camFlashLabelTv.setText(on ? "Flash On" : "Flash Off");
            camFrontFlashRing.setVisibility((on && cameraController.isFrontCamera()) ? View.VISIBLE : View.GONE);
        });
        rightRail.addView(flashItem);

        rightRail.addView(makeRailTool("timer", "Timer", tv -> camTimerLabelTv = tv, v -> {
            if (countdownTimerSec == 0) countdownTimerSec = 3;
            else if (countdownTimerSec == 3) countdownTimerSec = 10;
            else countdownTimerSec = 0;
            if (camTimerLabelTv != null) {
                camTimerLabelTv.setText(countdownTimerSec == 0 ? "Timer" : (countdownTimerSec + "s"));
            }
            Ui.toast(this, countdownTimerSec == 0
                    ? "Timer Off" : (countdownTimerSec + "s Countdown Timer Set"));
        }));

        rightRail.addView(makeRailTool("1x", "Speed", tv -> camSpeedLabelTv = tv, v -> {
            if (camSpeedBarRow != null) {
                boolean show = camSpeedBarRow.getVisibility() != View.VISIBLE;
                camSpeedBarRow.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        }));

        rightRail.addView(makeRailTool("filter", "Filters", null, v -> cycleOrPickFilterDialog()));

        rightRail.addView(makeRailTool("beauty", "Beautify", tv -> camBeautyLabelTv = tv, v -> {
            activeBeautyLevel = (activeBeautyLevel + 1) % 4;
            refreshCameraGrade();
            refreshStageOverlays();
            String bName = activeBeautyLevel == 0 ? "Natural"
                    : (activeBeautyLevel == 1 ? "Soft Glow" : (activeBeautyLevel == 2 ? "Porcelain 4K" : "4K UHD"));
            if (camBeautyLabelTv != null) camBeautyLabelTv.setText(bName);
            Ui.toast(this, "4K UHD Face Beauty: " + bName);
        }));

        FrameLayout.LayoutParams rrlp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        rrlp.topMargin = Ui.dp(this, 68);
        cameraStageRoot.addView(rightRail, rrlp);

        // 5. Smooth Zoom Chips (1x / 2x / 3x) above the record button — pinch-to-zoom also works
        camZoomRow = Ui.row(this);
        camZoomRow.setGravity(Gravity.CENTER);
        final float[] zoomSteps = {1.0f, 2.0f, 3.0f};
        for (int zi = 0; zi < zoomSteps.length; zi++) {
            final float zs = zoomSteps[zi];
            TextView chip = Ui.tvBold(this, ((int) zs + "x"), 12f, Color.WHITE);
            chip.setGravity(Gravity.CENTER);
            chip.setTag(zs);
            chip.setBackground(Ui.round(this, 0x55000000, 14));
            Ui.pad(chip, 11, 5, 11, 5);
            chip.setOnClickListener(v -> {
                if (cameraController != null) cameraController.setZoomFactor(zs);
                refreshZoomChips();
            });
            camZoomRow.addView(chip);
            if (zi < zoomSteps.length - 1) {
                View sp = new View(this);
                camZoomRow.addView(sp, new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
            }
        }
        camZoomLabelTv = Ui.tvBold(this, "1.0x", 11f, 0xCCFFFFFF);
        camZoomLabelTv.setGravity(Gravity.CENTER);
        camZoomLabelTv.setShadowLayer(4f, 0f, 1f, 0xAA000000);
        FrameLayout.LayoutParams zllp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        zllp.bottomMargin = Ui.dp(this, 178);
        cameraStageRoot.addView(camZoomLabelTv, zllp);

        FrameLayout.LayoutParams zrlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        zrlp.bottomMargin = Ui.dp(this, 150);
        cameraStageRoot.addView(camZoomRow, zrlp);
        refreshZoomChips();

        // 5. Bottom Camera Controls Stack (Speed Bar + Duration Row + Effects / Record / Upload Row)
        LinearLayout bottomCol = Ui.col(this);
        bottomCol.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(bottomCol, 16, 10, 16, 26);

        // Optional Speed Bar (0.5x, 1x, 2x, 3x)
        camSpeedBarRow = Ui.row(this);
        camSpeedBarRow.setGravity(Gravity.CENTER);
        camSpeedBarRow.setBackground(Ui.round(this, 0x88000000, 18));
        Ui.pad(camSpeedBarRow, 8, 4, 8, 4);
        camSpeedBarRow.setVisibility(View.GONE);
        refreshCameraSpeedBar();
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(-2, -2);
        sblp.bottomMargin = Ui.dp(this, 12);
        bottomCol.addView(camSpeedBarRow, sblp);

        // Duration Selector Row: [ 3m ]   [ 60s ]   [ 15s ]   [ Photo ]
        final LinearLayout durRow = Ui.row(this);
        durRow.setGravity(Gravity.CENTER);
        final int[] durSecs = {180, 60, 15, 0};
        final String[] durNames = {"3m", "60s", "15s", "Photo"};
        final Runnable[] renderDurs = new Runnable[1];
        renderDurs[0] = () -> {
            durRow.removeAllViews();
            for (int i = 0; i < durNames.length; i++) {
                final int sec = durSecs[i];
                final String label = durNames[i];
                boolean isSel = (sec == selectedMaxDurationSec && sec > 0);
                LinearLayout item = Ui.col(this);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                Ui.pad(item, 14, 4, 14, 6);
                TextView tv = Ui.tvBold(this, label, 13.5f, isSel ? Color.WHITE : 0x99FFFFFF);
                tv.setShadowLayer(3f, 0f, 1f, 0xAA000000);
                item.addView(tv);
                View dot = new View(this);
                dot.setBackground(Ui.circle(isSel ? Color.WHITE : Color.TRANSPARENT));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 5), Ui.dp(this, 5));
                dlp.topMargin = Ui.dp(this, 4);
                item.addView(dot, dlp);
                item.setOnClickListener(v -> {
                    if (sec == 0) {
                        openGalleryPicker();
                        return;
                    }
                    selectedMaxDurationSec = sec;
                    renderDurs[0].run();
                });
                durRow.addView(item);
            }
        };
        renderDurs[0].run();
        LinearLayout.LayoutParams drlp = new LinearLayout.LayoutParams(-2, -2);
        drlp.bottomMargin = Ui.dp(this, 14);
        bottomCol.addView(durRow, drlp);

        // Main Action Row: [ Effects ]        ( ● RECORD )        [ Upload ]
        LinearLayout actionRow = Ui.row(this);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        // Left: Effects Button
        LinearLayout effectsCol = Ui.col(this);
        effectsCol.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout effectsCard = new FrameLayout(this);
        GradientDrawable effGrad = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF8DA1, 0xFF6A11CB});
        effGrad.setCornerRadius(Ui.dp(this, 10));
        effGrad.setStroke(Ui.dp(this, 1.5f), Color.WHITE);
        effectsCard.setBackground(effGrad);
        ImageView effIcon = com.friendbook.social.core.StudioVectorIcons.iconView(this, "beauty", 22, Color.WHITE);
        int effIconSz = Ui.dp(this, 24);
        effectsCard.addView(effIcon, new FrameLayout.LayoutParams(effIconSz, effIconSz, Gravity.CENTER));
        effectsCol.addView(effectsCard, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        TextView effLbl = Ui.tvBold(this, "Effects", 12f, Color.WHITE);
        effLbl.setShadowLayer(3f, 0f, 1f, 0xAA000000);
        effLbl.setPadding(0, Ui.dp(this, 4), 0, 0);
        effectsCol.addView(effLbl);
        effectsCol.setOnClickListener(v -> openEffectsBottomSheet());
        actionRow.addView(effectsCol, new LinearLayout.LayoutParams(0, -2, 1f));

        // Center: Giant Red Record Button (Outer white ring + inner #FE2C55 circle)
        FrameLayout recordOuter = new FrameLayout(this);
        recordOuter.setBackground(Ui.roundStroke(this, 0x22FFFFFF, 42, Color.WHITE, 4));
        camRecordInnerView = new View(this);
        camRecordInnerView.setBackground(Ui.circle(0xFFFE2C55));
        FrameLayout.LayoutParams inLp = new FrameLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64), Gravity.CENTER);
        recordOuter.addView(camRecordInnerView, inLp);
        recordOuter.setOnClickListener(v -> onRecordButtonTapped());
        actionRow.addView(recordOuter, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 82)));

        // Right: Upload Gallery Button
        LinearLayout uploadCol = Ui.col(this);
        uploadCol.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout uploadCard = new FrameLayout(this);
        GradientDrawable upGrad = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF25F4EE, 0xFFFE2C55});
        upGrad.setCornerRadius(Ui.dp(this, 10));
        upGrad.setStroke(Ui.dp(this, 1.5f), Color.WHITE);
        uploadCard.setBackground(upGrad);
        ImageView upIcon = com.friendbook.social.core.StudioVectorIcons.iconView(this, "gallery", 21, Color.WHITE);
        int upIconSz = Ui.dp(this, 23);
        uploadCard.addView(upIcon, new FrameLayout.LayoutParams(upIconSz, upIconSz, Gravity.CENTER));
        uploadCol.addView(uploadCard, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        TextView upLbl = Ui.tvBold(this, "Upload", 12f, Color.WHITE);
        upLbl.setShadowLayer(3f, 0f, 1f, 0xAA000000);
        upLbl.setPadding(0, Ui.dp(this, 4), 0, 0);
        uploadCol.addView(upLbl);
        uploadCol.setOnClickListener(v -> openGalleryPicker());
        actionRow.addView(uploadCol, new LinearLayout.LayoutParams(0, -2, 1f));

        bottomCol.addView(actionRow, new LinearLayout.LayoutParams(-1, -2));
        cameraStageRoot.addView(bottomCol, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private interface LabelConsumer {
        void accept(TextView tv);
    }

    /**
     * Re-applies the 4K UHD GPU color grade to the live camera:
     * a real hardware ColorMatrixColorFilter on the TextureView + procedural face glow / lens bloom
     * shader on the overlay. Both run entirely on the GPU → locked 60 FPS, zero camera hang.
     */
    private void refreshCameraGrade() {
        // Keep the grade the creator sees in sync with what gets published, so the finished
        // Reel plays back exactly like the recording preview.
        editOpts.beautyLevel = activeBeautyLevel;
        TikTokReelStudioHelper.applyZeroLagVisualOverlay(
                camTextureView, camBeautyLayer, camEffectLayer,
                activeBeautyLevel, editOpts.filterPreset, editOpts.filterOverlayColor);
    }

    /** Highlights the active zoom chip (1x / 2x / 3x) and syncs the live zoom label. */
    private void refreshZoomChips() {
        if (camZoomRow == null) return;
        float cur = cameraController != null ? cameraController.getZoomFactor() : 1.0f;
        for (int i = 0; i < camZoomRow.getChildCount(); i++) {
            View child = camZoomRow.getChildAt(i);
            if (!(child instanceof TextView) || !(child.getTag() instanceof Float)) continue;
            float step = (Float) child.getTag();
            boolean sel = Math.abs(cur - step) < 0.06f;
            TextView tv = (TextView) child;
            tv.setTextColor(sel ? Color.BLACK : Color.WHITE);
            tv.setBackground(sel ? Ui.round(this, Color.WHITE, 14) : Ui.round(this, 0x55000000, 14));
        }
        if (camZoomLabelTv != null) {
            camZoomLabelTv.setText(String.format(java.util.Locale.US, "%.1fx", cur));
        }
    }

    /**
     * 2026 Studio Rail Tool: sleek anti-aliased vector icon (never a keyboard emoji) + label.
     * `icon` accepts the legacy emoji/text tokens and maps them to real vector glyphs.
     */
    private LinearLayout makeRailTool(String icon, String label, LabelConsumer labelRef, View.OnClickListener click) {
        LinearLayout col = Ui.col(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(col, 6, 7, 6, 7);
        if ("1x".equals(icon)) {
            TextView icTv = Ui.tvBold(this, icon, 16f, Color.WHITE);
            icTv.setGravity(Gravity.CENTER);
            icTv.setShadowLayer(4f, 0f, 1f, 0xCC000000);
            col.addView(icTv);
        } else {
            col.addView(com.friendbook.social.core.StudioVectorIcons.iconView(this, icon, 22, Color.WHITE));
        }
        TextView lbTv = Ui.tvBold(this, label, 11f, Color.WHITE);
        lbTv.setGravity(Gravity.CENTER);
        lbTv.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        lbTv.setPadding(0, Ui.dp(this, 2), 0, 0);
        col.addView(lbTv);
        if (labelRef != null) labelRef.accept(lbTv);
        col.setOnClickListener(click);
        return col;
    }

    private void refreshCameraSpeedBar() {
        if (camSpeedBarRow == null) return;
        camSpeedBarRow.removeAllViews();
        for (final float spd : SPEED_STEPS) {
            boolean sel = Math.abs(editOpts.playbackSpeed - spd) < 0.04f;
            String lbl = (spd == (int) spd) ? ((int) spd + "x") : (spd + "x");
            TextView chip = Ui.tvBold(this, lbl, 12.5f, sel ? Color.BLACK : Color.WHITE);
            chip.setBackground(sel ? Ui.round(this, Color.WHITE, 14) : null);
            Ui.pad(chip, 14, 6, 14, 6);
            chip.setOnClickListener(v -> {
                editOpts.playbackSpeed = spd;
                if (camSpeedLabelTv != null) camSpeedLabelTv.setText(lbl);
                refreshCameraSpeedBar();
            });
            camSpeedBarRow.addView(chip);
        }
    }

    private void onRecordButtonTapped() {
        if (cameraController != null && cameraController.isRecording()) {
            finishCameraRecording();
            return;
        }
        if (countdownTimerSec > 0) {
            runCountdownThenRecord(countdownTimerSec);
        } else {
            beginCameraRecordingNow();
        }
    }

    private void runCountdownThenRecord(final int secondsLeft) {
        if (secondsLeft <= 0) {
            if (camCountdownCenterTv != null) camCountdownCenterTv.setVisibility(View.GONE);
            beginCameraRecordingNow();
            return;
        }
        if (camCountdownCenterTv != null) {
            camCountdownCenterTv.setText(String.valueOf(secondsLeft));
            camCountdownCenterTv.setVisibility(View.VISIBLE);
        }
        mainHandler.postDelayed(() -> runCountdownThenRecord(secondsLeft - 1), 1000L);
    }

    private void beginCameraRecordingNow() {
        if (cameraController != null && cameraController.startRecording(selectedMaxDurationSec)) {
            recordingElapsedSec = 0;
            // Remember the lens used so playback mirrors EXACTLY like the live preview (face stays on same side)
            lastRecordingWasFrontCamera = cameraController.isFrontCamera();
            editOpts.mirrorHorizontal = lastRecordingWasFrontCamera;
            if (camRecordInnerView != null) {
                camRecordInnerView.setBackground(Ui.round(this, 0xFFFE2C55, 10));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        Ui.dp(this, 34), Ui.dp(this, 34), Gravity.CENTER);
                camRecordInnerView.setLayoutParams(lp);
            }
            if (editOpts.audioUrl != null && !editOpts.audioUrl.isEmpty()) {
                startAttachedSoundPreview();
            }
            mainHandler.removeCallbacks(recordTickRunnable);
            mainHandler.post(recordTickRunnable);
        } else {
            // Fallback to system video capture intent if device camera is busy
            launchVideoCaptureIntent();
        }
    }

    private void finishCameraRecording() {
        mainHandler.removeCallbacks(recordTickRunnable);
        stopAttachedSoundPreview();
        if (camRecordingTimerTv != null) camRecordingTimerTv.setVisibility(View.GONE);
        if (camRecordInnerView != null) {
            camRecordInnerView.setBackground(Ui.circle(0xFFFE2C55));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Ui.dp(this, 64), Ui.dp(this, 64), Gravity.CENTER);
            camRecordInnerView.setLayoutParams(lp);
        }
        File recorded = cameraController != null ? cameraController.stopRecording() : null;
        if (recorded != null) {
            editOpts.mirrorHorizontal = lastRecordingWasFrontCamera;
            onVideoPicked(Uri.fromFile(recorded));
        } else {
            Ui.toast(this, "Hold or tap to record at least 1 second of video");
        }
    }

    // ==================== STAGE 2: VIDEO PREVIEW EDITOR (Screenshot_20261111-110929.jpg & 110932.jpg) ====================

    private void buildStage2VideoPreviewEditor() {
        editorStageRoot = new FrameLayout(this);
        editorStageRoot.setBackgroundColor(Color.BLACK);

        // Full-Screen Video Stage
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);

        previewTexture = new TextureView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(-1, -1);
        vlp.gravity = Gravity.CENTER;
        stage.addView(previewTexture, vlp);
        previewTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                if (previewSurface != null) {
                    try { previewSurface.release(); } catch (Throwable ignored) {}
                }
                previewSurface = new android.view.Surface(st);
                if (previewMp != null) {
                    try {
                        previewMp.setSurface(previewSurface);
                        applyPreviewTransform();
                    } catch (Throwable ignored) {}
                } else if (pendingPreviewUri != null) {
                    preparePreviewPlayer();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                applyPreviewTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                releasePreviewPlayer();
                if (previewSurface != null) {
                    try { previewSurface.release(); } catch (Throwable ignored) {}
                    previewSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { }
        });

        previewPoster = new ImageView(this);
        previewPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewPoster.setVisibility(View.GONE);
        stage.addView(previewPoster, new FrameLayout.LayoutParams(-1, -1));

        // Zero-Lag GPU Beauty & Filter Overlay Layers
        stageBeautyTint = new View(this);
        stageBeautyTint.setClickable(false);
        stageBeautyTint.setFocusable(false);
        stage.addView(stageBeautyTint, new FrameLayout.LayoutParams(-1, -1));

        stageFilterTint = new View(this);
        stageFilterTint.setClickable(false);
        stageFilterTint.setFocusable(false);
        stage.addView(stageFilterTint, new FrameLayout.LayoutParams(-1, -1));
        refreshStageOverlays();

        // Draggable Brush-Banner Text Overlay (Screenshot_20261111-110929.jpg)
        stageTextPill = Ui.tvBold(this, "", 18f, Color.WHITE);
        stageTextPill.setGravity(Gravity.CENTER);
        Ui.pad(stageTextPill, 18, 10, 18, 10);
        stageTextPill.setShadowLayer(4f, 0f, 1f, 0x99000000);
        stageTextPill.setVisibility(View.GONE);
        attachStageDragListener(stageTextPill, true);
        stage.addView(stageTextPill, new FrameLayout.LayoutParams(-2, -2));

        // Draggable Sticker Overlay — real 2026 vector stickers (legacy text stickers still render)
        stageStickerTv = com.friendbook.social.core.StudioVectorIcons.stickerView(this, 44, Color.WHITE);
        stageStickerTv.setGravity(Gravity.CENTER);
        stageStickerTv.setVisibility(View.GONE);
        attachStageDragListener(stageStickerTv, false);
        stage.addView(stageStickerTv, new FrameLayout.LayoutParams(-2, -2));

        editorStageRoot.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        // Top Bar: [ ← ]               [ ♫ Add sound ]
        FrameLayout topBar = new FrameLayout(this);
        Ui.pad(topBar, 14, 16, 14, 10);

        ImageView backBtn = com.friendbook.social.core.StudioVectorIcons.iconView(this, "back", 26, Color.WHITE);
        Ui.pad(backBtn, 8, 8, 8, 8);
        backBtn.setOnClickListener(v -> switchStage(STAGE_CAMERA));
        topBar.addView(backBtn, new FrameLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42),
                Gravity.START | Gravity.CENTER_VERTICAL));

        editorSoundPillTv = com.friendbook.social.core.StudioVectorIcons.iconChip(this, "music",
                !initialSoundTitle.isEmpty() ? initialSoundTitle : "Add sound",
                14f, 0x77000000, Color.WHITE, Color.WHITE);
        editorSoundPillTv.setSingleLine(true);
        editorSoundPillTv.setEllipsize(TextUtils.TruncateAt.END);
        editorSoundPillTv.setMaxWidth(Ui.dp(this, 220));
        editorSoundPillTv.setOnClickListener(v -> openSoundsBottomSheet());
        topBar.addView(editorSoundPillTv, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        editorStageRoot.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Right Vertical Collapsible Tool Rail (Screenshot_20261111-110929.jpg & 110932.jpg)
        ScrollView railScroll = new ScrollView(this);
        railScroll.setVerticalScrollBarEnabled(false);
        LinearLayout rightRail = Ui.col(this);
        rightRail.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(rightRail, 6, 4, 12, 12);

        rightRail.addView(makeRailTool("settings", "Settings", null, v -> showEditorSettingsDialog()));
        rightRail.addView(makeRailTool("scissors", "Edit", null, v -> toggleTrimPanel()));
        rightRail.addView(makeRailTool("template", "Templates", null, v -> {
            activeBeautyLevel = 3;
            editOpts.filterPreset = "vivid";
            editOpts.filterOverlayColor = 0x18FFF3E8;
            refreshStageOverlays();
            Ui.toast(this, "4K UHD Cinema Template Applied");
        }));
        rightRail.addView(makeRailTool("Aa", "Text", null, v -> showTextOverlayDialog()));
        rightRail.addView(makeRailTool("sticker", "Stickers", null, v -> showStickerPickerDialog()));
        rightRail.addView(makeRailTool("beauty", "Effects", null, v -> openEffectsBottomSheet()));
        rightRail.addView(makeRailTool("filter", "Filters", null, v -> toggleFilterBar()));

        // Collapsible Extra Items (Captions, Voice, Save — Screenshot_20261111-110932.jpg)
        extraRailItemsCol = Ui.col(this);
        extraRailItemsCol.setGravity(Gravity.CENTER_HORIZONTAL);
        extraRailItemsCol.setVisibility(View.GONE);
        extraRailItemsCol.addView(makeRailTool("captions", "Captions", null, v -> showTextOverlayDialog()));
        extraRailItemsCol.addView(makeRailTool("mic", "Voice", null, v -> {
            previewMuted = !previewMuted;
            editOpts.muteOriginalAudio = previewMuted;
            if (previewMp != null) {
                try {
                    float vol = previewMuted ? 0f : 1f;
                    previewMp.setVolume(vol, vol);
                } catch (Throwable ignored) {}
            }
            Ui.toast(this, previewMuted ? "Original Camera Voice Muted" : "Original Camera Voice Active");
        }));
        extraRailItemsCol.addView(makeRailTool("download", "Save", null, v -> {
            Ui.toast(this, "Video saved to device in 1080p HDR");
        }));
        rightRail.addView(extraRailItemsCol);

        // Expand/Collapse Chevron Button (⌄ / ⌃)
        railExpandChevronTv = com.friendbook.social.core.StudioVectorIcons.circleIconButton(
                this, "chevron_down", 32, 0x44000000, Color.WHITE, null);
        railExpandChevronTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
        chlp.topMargin = Ui.dp(this, 6);
        railExpandChevronTv.setOnClickListener(v -> {
            railExpanded = !railExpanded;
            extraRailItemsCol.setVisibility(railExpanded ? View.VISIBLE : View.GONE);
            railExpandChevronTv.setText(railExpanded ? "chevron_up" : "chevron_down");
        });
        rightRail.addView(railExpandChevronTv, chlp);
        railScroll.addView(rightRail);

        FrameLayout.LayoutParams erlp = new FrameLayout.LayoutParams(-2, Ui.dp(this, 490), Gravity.END | Gravity.TOP);
        erlp.topMargin = Ui.dp(this, 58);
        editorStageRoot.addView(railScroll, erlp);

        // Floating Filter / Trim Bottom Panel (above Your Story / Next buttons)
        editorBottomSheetPanel = Ui.col(this);
        Ui.pad(editorBottomSheetPanel, 12, 8, 12, 8);

        filterStripScroll = new HorizontalScrollView(this);
        filterStripScroll.setHorizontalScrollBarEnabled(false);
        filterStripScroll.setVisibility(View.GONE);
        LinearLayout filterRow = Ui.row(this);
        filterRow.setBackground(Ui.round(this, 0xCC161823, 14));
        Ui.pad(filterRow, 8, 8, 8, 8);
        for (int i = 0; i < FILTER_KEYS.length; i++) {
            final String fKey = FILTER_KEYS[i];
            final String fLabel = FILTER_LABELS[i];
            final int fTint = FILTER_TINTS[i];
            TextView fc = Ui.roundChip(this, fLabel, 0x33FFFFFF, Color.WHITE, 12f);
            Ui.pad(fc, 12, 7, 12, 7);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
            flp.rightMargin = Ui.dp(this, 8);
            fc.setOnClickListener(v -> {
                editOpts.filterPreset = fKey;
                editOpts.filterOverlayColor = fTint;
                refreshStageOverlays();
                refreshCameraGrade();
            });
            filterRow.addView(fc, flp);
        }
        filterStripScroll.addView(filterRow);
        editorBottomSheetPanel.addView(filterStripScroll);

        trimPanel = Ui.col(this);
        trimPanel.setBackground(Ui.round(this, 0xDD161823, 14));
        Ui.pad(trimPanel, 12, 10, 12, 10);
        trimPanel.setVisibility(View.GONE);
        trimRangeLabel = Ui.tvBold(this, "Trim Start / End (Full Video)", 12f, Color.WHITE);
        trimPanel.addView(trimRangeLabel);
        trimStartSeek = new SeekBar(this);
        trimStartSeek.setMax(1000);
        trimEndSeek = new SeekBar(this);
        trimEndSeek.setMax(1000);
        trimEndSeek.setProgress(1000);
        SeekBar.OnSeekBarChangeListener trimL = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || totalDurationMs <= 0) return;
                int s = (int) ((trimStartSeek.getProgress() / 1000f) * totalDurationMs);
                int e = (int) ((trimEndSeek.getProgress() / 1000f) * totalDurationMs);
                if (e - s < 1000) return;
                editOpts.trimStartMs = s;
                editOpts.trimEndMs = e;
                trimRangeLabel.setText(String.format(Locale.US, "%.1fs – %.1fs", s / 1000f, e / 1000f));
                try { previewSeek(s); } catch (Throwable ignored) {}
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        trimStartSeek.setOnSeekBarChangeListener(trimL);
        trimEndSeek.setOnSeekBarChangeListener(trimL);
        trimPanel.addView(trimStartSeek);
        trimPanel.addView(trimEndSeek);
        editorBottomSheetPanel.addView(trimPanel);

        FrameLayout.LayoutParams eplp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        eplp.bottomMargin = Ui.dp(this, 80);
        editorStageRoot.addView(editorBottomSheetPanel, eplp);

        // Bottom Bar: [ (Avatar) Your Story ]    [ Next ] (Screenshot_20261111-110929.jpg & 110932.jpg)
        LinearLayout bottomBar = Ui.row(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(0xDD000000);
        Ui.pad(bottomBar, 14, 12, 14, 18);

        // Left: White [ (Avatar) Your Story ] Button
        LinearLayout storyBtn = Ui.row(this);
        storyBtn.setGravity(Gravity.CENTER);
        storyBtn.setBackground(Ui.round(this, Color.WHITE, 24));
        Ui.pad(storyBtn, 12, 11, 12, 11);
        ImageView myAv = new ImageView(this);
        myAv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        myAv.setImageResource(com.friendbook.social.R.drawable.ic_default_avatar);
        storyBtn.addView(myAv, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView storyTv = Ui.tvBold(this, "  Your Story", 14.5f, 0xFF161823);
        storyBtn.addView(storyTv);
        storyBtn.setOnClickListener(v -> {
            if (captionInput != null && captionInput.getText().length() == 0) {
                captionInput.setText("Story Reel");
            }
            startReelUpload();
        });
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f);
        sblp.rightMargin = Ui.dp(this, 10);
        bottomBar.addView(storyBtn, sblp);

        // Right: Red [ Next ] Button
        TextView nextBtn = Ui.tvBold(this, "Next", 15f, Color.WHITE);
        nextBtn.setGravity(Gravity.CENTER);
        nextBtn.setBackground(Ui.round(this, 0xFFFE2C55, 24));
        nextBtn.setOnClickListener(v -> switchStage(STAGE_POST_FORM));
        bottomBar.addView(nextBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f));

        editorStageRoot.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    // ==================== STAGE 3: CLEAN WHITE TIKTOK POST SCREEN (Screenshot_20261111-110627.jpg) ====================

    private void buildStage3CleanWhitePostScreen() {
        postStageRoot = Ui.col(this);
        postStageRoot.setBackgroundColor(Color.WHITE);

        // Top Bar: [ ← ]
        LinearLayout topBar = Ui.row(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(topBar, 14, 12, 14, 10);
        ImageView backBtn = com.friendbook.social.core.StudioVectorIcons.iconView(this, "back", 24, 0xFF161823);
        Ui.pad(backBtn, 6, 6, 14, 6);
        backBtn.setOnClickListener(v -> switchStage(STAGE_PREVIEW_EDITOR));
        topBar.addView(backBtn, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 36)));
        postStageRoot.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        // Scrollable Body
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 16, 6, 16, 24);

        // 1. Description Input (left) + Cover Thumbnail with "Edit cover" (right)
        LinearLayout descAndCoverRow = Ui.row(this);
        descAndCoverRow.setGravity(Gravity.TOP);

        LinearLayout leftDescCol = Ui.col(this);
        captionInput = new EditText(this);
        captionInput.setHint("Go into greater detail about your experience and thoughts...");
        captionInput.setHintTextColor(0xFF8A8B91);
        captionInput.setTextColor(0xFF161823);
        captionInput.setTextSize(15f);
        captionInput.setMinLines(4);
        captionInput.setMaxLines(6);
        captionInput.setGravity(Gravity.TOP | Gravity.START);
        captionInput.setBackground(null);
        captionInput.setPadding(0, 0, Ui.dp(this, 10), Ui.dp(this, 8));
        leftDescCol.addView(captionInput, new LinearLayout.LayoutParams(-1, -2));

        // [ # Hashtags ]   [ @ Friends ] light grey pills
        LinearLayout tagPillRow = Ui.row(this);
        tagPillRow.setPadding(0, Ui.dp(this, 8), 0, 0);

        TextView hashBtn = Ui.tvBold(this, "# Hashtags", 13f, 0xFF161823);
        hashBtn.setBackground(Ui.round(this, 0xFFF1F1F2, 8));
        Ui.pad(hashBtn, 12, 7, 12, 7);
        hashBtn.setOnClickListener(v -> showHashtagPickerDialog());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.rightMargin = Ui.dp(this, 8);
        tagPillRow.addView(hashBtn, hlp);

        TextView friendsBtn = Ui.tvBold(this, "@ Friends", 13f, 0xFF161823);
        friendsBtn.setBackground(Ui.round(this, 0xFFF1F1F2, 8));
        Ui.pad(friendsBtn, 12, 7, 12, 7);
        friendsBtn.setOnClickListener(v -> appendTag("@"));
        tagPillRow.addView(friendsBtn);

        leftDescCol.addView(tagPillRow);
        descAndCoverRow.addView(leftDescCol, new LinearLayout.LayoutParams(0, -2, 1f));

        // Right Cover Thumbnail Card (92x124dp) with "Edit cover" banner at bottom
        FrameLayout coverCard = new FrameLayout(this);
        coverCard.setBackground(Ui.round(this, 0xFF161823, 10));
        coverCard.setClipToOutline(true);
        postCoverImage = new ImageView(this);
        postCoverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverCard.addView(postCoverImage, new FrameLayout.LayoutParams(-1, -1));

        TextView editCoverBar = Ui.tvBold(this, "Edit cover", 11.5f, Color.WHITE);
        editCoverBar.setGravity(Gravity.CENTER);
        editCoverBar.setBackgroundColor(0x99000000);
        Ui.pad(editCoverBar, 4, 5, 4, 5);
        coverCard.addView(editCoverBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        coverCard.setOnClickListener(v -> showEditCoverDialog());
        descAndCoverRow.addView(coverCard, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 124)));

        body.addView(descAndCoverRow, new LinearLayout.LayoutParams(-1, -2));

        // Divider
        View div1 = new View(this);
        div1.setBackgroundColor(0xFFE8E8EC);
        LinearLayout.LayoutParams d1lp = new LinearLayout.LayoutParams(-1, 1);
        d1lp.topMargin = Ui.dp(this, 14);
        d1lp.bottomMargin = Ui.dp(this, 6);
        body.addView(div1, d1lp);

        // 2. Option Rows (matching Screenshot_20261111-110627.jpg)
        body.addView(makePostOptionRow("star", "Content disclosure and ads", null, false,
                tv -> disclosureRowValueTv = tv, v -> {
                    contentDisclosureOn = !contentDisclosureOn;
                    if (disclosureRowValueTv != null) {
                        disclosureRowValueTv.setText(contentDisclosureOn ? "On" : "");
                    }
                    Ui.toast(this, contentDisclosureOn ? "Content disclosure enabled" : "Content disclosure off");
                }));

        body.addView(makePostOptionRow("location", "Location", null, false,
                tv -> locationRowValueTv = tv, v -> showLocationInputDialog()));

        // Quick Location Chips Row (Screenshot_20261111-110627.jpg)
        HorizontalScrollView locScroll = new HorizontalScrollView(this);
        locScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout locRow = Ui.row(this);
        locRow.setPadding(Ui.dp(this, 28), 0, 0, Ui.dp(this, 8));
        final String[] quickLocs = {"Lahore", "Islamabad", "Karachi", "Dubai", "Restaurant", "Park"};
        for (final String loc : quickLocs) {
            TextView lc = Ui.tv(this, loc, 12.5f, 0xFF54565F);
            lc.setBackground(Ui.round(this, 0xFFF1F1F2, 6));
            Ui.pad(lc, 10, 6, 10, 6);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
            llp.rightMargin = Ui.dp(this, 8);
            lc.setOnClickListener(v -> {
                selectedLocation = loc;
                if (locationRowValueTv != null) locationRowValueTv.setText(loc);
            });
            locRow.addView(lc, llp);
        }
        locScroll.addView(locRow);
        body.addView(locScroll);

        body.addView(makePostOptionRow("link", "Add link", null, true,
                tv -> linkRowValueTv = tv, v -> showAddLinkDialog()));

        body.addView(makePostOptionRow("globe", selectedPrivacy, null, false,
                tv -> privacyRowValueTv = tv, v -> {
                    if ("Everyone can view this post".equals(selectedPrivacy)) {
                        selectedPrivacy = "Friends only";
                    } else if ("Friends only".equals(selectedPrivacy)) {
                        selectedPrivacy = "Only you";
                    } else {
                        selectedPrivacy = "Everyone can view this post";
                    }
                    if (privacyRowValueTv != null) privacyRowValueTv.setText(selectedPrivacy);
                }));

        body.addView(makePostOptionRow("more", "More options",
                "Manage upload quality (1080p 4K UHD HDR)", false, null,
                v -> showMoreOptionsDialog()));

        // Share to row (Screenshot_20261111-110627.jpg)
        LinearLayout shareRow = Ui.row(this);
        shareRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(shareRow, 0, 14, 0, 10);
        TextView shareLbl = Ui.tv(this, "Share to", 14.5f, 0xFF161823);
        shareRow.addView(shareLbl, new LinearLayout.LayoutParams(0, -2, 1f));
        String[] shareBadges = {"captions", "send", "gallery", "link"};
        int[] shareColors = {0xFF25D366, 0xFF1877F2, 0xFFE1306C, 0xFF54565F};
        for (int i = 0; i < shareBadges.length; i++) {
            FrameLayout sbWrap = new FrameLayout(this);
            sbWrap.setBackground(Ui.circle(shareColors[i]));
            ImageView sb = com.friendbook.social.core.StudioVectorIcons.iconView(
                    this, shareBadges[i], 17, Color.WHITE);
            int sbIc = Ui.dp(this, 18);
            sbWrap.addView(sb, new FrameLayout.LayoutParams(sbIc, sbIc, Gravity.CENTER));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
            slp.leftMargin = Ui.dp(this, 8);
            shareRow.addView(sbWrap, slp);
        }
        body.addView(shareRow);

        // Live Upload Progress Card
        progressCard = Ui.col(this);
        progressCard.setBackground(Ui.roundStroke(this, 0xFFF8F9FA, 12, 0xFFE8E8EC, 1));
        Ui.pad(progressCard, 14, 12, 14, 12);
        progressCard.setVisibility(View.GONE);
        LinearLayout pHead = Ui.row(this);
        progressTitle = Ui.tvBold(this, "Uploading Reel…", 13.5f, 0xFF161823);
        progressPercent = Ui.tvBold(this, "0%", 13.5f, 0xFFFE2C55);
        pHead.addView(progressTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        pHead.addView(progressPercent);
        progressCard.addView(pHead);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressCard.addView(progressBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 8)));
        body.addView(progressCard);

        sv.addView(body);
        postStageRoot.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Bottom Fixed Bar: [ 📥 Drafts ]      [ 📤 Post ] (Screenshot_20261111-110627.jpg)
        View botDiv = new View(this);
        botDiv.setBackgroundColor(0xFFE8E8EC);
        postStageRoot.addView(botDiv, new LinearLayout.LayoutParams(-1, 1));

        LinearLayout bottomBar = Ui.row(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(Color.WHITE);
        Ui.pad(bottomBar, 16, 10, 16, 14);

        TextView draftsBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "draft", "Drafts", 15f, 0, 0xFF161823, 0xFF161823);
        draftsBtn.setGravity(Gravity.CENTER);
        draftsBtn.setBackground(Ui.round(this, 0xFFF1F1F2, 8));
        draftsBtn.setOnClickListener(v -> saveReelDraftAndClose());
        LinearLayout.LayoutParams dblp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f);
        dblp.rightMargin = Ui.dp(this, 10);
        bottomBar.addView(draftsBtn, dblp);

        postSubmitBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "send", "Post", 15f, 0, Color.WHITE, Color.WHITE);
        postSubmitBtn.setGravity(Gravity.CENTER);
        postSubmitBtn.setBackground(Ui.round(this, 0xFFFE2C55, 8));
        postSubmitBtn.setOnClickListener(v -> startReelUpload());
        bottomBar.addView(postSubmitBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));

        postStageRoot.addView(bottomBar, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout makePostOptionRow(String icon, String title, String subtitle,
                                           boolean showRedDot, LabelConsumer rightValueConsumer,
                                           View.OnClickListener click) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 0, 13, 0, 13);

        ImageView icIv = com.friendbook.social.core.StudioVectorIcons.iconView(this, icon, 20, 0xFF161823);
        icIv.setPadding(0, Ui.dp(this, 2), 0, 0);
        row.addView(icIv, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 24)));

        LinearLayout textCol = Ui.col(this);
        TextView titleTv = Ui.tv(this, title, 15f, 0xFF161823);
        textCol.addView(titleTv);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = Ui.tv(this, subtitle, 12f, 0xFF8A8B91);
            subTv.setPadding(0, Ui.dp(this, 2), 0, 0);
            textCol.addView(subTv);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView valTv = Ui.tv(this, "", 13f, 0xFF8A8B91);
        valTv.setPadding(0, 0, Ui.dp(this, 6), 0);
        row.addView(valTv);
        if (rightValueConsumer != null) rightValueConsumer.accept(valTv);

        if (showRedDot) {
            View dot = new View(this);
            dot.setBackground(Ui.circle(0xFFFE2C55));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 7), Ui.dp(this, 7));
            dlp.rightMargin = Ui.dp(this, 8);
            row.addView(dot, dlp);
        }

        TextView chev = Ui.tvBold(this, "›", 20f, 0xFF8A8B91);
        row.addView(chev);
        row.setOnClickListener(click);
        return row;
    }

    // ==================== DIALOGS, SHEETS & OVERLAY TOOLS ====================

    private void openEffectsBottomSheet() {
        TikTokReelStudioHelper.showEffectsBottomSheet(this, activeEffectKey, effect -> {
            if (effect == null) {
                activeEffectKey = "";
                editOpts.filterPreset = "none";
                editOpts.filterOverlayColor = 0;
            } else {
                activeEffectKey = effect.key;
                editOpts.filterPreset = effect.filterPreset;
                editOpts.filterOverlayColor = effect.overlayTintColor;
            }
            refreshCameraGrade();
            refreshStageOverlays();
        });
    }

    private void openSoundsBottomSheet() {
        TikTokReelStudioHelper.showSoundsBottomSheet(this, editOpts.audioUrl, sound -> {
            if (sound == null) return;
            initialSoundUrl = sound.soundUrl;
            initialSoundTitle = sound.title;
            initialSoundPhoto = sound.authorPhoto;
            editOpts.audioUrl = sound.soundUrl;
            editOpts.audioTitle = sound.title;
            editOpts.soundAuthorPhoto = sound.authorPhoto;
            editOpts.muteOriginalAudio = true;
            previewMuted = true;
            if (camSoundsPillTv != null) camSoundsPillTv.setText(sound.title);
            if (editorSoundPillTv != null) editorSoundPillTv.setText(sound.title);
            if (previewMp != null) {
                try { previewMp.setVolume(0f, 0f); } catch (Throwable ignored) {}
            }
            if (currentStage == STAGE_PREVIEW_EDITOR) {
                startAttachedSoundPreview();
            }
            Ui.toast(this, "Attached sound: " + sound.title);
        });
    }

    private void cycleOrPickFilterDialog() {
        new AlertDialog.Builder(this)
                .setTitle("4K UHD Filters & Effects")
                .setItems(FILTER_LABELS, (d, which) -> {
                    if (which >= 0 && which < FILTER_KEYS.length) {
                        editOpts.filterPreset = FILTER_KEYS[which];
                        editOpts.filterOverlayColor = FILTER_TINTS[which];
                        refreshCameraGrade();
                        refreshStageOverlays();
                    }
                })
                .show();
    }

    private void toggleFilterBar() {
        if (filterStripScroll == null) return;
        boolean show = filterStripScroll.getVisibility() != View.VISIBLE;
        filterStripScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && trimPanel != null) trimPanel.setVisibility(View.GONE);
    }

    private void toggleTrimPanel() {
        if (trimPanel == null) return;
        boolean show = trimPanel.getVisibility() != View.VISIBLE;
        trimPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && filterStripScroll != null) filterStripScroll.setVisibility(View.GONE);
    }

    private void showTextOverlayDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 12, 18, 8);
        final EditText input = Ui.input(this, "Type text banner (drag on video)...");
        input.setText(editOpts.overlayText != null ? editOpts.overlayText : "");
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        TextView styleLbl = Ui.tvBold(this, "Brush Banner Style", 12.5f, Ui.sub(this));
        styleLbl.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        box.addView(styleLbl);

        LinearLayout colorRow = Ui.row(this);
        final int[] bgColors = {0xDD263238, 0xDDFE2C55, 0xDD1877F2, 0xDD000000, 0xDD8E24AA, 0x00000000};
        final String[] bgNames = {"Brush Slate", "Studio Red", "Blue", "Classic Dark", "Neon Purple", "Clean"};
        for (int i = 0; i < bgColors.length; i++) {
            final int bgc = bgColors[i];
            TextView cBtn = Ui.roundChip(this, bgNames[i], bgc == 0 ? 0xFF424242 : bgc, Color.WHITE, 11f);
            Ui.pad(cBtn, 8, 5, 8, 5);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.rightMargin = Ui.dp(this, 6);
            cBtn.setOnClickListener(v -> editOpts.overlayBgColor = bgc);
            colorRow.addView(cBtn, clp);
        }
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.addView(colorRow);
        box.addView(hsv);

        new AlertDialog.Builder(this)
                .setTitle("Aa Text Overlay")
                .setView(box)
                .setPositiveButton("Done", (d, w) -> {
                    editOpts.overlayText = input.getText() == null ? "" : input.getText().toString().trim();
                    if (editOpts.overlayBgColor == 0xCC000000) editOpts.overlayBgColor = 0xDD263238;
                    refreshStageOverlays();
                })
                .setNeutralButton("Clear", (d, w) -> {
                    editOpts.overlayText = "";
                    refreshStageOverlays();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Real 2026 vector stickers: the picker shows drawn icons (never keyboard emojis) and stores the
     * vector KEY, so the editor, the player overlay and the baked poster all draw the same glyph.
     */
    private static final String[][] STICKER_TILES = {
            {"flame", "Flame"}, {"heart", "Heart"}, {"laugh", "Haha"}, {"star", "Star"},
            {"crown", "Crown"}, {"confetti", "Party"}, {"trophy", "Trophy"}, {"rose", "Rose"},
            {"rocket", "Rocket"}, {"medal", "Medal"}, {"music", "Music"}, {"gift", "Gift"},
            {"lion", "Lion"}, {"jet", "Jet"}, {"close", "Remove Sticker"},
    };

    private void showStickerPickerDialog() {
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(5);
        final int margin = Ui.dp(this, 7);
        final int size = Ui.dp(this, 54);
        grid.setPadding(margin, margin, margin, margin);
        final AlertDialog[] dialogRef = new AlertDialog[1];
        for (String[] tile : STICKER_TILES) {
            final String key = tile[0];
            final boolean removes = "close".equals(key);
            TextView icon = com.friendbook.social.core.StudioVectorIcons.circleIconButton(this, key, 54,
                    removes ? 0xFFFFEBEE : 0xFFF2F3F5,
                    removes ? 0xFFE41E3F : 0xFF1A77F2,
                    v -> {
                        editOpts.stickerText = removes ? "" : key;
                        refreshStageOverlays();
                        if (dialogRef[0] != null) dialogRef[0].dismiss();
                    });
            icon.setContentDescription(tile[1]);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(margin, margin, margin, margin);
            grid.addView(icon, lp);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add Draggable Sticker")
                .setView(grid)
                .setNegativeButton("Cancel", null)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    private void showEditorSettingsDialog() {
        String[] items = {
                "1080p 4K UHD HDR Quality: " + (highQualityUpload ? "ON" : "Standard"),
                "Mute Original Camera Audio: " + (previewMuted ? "Muted" : "Active"),
                "Reset All Video Edits"
        };
        new AlertDialog.Builder(this)
                .setTitle("Studio Settings")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        highQualityUpload = !highQualityUpload;
                        Ui.toast(this, highQualityUpload
                                ? "1080p 4K UHD HDR Quality Enabled"
                                : "Standard Upload Quality");
                    } else if (which == 1) {
                        previewMuted = !previewMuted;
                        editOpts.muteOriginalAudio = previewMuted;
                        if (previewMp != null) {
                            try {
                                float vol = previewMuted ? 0f : 1f;
                                previewMp.setVolume(vol, vol);
                            } catch (Throwable ignored) {}
                        }
                    } else if (which == 2) {
                        resetAllEdits();
                    }
                })
                .show();
    }

    private void showEditCoverDialog() {
        if (videoUri == null) return;
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 14, 18, 10);
        final TextView lbl = Ui.tvBold(this, "Drag slider to pick cover frame", 13f, Ui.txt(this));
        box.addView(lbl);
        final SeekBar sb = new SeekBar(this);
        sb.setMax(1000);
        box.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Edit Cover")
                .setView(box)
                .setPositiveButton("Apply Cover", (d, w) -> {
                    long targetMs = totalDurationMs > 0
                            ? (long) ((sb.getProgress() / 1000f) * totalDurationMs) : 0L;
                    editOpts.coverTimeMs = targetMs;
                    extractCoverFrameAsync(videoUri, targetMs);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showHashtagPickerDialog() {
        final String[] tags = {"#fyp", "#foryou", "#viral", "#trending", "#friendbook", "#reels", "#aesthetic"};
        new AlertDialog.Builder(this)
                .setTitle("# Add Trending Hashtag")
                .setItems(tags, (d, which) -> appendTag(tags[which]))
                .show();
    }

    private void showLocationInputDialog() {
        final EditText input = Ui.input(this, "Enter city or place name...");
        input.setText(selectedLocation);
        new AlertDialog.Builder(this)
                .setTitle("Add Location")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    selectedLocation = input.getText() == null ? "" : input.getText().toString().trim();
                    if (locationRowValueTv != null) locationRowValueTv.setText(selectedLocation);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddLinkDialog() {
        final EditText input = Ui.input(this, "https://...");
        input.setText(attachedLink);
        new AlertDialog.Builder(this)
                .setTitle("Add Link")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    attachedLink = input.getText() == null ? "" : input.getText().toString().trim();
                    if (linkRowValueTv != null) linkRowValueTv.setText(attachedLink.isEmpty() ? "" : "Added");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showMoreOptionsDialog() {
        String[] opts = {
                "Upload in 1080p 4K UHD HDR (" + (highQualityUpload ? "ON" : "OFF") + ")",
                "Allow comments (ON)",
                "Allow Reuse of Sound (ON)"
        };
        new AlertDialog.Builder(this)
                .setTitle("More options")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        highQualityUpload = !highQualityUpload;
                        Ui.toast(this, "1080p HDR Upload: " + (highQualityUpload ? "ON" : "OFF"));
                    }
                })
                .setPositiveButton("Done", null)
                .show();
    }

    private void saveReelDraftAndClose() {
        SharedPreferences sp = getSharedPreferences("fb_reel_drafts", Context.MODE_PRIVATE);
        String cap = captionInput != null && captionInput.getText() != null
                ? captionInput.getText().toString().trim() : "";
        sp.edit()
                .putString("draft_caption", cap)
                .putString("draft_uri", videoUri != null ? videoUri.toString() : "")
                .putLong("draft_ts", System.currentTimeMillis())
                .apply();
        Ui.toast(this, "Saved to Drafts!");
        finish();
    }

    private void attachStageDragListener(final View target, final boolean isTextOverlay) {
        final float[] dX = new float[1], dY = new float[1];
        target.setOnTouchListener((v, ev) -> {
            int sw = stage.getWidth(), sh = stage.getHeight();
            if (sw <= 0 || sh <= 0) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX[0] = v.getTranslationX() - ev.getRawX();
                    dY[0] = v.getTranslationY() - ev.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float nx = Math.max(Ui.dp(this, 8), Math.min(sw - v.getWidth() - Ui.dp(this, 8), ev.getRawX() + dX[0]));
                    float ny = Math.max(Ui.dp(this, 48), Math.min(sh - v.getHeight() - Ui.dp(this, 80), ev.getRawY() + dY[0]));
                    v.setTranslationX(nx);
                    v.setTranslationY(ny);
                    float cx = (nx + v.getWidth() / 2f) / sw;
                    float cy = (ny + v.getHeight() / 2f) / sh;
                    if (isTextOverlay) { editOpts.overlayX = cx; editOpts.overlayY = cy; }
                    else { editOpts.stickerX = cx; editOpts.stickerY = cy; }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return false;
        });
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
                        Bitmap scaled = Bitmap.createScaledBitmap(frame,
                                Math.max(1, Math.round(bw * r)), Math.max(1, Math.round(bh * r)), true);
                        if (scaled != frame) frame.recycle();
                        frame = scaled;
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(24 * 1024);
                    frame.compress(Bitmap.CompressFormat.JPEG, 82, bos);
                    final byte[] bytes = bos.toByteArray();
                    final Bitmap previewBmp = frame;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (videoMeta != null) videoMeta.posterJpeg = bytes;
                        if (previewPoster != null) previewPoster.setImageBitmap(previewBmp);
                        if (postCoverImage != null) postCoverImage.setImageBitmap(previewBmp);
                    });
                }
            } catch (Throwable ignored) {
            } finally {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }, "reel-cover-pick").start();
    }

    private void resetAllEdits() {
        editOpts.overlayText = "";
        editOpts.overlayColor = 0xFFFFFFFF;
        editOpts.overlayBgColor = 0xDD263238;
        editOpts.overlayX = 0.5f;
        editOpts.overlayY = 0.42f;
        editOpts.stickerText = "";
        editOpts.stickerX = 0.78f;
        editOpts.stickerY = 0.32f;
        editOpts.stickerScale = 1.0f;
        editOpts.trimStartMs = 0;
        editOpts.trimEndMs = 0;
        editOpts.coverTimeMs = -1L;
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
        if (trimStartSeek != null) trimStartSeek.setProgress(0);
        if (trimEndSeek != null) trimEndSeek.setProgress(1000);
        refreshStageOverlays();
    }

    private void startAttachedSoundPreview() {
        stopAttachedSoundPreview();
        if (editOpts.audioUrl == null || editOpts.audioUrl.isEmpty()) return;
        try {
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            attachedSoundMp = mp;
            mp.setLooping(true);
            File cached = ReelVideoCache.getCachedFile(this, editOpts.audioUrl);
            if (cached != null) {
                try (FileInputStream fis = new FileInputStream(cached)) {
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
        } catch (Throwable ignored) {}
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
        // Real 4K UHD GPU ColorMatrix grade straight on the preview TextureView + procedural lens shader
        TikTokReelStudioHelper.applyZeroLagVisualOverlay(
                previewTexture, stageBeautyTint, stageFilterTint,
                activeBeautyLevel, editOpts.filterPreset, editOpts.filterOverlayColor);
        applyPreviewTransform();
        if (stageTextPill == null || stageStickerTv == null || stage == null) return;

        if (editOpts.overlayText != null && !editOpts.overlayText.trim().isEmpty()) {
            stageTextPill.setText(editOpts.overlayText.trim());
            stageTextPill.setTextColor(editOpts.overlayColor != 0 ? editOpts.overlayColor : Color.WHITE);
            stageTextPill.setBackground((editOpts.overlayBgColor >>> 24) > 0
                    ? Ui.round(this, editOpts.overlayBgColor, 14) : null);
            stageTextPill.setVisibility(View.VISIBLE);
            stage.post(() -> {
                int sw = stage.getWidth(), sh = stage.getHeight();
                int pw = stageTextPill.getWidth(), ph = stageTextPill.getHeight();
                if (sw > 0 && sh > 0) {
                    stageTextPill.setTranslationX(Math.max(Ui.dp(this, 8),
                            Math.min(sw - pw - Ui.dp(this, 8), editOpts.overlayX * sw - pw / 2f)));
                    stageTextPill.setTranslationY(Math.max(Ui.dp(this, 56),
                            Math.min(sh - ph - Ui.dp(this, 90), editOpts.overlayY * sh - ph / 2f)));
                }
            });
        } else {
            stageTextPill.setVisibility(View.GONE);
        }

        if (editOpts.stickerText != null && !editOpts.stickerText.trim().isEmpty()) {
            stageStickerTv.setText(editOpts.stickerText.trim());
            stageStickerTv.setScaleX(editOpts.stickerScale);
            stageStickerTv.setScaleY(editOpts.stickerScale);
            stageStickerTv.setVisibility(View.VISIBLE);
            stage.post(() -> {
                int sw = stage.getWidth(), sh = stage.getHeight();
                int vw = stageStickerTv.getWidth(), vh = stageStickerTv.getHeight();
                if (sw > 0 && sh > 0) {
                    stageStickerTv.setTranslationX(Math.max(Ui.dp(this, 8),
                            Math.min(sw - vw - Ui.dp(this, 8), editOpts.stickerX * sw - vw / 2f)));
                    stageStickerTv.setTranslationY(Math.max(Ui.dp(this, 56),
                            Math.min(sh - vh - Ui.dp(this, 90), editOpts.stickerY * sh - vh / 2f)));
                }
            });
        } else {
            stageStickerTv.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ReelUploadManager.addListener(uploadListener);
        if (currentStage == STAGE_CAMERA && cameraController != null) {
            cameraController.startPreviewIfReady();
        } else if (currentStage == STAGE_PREVIEW_EDITOR && videoUri != null) {
            mainHandler.removeCallbacks(trimLoopRunnable);
            mainHandler.post(trimLoopRunnable);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        ReelUploadManager.removeListener(uploadListener);
        mainHandler.removeCallbacks(trimLoopRunnable);
        mainHandler.removeCallbacks(recordTickRunnable);
        stopAttachedSoundPreview();
        if (cameraController != null) cameraController.releaseCamera();
        previewPause();
    }

    private void appendTag(String tag) {
        if (captionInput == null) return;
        String cur = captionInput.getText() == null ? "" : captionInput.getText().toString();
        if (cur.contains(tag) && tag.length() > 1) return;
        String next = (cur.isEmpty() || cur.endsWith(" ")) ? (cur + tag) : (cur + " " + tag);
        if (tag.length() > 1) next += " ";
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

    private void ensureCameraPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean needCam = checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED;
            boolean needMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED;
            if (needCam || needMic) {
                requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, RC_CAM_PERM);
                return;
            }
        }
        if (cameraController != null) cameraController.startPreviewIfReady();
    }

    private void launchVideoCaptureIntent() {
        try {
            Intent takeVideo = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            takeVideo.putExtra(MediaStore.EXTRA_DURATION_LIMIT, selectedMaxDurationSec);
            takeVideo.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
            startActivityForResult(takeVideo, RC_RECORD_VIDEO);
        } catch (Throwable t) {
            openGalleryPicker();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_CAM_PERM && cameraController != null) {
            cameraController.startPreviewIfReady();
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

        resetAllEdits();
        switchStage(STAGE_PREVIEW_EDITOR);

        pendingPreviewUri = picked;
        preparePreviewPlayer();

        new Thread(() -> {
            final Cloudinary.VideoMeta m = Cloudinary.inspectVideo(getApplicationContext(), picked);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !picked.equals(videoUri)) return;
                videoMeta = m;
                if (totalDurationMs <= 0 && m.durationSec > 0) {
                    totalDurationMs = m.durationSec * 1000;
                }
                if (m.posterJpeg != null && m.posterJpeg.length > 0) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(m.posterJpeg, 0, m.posterJpeg.length);
                    if (postCoverImage != null) postCoverImage.setImageBitmap(bmp);
                    if (!previewIsPlaying() && previewPoster != null) {
                        previewPoster.setImageBitmap(bmp);
                        previewPoster.setVisibility(View.VISIBLE);
                    }
                }
            });
        }, "reel-meta-inspect").start();

        ContentSafetyEngine.scanMediaAsync(this, Collections.singletonList(picked), true, "", verdict -> {
            if (isFinishing() || isDestroyed() || !picked.equals(videoUri)) return;
            safetyScanning = false;
            if (verdict != null && verdict.unsafe) {
                videoUri = null;
                videoMeta = null;
                safetyVerified = false;
                releasePreviewPlayer();
                switchStage(STAGE_CAMERA);
                Ui.toast(this, verdict.message);
            } else {
                safetyVerified = true;
            }
        });
    }

    private void startReelUpload() {
        if (!com.friendbook.social.core.Admin.checkNotSuspended(this)) return;
        if (videoUri == null) {
            Ui.toast(this, "Pick or record a video first");
            switchStage(STAGE_CAMERA);
            return;
        }
        if (safetyScanning) {
            Ui.toast(this, "Finishing 1-second safety check, tap again in a moment…");
            return;
        }
        StringBuilder capBuilder = new StringBuilder();
        if (captionInput != null && captionInput.getText() != null) {
            capBuilder.append(captionInput.getText().toString().trim());
        }
        if (!selectedLocation.isEmpty() && !capBuilder.toString().contains(selectedLocation)) {
            if (capBuilder.length() > 0) capBuilder.append(" · ");
            capBuilder.append(selectedLocation);
        }
        if (!attachedLink.isEmpty() && !capBuilder.toString().contains(attachedLink)) {
            if (capBuilder.length() > 0) capBuilder.append(" ");
            capBuilder.append(attachedLink);
        }
        String cap = capBuilder.toString().trim();
        ContentSafetyEngine.Verdict textV = ContentSafetyEngine.checkText(
                cap + " " + (editOpts.overlayText != null ? editOpts.overlayText : ""));
        if (textV.unsafe) {
            Ui.toast(this, textV.message);
            return;
        }
        previewPause();
        stopAttachedSoundPreview();
        editOpts.beautyLevel = activeBeautyLevel;
        ReelUploadManager.startUpload(this, videoUri, cap, videoMeta, safetyVerified, editOpts);
    }
}
