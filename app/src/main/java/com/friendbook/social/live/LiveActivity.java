package com.friendbook.social.live;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.R;
import com.friendbook.social.core.ContentSafetyEngine;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Ui;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Full Real-Time Live Video & Audio Broadcast Studio + Viewer Room (`v2.6.0`):
 * - Real live Camera2 WebP video streaming + real live 8kHz G.711 u-law voice streaming
 * - Co-Host / Guest split-screen camera & voice ("Join Live")
 * - Interactive Live Polls, Q&A Stage Spotlight, Star Gifts & Top Supporters Leaderboard
 * - Full Studio Controls: Flip, Mirror, Pinch-Zoom, Tap-Focus, Torch, Front Ring-Light,
 *   Beauty Exposure Boost, 8 Visual Filters, Video Quality (HD / Auto / Data Saver),
 *   Camera Pause Shield, Clean View HUD, Slow Mode, Keyword Filter, Live Moderators, Mute & Kick.
 */
public class LiveActivity extends Activity implements LiveStreamEngine.Listener {

    private static final int REQ_LIVE_PERMS = 701;
    private static final int REQ_COHOST_PERMS = 702;

    private static final String[] FILTER_NAMES = {
            "✨ Natural", "☀️ Warm", "❄️ Cool", "🌸 Rose",
            "🌙 Cinema", "🎞️ Vintage", "💎 Vivid", "🖤 Mono"
    };
    private static final int[] FILTER_OVERLAYS = {
            0x00000000, 0x26FF9800, 0x2400B0FF, 0x26FF4081,
            0x380D1B2A, 0x2AD7CCC8, 0x1E7C4DFF, 0x48212121
    };
    private static final String[] CATEGORIES = {
            "💬 Chatting", "🎮 Gaming", "🎵 Music", "📚 Q&A",
            "🏏 Sports", "✨ Lifestyle", "🍳 Cooking", "💻 Tech"
    };
    private static final String[] REACTION_EMOJIS = {"❤️", "🔥", "😂", "👏", "😍", "🎉"};

    // Launch & Identity
    private String liveId;
    private String hostId;
    private boolean isHost;
    private boolean isLiveStarted;
    private boolean isEnded;
    private boolean viewerCountJoined;

    // Engines (main + optional Co-Host local engine when viewer joins stage)
    private LiveStreamEngine engine;
    private LiveStreamEngine coHostLocalEngine;

    // Stage Views
    private FrameLayout rootStage;
    private TextureView cameraTexture;
    private ImageView remoteVideoView;
    private LinearLayout fallbackStage;
    private ImageView stageAvatar;
    private TextView stageStatusTv;
    private View filterOverlayView;
    private View ringLightOverlay;
    private FrameLayout floatingHeartsLayer;

    // Co-Host PIP Card (Top-Right under header)
    private FrameLayout coHostCard;
    private TextureView coHostLocalTexture;
    private ImageView coHostRemoteImage;
    private TextView coHostLabelTv;
    private TextView coHostCloseBtn;

    // Pre-Live Studio Views
    private FrameLayout preLiveOverlay;
    private EditText titleInput;
    private String selectedCategory = CATEGORIES[0];
    private boolean privacyFriendsOnly = false;
    private TextView privacyChip;
    private LinearLayout categoryChipRow;
    private TextView qualityStudioChip;
    private TextView countdownTv;

    // Active Broadcast HUD Views
    private FrameLayout liveHudOverlay;
    private TextView restoreHudBtn;
    private ImageView hostTopAvatar;
    private TextView hostTopName;
    private TextView hostTopSub;
    private TextView followHostBtn;
    private TextView liveBadgeTv;
    private TextView timerTv;
    private TextView viewerCountTv;
    private TextView voiceMeterTv;
    private LinearLayout pinnedCommentBox;
    private TextView pinnedCommentTv;

    // Stage Interactive Cards (Spotlight Q&A + Active Poll)
    private LinearLayout spotlightQaCard;
    private TextView spotlightQaTv;
    private LinearLayout activePollCard;

    // Chat & Bottom Bar
    private RecyclerView chatRv;
    private ChatAdapter chatAdapter;
    private EditText commentInput;
    private LinearLayout bottomComposerBar;

    // Right Action Rail Buttons
    private TextView btnMic;
    private TextView btnVideoPause;
    private TextView btnSpeaker;
    private TextView btnTorch;
    private TextView btnRingLight;
    private TextView btnFilter;
    private TextView btnCoHost;
    private TextView btnQa;
    private TextView btnPoll;

    // Broadcast Settings & Room State
    private int currentFilterIdx = 0;
    private boolean ringLightOn = false;
    private boolean cleanViewMode = false;
    private boolean fitVideoCenter = false;
    private boolean commentsEnabled = true;
    private boolean slowModeEnabled = false;
    private boolean giftsEnabled = true;
    private boolean qaEnabled = true;
    private boolean coHostAllowed = true;
    private String blockedKeywordsCsv = "";
    private long lastCommentSentMs = 0L;

    // Metrics & Realtime Sync
    private long liveStartMs = 0L;
    private long frameSeqOut = 0L;
    private long audioSeqOut = 0L;
    private long coHostSeqOut = 0L;
    private long lastHostVideoUpdateMs = 0L;
    private long lastCoHostMediaPushMs = 0L;
    private int peakViewers = 1;
    private int currentViewers = 1;
    private int totalUniqueViewers = 1;
    private int totalLikes = 0;
    private int totalComments = 0;
    private int totalStarsReceived = 0;
    private String hostNameStr = "Host";
    private String hostPhotoStr = "";
    private String liveTitleStr = "";

    // Co-Host, Moderation, Q&A, Polls & Leaderboard State
    private String activeCoHostUid = "";
    private String activeCoHostName = "";
    private boolean isMeActiveCoHost = false;
    private final Set<String> activeViewerIds = new HashSet<>();
    private final Map<String, String> viewerNames = new LinkedHashMap<>();
    private final Map<String, String> viewerPhotos = new HashMap<>();
    private final Set<String> mutedUserIds = new HashSet<>();
    private final Set<String> kickedUserIds = new HashSet<>();
    private final Set<String> moderatorIds = new HashSet<>();
    private final Set<String> deletedMsgIds = new HashSet<>();
    private final Map<String, Integer> supporterStars = new HashMap<>();
    private final Map<String, String> supporterNames = new HashMap<>();
    private final List<ChatItem> qaItems = new ArrayList<>();
    private final Map<String, ChatItem> pendingCoHostRequests = new LinkedHashMap<>();

    // Poll State
    private String activePollId = "";
    private String activePollQuestion = "";
    private List<String> activePollOptions = new ArrayList<>();
    private final Map<String, Integer> activePollVoterChoice = new HashMap<>();
    private int myVotedOption = -1;

    // Listeners & Timers
    private ListenerRegistration liveDocReg;
    private ListenerRegistration chatColReg;
    private DatabaseReference rtdbStreamRef;
    private ValueEventListener rtdbStreamListener;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();

    private final Runnable durationTicker = new Runnable() {
        @Override
        public void run() {
            if (!isEnded && isLiveStarted && liveStartMs > 0 && timerTv != null) {
                long sec = Math.max(0L, (System.currentTimeMillis() - liveStartMs) / 1000L);
                long mm = sec / 60L;
                long ss = sec % 60L;
                if (mm >= 60) {
                    timerTv.setText(String.format(Locale.US, "%d:%02d:%02d", mm / 60, mm % 60, ss));
                } else {
                    timerTv.setText(String.format(Locale.US, "%02d:%02d", mm, ss));
                }
                uiHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final Runnable hostHeartbeatTask = new Runnable() {
        @Override
        public void run() {
            if (!isEnded && isHost && isLiveStarted && liveId != null) {
                Map<String, Object> hb = new HashMap<>();
                hb.put("lastHeartbeatMs", System.currentTimeMillis());
                hb.put("viewerIds", new ArrayList<>(activeViewerIds));
                hb.put("viewerCount", Math.max(1, currentViewers));
                hb.put("peakViewers", peakViewers);
                hb.put("likesCount", totalLikes);
                Fb.db().collection("lives").document(liveId).update(hb);
                uiHandler.postDelayed(this, 8000L);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);

        liveId = getIntent().getStringExtra("live_id");
        hostId = getIntent().getStringExtra("host_id");
        // SAFETY FIX: Never close silently! Empty live_id always launches Host Pre-Live Studio.
        boolean hostFlag = getIntent().getBooleanExtra("host", false);
        isHost = hostFlag || liveId == null || liveId.trim().isEmpty();
        if (isHost) {
            hostId = Fb.uid();
        }
        if (Fb.uid() == null) {
            Ui.toast(this, "Please sign in first");
            finish();
            return;
        }

        engine = new LiveStreamEngine(this, this);
        buildCompleteUi();

        if (isHost) {
            preLiveOverlay.setVisibility(View.VISIBLE);
            liveHudOverlay.setVisibility(View.GONE);
            ensureLivePermissions(REQ_LIVE_PERMS);
        } else {
            preLiveOverlay.setVisibility(View.GONE);
            liveHudOverlay.setVisibility(View.VISIBLE);
            joinExistingLiveAsViewer();
        }
    }

    // ==================== COMPLETE PRO STUDIO + BROADCAST UI ====================

    private void buildCompleteUi() {
        rootStage = new FrameLayout(this);
        rootStage.setBackgroundColor(0xFF0B0C10);

        // 1. Local Camera2 TextureView (Host)
        cameraTexture = new TextureView(this);
        cameraTexture.setVisibility(isHost ? View.VISIBLE : View.GONE);
        rootStage.addView(cameraTexture, new FrameLayout.LayoutParams(-1, -1));

        // 2. Remote Live Video ImageView (Viewer watches Host's real-time camera frames!)
        remoteVideoView = new ImageView(this);
        remoteVideoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        remoteVideoView.setVisibility(View.GONE);
        rootStage.addView(remoteVideoView, new FrameLayout.LayoutParams(-1, -1));

        // 3. Fallback Avatar Stage (shown before frames arrive or when Host pauses camera)
        fallbackStage = Ui.col(this);
        fallbackStage.setGravity(Gravity.CENTER);
        GradientDrawable bgGrad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1D29, 0xFF0F1118, 0xFF090A0F});
        fallbackStage.setBackground(bgGrad);
        fallbackStage.setVisibility(isHost ? View.GONE : View.VISIBLE);

        stageAvatar = new ImageView(this);
        Ui.avatar(this, stageAvatar, isHost ? Fb.myPhoto() : null);
        int avSz = Ui.dp(this, 108);
        FrameLayout ringWrap = new FrameLayout(this);
        ringWrap.setBackground(Ui.roundStroke(this, 0x22E41E3F, 64, 0xFFE41E3F, 3));
        Ui.pad(ringWrap, 6, 6, 6, 6);
        ringWrap.addView(stageAvatar, new FrameLayout.LayoutParams(avSz, avSz, Gravity.CENTER));
        fallbackStage.addView(ringWrap, new LinearLayout.LayoutParams(-2, -2));

        stageStatusTv = Ui.tvBold(this,
                isHost ? "📷 Camera paused (Audio still live)" : "📡 Connecting to live video & audio...",
                14, 0xDDFFFFFF);
        stageStatusTv.setGravity(Gravity.CENTER);
        Ui.pad(stageStatusTv, 20, 14, 20, 4);
        fallbackStage.addView(stageStatusTv);
        rootStage.addView(fallbackStage, new FrameLayout.LayoutParams(-1, -1));

        // 4. Studio Visual Filter Tint Layer
        filterOverlayView = new View(this);
        filterOverlayView.setBackgroundColor(0x00000000);
        filterOverlayView.setClickable(false);
        rootStage.addView(filterOverlayView, new FrameLayout.LayoutParams(-1, -1));

        // 5. Front Screen Ring-Light Fill Border (Warm white studio glow for dark rooms!)
        ringLightOverlay = new View(this);
        ringLightOverlay.setBackground(Ui.roundStroke(this, 0x16FFF8E1, 22, 0xEEFFF9C4, 18));
        ringLightOverlay.setVisibility(View.GONE);
        ringLightOverlay.setClickable(false);
        rootStage.addView(ringLightOverlay, new FrameLayout.LayoutParams(-1, -1));

        // 6. Top & Bottom Readability Gradients
        View topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB0000000, 0x00000000}));
        rootStage.addView(topScrim, new FrameLayout.LayoutParams(-1, Ui.dp(this, 140), Gravity.TOP));

        View botScrim = new View(this);
        botScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x44000000, 0x00000000}));
        rootStage.addView(botScrim, new FrameLayout.LayoutParams(-1, Ui.dp(this, 320), Gravity.BOTTOM));

        // 7. Co-Host Picture-in-Picture Split Card (116dp x 168dp)
        buildCoHostPipCard();
        rootStage.addView(coHostCard);

        // 8. Touch Gestures: Pinch-to-Zoom (Host), Tap-to-Focus (Host), Double-Tap Reaction!
        attachStageGestures();

        // 9. Floating Emoji Reactions Layer
        floatingHeartsLayer = new FrameLayout(this);
        floatingHeartsLayer.setClickable(false);
        rootStage.addView(floatingHeartsLayer, new FrameLayout.LayoutParams(-1, -1));

        // 10. Pre-Live Setup Studio Overlay + Live Broadcast HUD Overlay
        preLiveOverlay = buildPreLiveStudioOverlay();
        rootStage.addView(preLiveOverlay, new FrameLayout.LayoutParams(-1, -1));

        liveHudOverlay = buildLiveHudOverlay();
        rootStage.addView(liveHudOverlay, new FrameLayout.LayoutParams(-1, -1));

        // 11. Restore HUD Pill when user enters Clean View Mode
        restoreHudBtn = Ui.roundChip(this, "👁 Show Controls", 0xAA000000, 0xFFFFFFFF, 12);
        Ui.pad(restoreHudBtn, 14, 8, 14, 8);
        restoreHudBtn.setVisibility(View.GONE);
        restoreHudBtn.setOnClickListener(v -> toggleCleanViewMode(false));
        FrameLayout.LayoutParams rhLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rhLp.setMargins(0, Ui.dp(this, 18), Ui.dp(this, 14), 0);
        rootStage.addView(restoreHudBtn, rhLp);

        // 12. Countdown Overlay (3 · 2 · 1 · LIVE!)
        countdownTv = new TextView(this);
        countdownTv.setTextColor(0xFFFFFFFF);
        countdownTv.setTextSize(72);
        countdownTv.setTypeface(Typeface.DEFAULT_BOLD);
        countdownTv.setGravity(Gravity.CENTER);
        countdownTv.setBackgroundColor(0x66000000);
        countdownTv.setVisibility(View.GONE);
        rootStage.addView(countdownTv, new FrameLayout.LayoutParams(-1, -1));

        setContentView(rootStage);
    }

    private void buildCoHostPipCard() {
        coHostCard = new FrameLayout(this);
        coHostCard.setBackground(Ui.roundStroke(this, 0xFF18191A, 14, 0xFFFFFFFF, 2));
        coHostCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams pipLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 118), Ui.dp(this, 168), Gravity.TOP | Gravity.END);
        pipLp.setMargins(0, Ui.dp(this, 78), Ui.dp(this, 62), 0);
        coHostCard.setLayoutParams(pipLp);

        coHostRemoteImage = new ImageView(this);
        coHostRemoteImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coHostCard.addView(coHostRemoteImage, new FrameLayout.LayoutParams(-1, -1));

        coHostLocalTexture = new TextureView(this);
        coHostLocalTexture.setVisibility(View.GONE);
        coHostCard.addView(coHostLocalTexture, new FrameLayout.LayoutParams(-1, -1));

        coHostLabelTv = Ui.tvBold(this, "🤝 Co-Host", 10, 0xFFFFFFFF);
        coHostLabelTv.setSingleLine(true);
        coHostLabelTv.setEllipsize(TextUtils.TruncateAt.END);
        coHostLabelTv.setBackground(Ui.round(this, 0xAA000000, 8));
        Ui.pad(coHostLabelTv, 6, 2, 6, 2);
        FrameLayout.LayoutParams lblLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lblLp.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), Ui.dp(this, 5));
        coHostCard.addView(coHostLabelTv, lblLp);

        coHostCloseBtn = Ui.roundChip(this, "✕", 0xCCE41E3F, 0xFFFFFFFF, 10);
        Ui.pad(coHostCloseBtn, 6, 2, 6, 2);
        coHostCloseBtn.setVisibility(View.GONE);
        coHostCloseBtn.setOnClickListener(v -> removeActiveCoHost());
        FrameLayout.LayoutParams xLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        xLp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 4), 0);
        coHostCard.addView(coHostCloseBtn, xLp);
    }

    private void attachStageGestures() {
        final ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (isHost && engine != null) {
                            float nextZoom = engine.getCurrentZoom() * detector.getScaleFactor();
                            engine.setZoom(nextZoom);
                        }
                        return true;
                    }
                });

        final GestureDetector tapDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (isHost && engine != null && cameraTexture != null) {
                            engine.focusAt(e.getX(), e.getY(),
                                    cameraTexture.getWidth(), cameraTexture.getHeight());
                        }
                        return true;
                    }
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (isLiveStarted) {
                            sendReaction("❤️");
                        }
                        return true;
                    }
                });

        rootStage.setOnTouchListener((v, ev) -> {
            scaleDetector.onTouchEvent(ev);
            tapDetector.onTouchEvent(ev);
            return true;
        });
    }

    // ==================== PRE-LIVE BROADCAST SETUP STUDIO ====================

    private FrameLayout buildPreLiveStudioOverlay() {
        FrameLayout wrap = new FrameLayout(this);

        // Top Studio Control Bar
        LinearLayout topBar = Ui.row(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(topBar, 14, 16, 14, 10);

        TextView closeBtn = circleBtn("✕", 38, v -> finish());
        topBar.addView(closeBtn);

        TextView badge = Ui.roundChip(this, "🎥 LIVE CAMERA STUDIO", 0xAA000000, 0xFFFFFFFF, 12);
        Ui.pad(badge, 14, 7, 14, 7);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
        bLp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        topBar.addView(badge, bLp);

        topBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        privacyChip = Ui.roundChip(this, "🌍 Public", 0xAA1877F2, 0xFFFFFFFF, 12);
        Ui.pad(privacyChip, 12, 7, 12, 7);
        privacyChip.setOnClickListener(v -> {
            privacyFriendsOnly = !privacyFriendsOnly;
            privacyChip.setText(privacyFriendsOnly ? "👥 Friends" : "🌍 Public");
            privacyChip.setBackground(Ui.round(this, privacyFriendsOnly ? 0xAA2E7D32 : 0xAA1877F2, 20));
        });
        topBar.addView(privacyChip);
        wrap.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Right Studio Quick Tools Rail
        LinearLayout sideRail = Ui.col(this);
        sideRail.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(sideRail, 8, 8, 12, 8);

        sideRail.addView(studioSideItem("🔄", "Flip", v -> {
            engine.flipCamera();
            Ui.toast(this, engine.isLensFront() ? "Front camera" : "Back camera");
        }));
        sideRail.addView(studioSideItem("🪞", "Mirror", v -> {
            boolean next = !engine.isMirrorFront();
            engine.setMirrorFront(next);
            Ui.toast(this, next ? "Front mirror ON" : "Front mirror OFF");
        }));
        sideRail.addView(studioSideItem("💡", "Light", v -> toggleRingLightOrTorch()));
        sideRail.addView(studioSideItem("✨", "Beauty", v -> {
            boolean b = engine.toggleBeautyBoost();
            Ui.toast(this, b ? "✨ Studio brightness boost ON" : "Studio brightness normal");
        }));
        sideRail.addView(studioSideItem("🎨", "Filter", v -> cycleVisualFilter()));
        sideRail.addView(studioSideItem("⚙️", "Settings", v -> showHostSettingsSheet()));

        FrameLayout.LayoutParams srLp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        wrap.addView(sideRail, srLp);

        // Bottom Studio Setup Card
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(this, 0xCC14161D, 22));
        Ui.pad(card, 16, 16, 16, 20);

        // Host profile + Title Input + Quality Chip
        LinearLayout hostRow = Ui.row(this);
        hostRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView myAv = new ImageView(this);
        Ui.avatar(this, myAv, Fb.myPhoto());
        hostRow.addView(myAv, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        titleInput = new EditText(this);
        titleInput.setHint("Add a title for your Live broadcast...");
        titleInput.setHintTextColor(0x99FFFFFF);
        titleInput.setTextColor(0xFFFFFFFF);
        titleInput.setTextSize(14);
        titleInput.setMaxLines(2);
        titleInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        titleInput.setBackground(Ui.round(this, 0x33FFFFFF, 14));
        Ui.pad(titleInput, 12, 10, 12, 10);
        LinearLayout.LayoutParams tiLp = new LinearLayout.LayoutParams(0, -2, 1f);
        tiLp.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
        hostRow.addView(titleInput, tiLp);

        qualityStudioChip = Ui.roundChip(this, "⚡ Auto 288p", 0x33FFFFFF, 0xFFFFFFFF, 11);
        Ui.pad(qualityStudioChip, 10, 8, 10, 8);
        qualityStudioChip.setOnClickListener(v -> cycleStreamQuality());
        hostRow.addView(qualityStudioChip);
        card.addView(hostRow);

        // Category Horizontal Chips
        HorizontalScrollView catScroll = new HorizontalScrollView(this);
        catScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(-1, -2);
        csLp.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 14));
        categoryChipRow = Ui.row(this);
        catScroll.addView(categoryChipRow);
        card.addView(catScroll, csLp);
        refreshPreLiveCategoryChips();

        // Big Red GO LIVE CTA Button
        TextView goBtn = Ui.tvBold(this, "🔴 GO LIVE NOW (VIDEO + VOICE)", 15, 0xFFFFFFFF);
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setBackground(Ui.round(this, 0xFFE41E3F, 26));
        Ui.pad(goBtn, 20, 14, 20, 14);
        goBtn.setOnClickListener(v -> beginCountdownAndStartLive());
        card.addView(goBtn, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        cLp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 16));
        wrap.addView(card, cLp);

        return wrap;
    }

    private View studioSideItem(String icon, String label, View.OnClickListener click) {
        LinearLayout col = Ui.col(this);
        col.setGravity(Gravity.CENTER);
        Ui.pad(col, 4, 5, 4, 5);
        col.addView(circleBtn(icon, 40, click));
        TextView t = Ui.tvBold(this, label, 10, 0xEEFFFFFF);
        t.setPadding(0, Ui.dp(this, 2), 0, 0);
        col.addView(t);
        col.setOnClickListener(click);
        return col;
    }

    private void refreshPreLiveCategoryChips() {
        if (categoryChipRow == null) return;
        categoryChipRow.removeAllViews();
        for (final String cat : CATEGORIES) {
            boolean sel = cat.equals(selectedCategory);
            TextView chip = Ui.roundChip(this, cat,
                    sel ? 0xFFE41E3F : 0x26FFFFFF, 0xFFFFFFFF, 12);
            Ui.pad(chip, 12, 6, 12, 6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, Ui.dp(this, 8), 0);
            chip.setOnClickListener(v -> {
                selectedCategory = cat;
                refreshPreLiveCategoryChips();
            });
            categoryChipRow.addView(chip, lp);
        }
    }

    // ==================== ACTIVE BROADCAST HUD OVERLAY ====================

    private FrameLayout buildLiveHudOverlay() {
        FrameLayout hud = new FrameLayout(this);

        // 1. TOP HEADER BAR (Host Pill + Follow + LIVE Timer + Viewers + Voice Meter + End/Leave)
        LinearLayout topWrap = Ui.col(this);
        Ui.pad(topWrap, 10, 14, 10, 6);

        LinearLayout topRow = Ui.row(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // Host profile pill
        LinearLayout hostPill = Ui.row(this);
        hostPill.setGravity(Gravity.CENTER_VERTICAL);
        hostPill.setBackground(Ui.round(this, 0x88000000, 24));
        Ui.pad(hostPill, 5, 4, 10, 4);

        hostTopAvatar = new ImageView(this);
        Ui.avatar(this, hostTopAvatar, isHost ? Fb.myPhoto() : null);
        hostPill.addView(hostTopAvatar, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));

        LinearLayout hostNames = Ui.col(this);
        hostNames.setPadding(Ui.dp(this, 7), 0, Ui.dp(this, 6), 0);
        hostTopName = Ui.tvBold(this, isHost ? Fb.myName() : "Live Host", 12, 0xFFFFFFFF);
        hostTopName.setSingleLine(true);
        hostTopName.setMaxWidth(Ui.dp(this, 108));
        hostTopName.setEllipsize(TextUtils.TruncateAt.END);
        hostNames.addView(hostTopName);

        hostTopSub = Ui.tv(this, "⭐ 0 Stars", 10, 0xFFFFD54F);
        hostTopSub.setSingleLine(true);
        hostNames.addView(hostTopSub);
        hostPill.addView(hostNames);

        if (!isHost) {
            followHostBtn = Ui.roundChip(this, "+ Follow", 0xFFE41E3F, 0xFFFFFFFF, 10);
            Ui.pad(followHostBtn, 8, 4, 8, 4);
            followHostBtn.setOnClickListener(v -> followHostNow());
            hostPill.addView(followHostBtn);
        }
        hostPill.setOnClickListener(v -> {
            if (hostId != null && !hostId.isEmpty()) {
                Intent p = new Intent(this, ProfileActivity.class);
                p.putExtra("uid", hostId);
                startActivity(p);
            }
        });
        topRow.addView(hostPill);

        topRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // LIVE + Timer Badge
        LinearLayout livePill = Ui.row(this);
        livePill.setGravity(Gravity.CENTER_VERTICAL);
        livePill.setBackground(Ui.round(this, 0xFFE41E3F, 14));
        Ui.pad(livePill, 8, 4, 8, 4);
        liveBadgeTv = Ui.tvBold(this, "LIVE", 10, 0xFFFFFFFF);
        livePill.addView(liveBadgeTv);
        timerTv = Ui.tvBold(this, "00:00", 10, 0xFFFFFFFF);
        timerTv.setPadding(Ui.dp(this, 5), 0, 0, 0);
        livePill.addView(timerTv);
        topRow.addView(livePill);

        // Active Viewers Badge (clickable -> Viewers & Supporters Sheet)
        viewerCountTv = Ui.roundChip(this, "👁 1", 0x88000000, 0xFFFFFFFF, 11);
        Ui.pad(viewerCountTv, 9, 5, 9, 5);
        viewerCountTv.setOnClickListener(v -> showViewersAndLeaderboardSheet());
        LinearLayout.LayoutParams vcLp = new LinearLayout.LayoutParams(-2, -2);
        vcLp.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
        topRow.addView(viewerCountTv, vcLp);

        // Close / End Button
        TextView exitBtn = circleBtn("✕", 34, v -> confirmExitOrEnd());
        topRow.addView(exitBtn);
        topWrap.addView(topRow);

        // Second mini bar: Live Voice Meter + Quality + Leaderboard + Clean View
        LinearLayout subRow = Ui.row(this);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), 0);

        voiceMeterTv = Ui.roundChip(this, "🎙️ Voice Live", 0x77000000, 0xFF69F0AE, 10);
        Ui.pad(voiceMeterTv, 8, 3, 8, 3);
        subRow.addView(voiceMeterTv);

        TextView topGiftChip = Ui.roundChip(this, "🏆 Top Gifters", 0x77000000, 0xFFFFD54F, 10);
        Ui.pad(topGiftChip, 8, 3, 8, 3);
        topGiftChip.setOnClickListener(v -> showViewersAndLeaderboardSheet());
        LinearLayout.LayoutParams tgLp = new LinearLayout.LayoutParams(-2, -2);
        tgLp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        subRow.addView(topGiftChip, tgLp);

        TextView cleanChip = Ui.roundChip(this, "👁 Clean View", 0x77000000, 0xDDFFFFFF, 10);
        Ui.pad(cleanChip, 8, 3, 8, 3);
        cleanChip.setOnClickListener(v -> toggleCleanViewMode(true));
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(-2, -2);
        clLp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        subRow.addView(cleanChip, clLp);

        topWrap.addView(subRow);

        // Pinned Comment Banner
        pinnedCommentBox = Ui.row(this);
        pinnedCommentBox.setGravity(Gravity.CENTER_VERTICAL);
        pinnedCommentBox.setBackground(Ui.round(this, 0xCC1877F2, 12));
        Ui.pad(pinnedCommentBox, 10, 6, 10, 6);
        pinnedCommentBox.setVisibility(View.GONE);
        pinnedCommentTv = Ui.tvBold(this, "", 12, 0xFFFFFFFF);
        pinnedCommentTv.setMaxLines(2);
        pinnedCommentTv.setEllipsize(TextUtils.TruncateAt.END);
        pinnedCommentBox.addView(pinnedCommentTv, new LinearLayout.LayoutParams(0, -2, 1f));
        if (isHost) {
            TextView unpin = Ui.tvBold(this, " ✕", 12, 0xFFFFFFFF);
            unpin.setOnClickListener(v -> updatePinnedComment(""));
            pinnedCommentBox.addView(unpin);
        }
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(-1, -2);
        pinLp.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 56), 0);
        topWrap.addView(pinnedCommentBox, pinLp);

        // Stage Spotlight Q&A Card
        spotlightQaCard = Ui.row(this);
        spotlightQaCard.setGravity(Gravity.CENTER_VERTICAL);
        spotlightQaCard.setBackground(Ui.roundStroke(this, 0xDD1F222E, 14, 0xFFFFD54F, 1));
        Ui.pad(spotlightQaCard, 12, 8, 12, 8);
        spotlightQaCard.setVisibility(View.GONE);
        spotlightQaTv = Ui.tvBold(this, "", 12, 0xFFFFFFFF);
        spotlightQaCard.addView(spotlightQaTv, new LinearLayout.LayoutParams(0, -2, 1f));
        if (isHost) {
            TextView closeQa = Ui.tvBold(this, " ✕", 13, 0xFFFFD54F);
            closeQa.setOnClickListener(v -> clearSpotlightQa());
            spotlightQaCard.addView(closeQa);
        }
        LinearLayout.LayoutParams qaLp = new LinearLayout.LayoutParams(-1, -2);
        qaLp.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 56), 0);
        topWrap.addView(spotlightQaCard, qaLp);

        // Active Interactive Poll Card
        activePollCard = Ui.col(this);
        activePollCard.setBackground(Ui.roundStroke(this, 0xDD151822, 14, 0xFF1877F2, 1));
        Ui.pad(activePollCard, 12, 10, 12, 10);
        activePollCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams pollLp = new LinearLayout.LayoutParams(-1, -2);
        pollLp.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 56), 0);
        topWrap.addView(activePollCard, pollLp);

        hud.addView(topWrap, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // 2. RIGHT VERTICAL CONTROL RAIL (Scroll-safe compact buttons)
        ScrollView railScroll = new ScrollView(this);
        railScroll.setVerticalScrollBarEnabled(false);
        LinearLayout rail = Ui.col(this);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(rail, 4, 4, 8, 4);

        if (isHost) {
            rail.addView(circleBtnSpaced("🔄", v -> engine.flipCamera()));
            btnMic = circleBtnSpaced("🎙️", v -> toggleHostMic());
            rail.addView(btnMic);
            btnVideoPause = circleBtnSpaced("📹", v -> toggleHostCameraPause());
            rail.addView(btnVideoPause);
            btnRingLight = circleBtnSpaced("💡", v -> toggleRingLightOrTorch());
            rail.addView(btnRingLight);
            btnFilter = circleBtnSpaced("🎨", v -> cycleVisualFilter());
            rail.addView(btnFilter);
            btnCoHost = circleBtnSpaced("🙋", v -> showCoHostManagerSheet());
            rail.addView(btnCoHost);
            btnQa = circleBtnSpaced("❓", v -> showQaDrawerSheet());
            rail.addView(btnQa);
            btnPoll = circleBtnSpaced("📊", v -> showCreatePollDialog());
            rail.addView(btnPoll);
            rail.addView(circleBtnSpaced("📤", v -> showShareAndInviteSheet()));
            rail.addView(circleBtnSpaced("⚙️", v -> showHostSettingsSheet()));
        } else {
            btnSpeaker = circleBtnSpaced("🔊", v -> toggleViewerSpeaker());
            rail.addView(btnSpeaker);
            btnCoHost = circleBtnSpaced("🙋", v -> requestOrLeaveCoHostStage());
            rail.addView(btnCoHost);
            btnQa = circleBtnSpaced("❓", v -> showAskQuestionDialog());
            rail.addView(btnQa);
            rail.addView(circleBtnSpaced("⭐", v -> showSendGiftSheet()));
            rail.addView(circleBtnSpaced("📤", v -> showShareAndInviteSheet()));
            rail.addView(circleBtnSpaced("⚙️", v -> showViewerSettingsSheet()));
        }

        railScroll.addView(rail);
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(
                -2, Ui.dp(this, 360), Gravity.END | Gravity.BOTTOM);
        railLp.setMargins(0, 0, 0, Ui.dp(this, 108));
        hud.addView(railScroll, railLp);

        // 3. BOTTOM LIVE CHAT STREAM + EMOJI REACTION STRIP + COMMENT COMPOSER
        LinearLayout bottomZone = Ui.col(this);
        Ui.pad(bottomZone, 10, 0, 10, 12);

        chatRv = new RecyclerView(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        chatRv.setLayoutManager(lm);
        chatAdapter = new ChatAdapter();
        chatRv.setAdapter(chatAdapter);
        LinearLayout.LayoutParams chatLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 185));
        chatLp.setMargins(0, 0, Ui.dp(this, 52), Ui.dp(this, 6));
        bottomZone.addView(chatRv, chatLp);

        // Quick Reaction Emoji Bar
        LinearLayout emojiRow = Ui.row(this);
        emojiRow.setGravity(Gravity.CENTER_VERTICAL);
        emojiRow.setPadding(0, 0, Ui.dp(this, 52), Ui.dp(this, 6));
        for (final String em : REACTION_EMOJIS) {
            TextView eBtn = Ui.tv(this, em, 18, 0xFFFFFFFF);
            eBtn.setGravity(Gravity.CENTER);
            eBtn.setBackground(Ui.round(this, 0x55000000, 18));
            Ui.pad(eBtn, 9, 4, 9, 4);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
            elp.setMargins(0, 0, Ui.dp(this, 6), 0);
            eBtn.setOnClickListener(v -> sendReaction(em));
            emojiRow.addView(eBtn, elp);
        }
        bottomZone.addView(emojiRow);

        // Comment Composer Row
        bottomComposerBar = Ui.row(this);
        bottomComposerBar.setGravity(Gravity.CENTER_VERTICAL);

        commentInput = new EditText(this);
        commentInput.setHint("Say something nice...");
        commentInput.setHintTextColor(0xAAFFFFFF);
        commentInput.setTextColor(0xFFFFFFFF);
        commentInput.setTextSize(13);
        commentInput.setSingleLine(true);
        commentInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(220)});
        commentInput.setBackground(Ui.round(this, 0x66000000, 22));
        Ui.pad(commentInput, 14, 9, 14, 9);
        bottomComposerBar.addView(commentInput, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView sendBtn = Ui.roundChip(this, "Send", Ui.BLUE, 0xFFFFFFFF, 12);
        Ui.pad(sendBtn, 14, 9, 14, 9);
        sendBtn.setOnClickListener(v -> sendChatMessage());
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-2, -2);
        sbLp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        bottomComposerBar.addView(sendBtn, sbLp);

        if (!isHost) {
            TextView giftQuick = Ui.roundChip(this, "⭐ Gift", 0xFFFF9800, 0xFFFFFFFF, 12);
            Ui.pad(giftQuick, 12, 9, 12, 9);
            giftQuick.setOnClickListener(v -> showSendGiftSheet());
            LinearLayout.LayoutParams gqLp = new LinearLayout.LayoutParams(-2, -2);
            gqLp.setMargins(Ui.dp(this, 6), 0, 0, 0);
            bottomComposerBar.addView(giftQuick, gqLp);
        }

        bottomZone.addView(bottomComposerBar, new LinearLayout.LayoutParams(-1, -2));
        hud.addView(bottomZone, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        return hud;
    }

    private TextView circleBtn(String emoji, int sizeDp, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(emoji);
        t.setTextSize(16);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(this, 0x77000000, sizeDp));
        int px = Ui.dp(this, sizeDp);
        t.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        t.setOnClickListener(click);
        return t;
    }

    private TextView circleBtnSpaced(String emoji, View.OnClickListener click) {
        TextView t = circleBtn(emoji, 38, click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        lp.setMargins(0, 0, 0, Ui.dp(this, 8));
        t.setLayoutParams(lp);
        return t;
    }

    // ==================== PERMISSIONS & STARTING LIVE BROADCAST ====================

    private void ensureLivePermissions(int reqCode) {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), reqCode);
        } else {
            if (reqCode == REQ_LIVE_PERMS) {
                engine.attachCameraPreview(cameraTexture, true);
            } else if (reqCode == REQ_COHOST_PERMS) {
                activateLocalCoHostStreaming();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean camOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_LIVE_PERMS) {
            if (camOk) {
                engine.attachCameraPreview(cameraTexture, true);
            } else {
                fallbackStage.setVisibility(View.VISIBLE);
                stageStatusTv.setText("🎙️ Audio-only mode (grant Camera permission for video)");
            }
        } else if (requestCode == REQ_COHOST_PERMS && camOk) {
            activateLocalCoHostStreaming();
        }
    }

    private void beginCountdownAndStartLive() {
        String rawTitle = titleInput.getText().toString().trim();
        ContentSafetyEngine.Verdict verdict = ContentSafetyEngine.checkText(rawTitle);
        if (verdict.unsafe) {
            Ui.toast(this, verdict.message);
            return;
        }
        preLiveOverlay.setVisibility(View.GONE);
        countdownTv.setVisibility(View.VISIBLE);
        final int[] step = {3};
        countdownTv.setText("3");
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                step[0]--;
                if (step[0] > 0) {
                    countdownTv.setText(String.valueOf(step[0]));
                    uiHandler.postDelayed(this, 850L);
                } else if (step[0] == 0) {
                    countdownTv.setText("🔴 LIVE!");
                    uiHandler.postDelayed(this, 550L);
                } else {
                    countdownTv.setVisibility(View.GONE);
                    startHostBroadcastNow();
                }
            }
        };
        uiHandler.postDelayed(tick, 850L);
    }

    private void startHostBroadcastNow() {
        String me = Fb.uid();
        if (me == null) return;

        hostNameStr = Fb.myName();
        hostPhotoStr = Fb.myPhoto() == null ? "" : Fb.myPhoto();
        String entered = titleInput.getText().toString().trim();
        liveTitleStr = entered.isEmpty() ? (hostNameStr + " is Live 🔴") : entered;
        liveStartMs = System.currentTimeMillis();
        isLiveStarted = true;

        DocumentReference docRef = Fb.db().collection("lives").document();
        liveId = docRef.getId();
        activeViewerIds.add(me);
        viewerNames.put(me, hostNameStr + " (Host)");
        viewerPhotos.put(me, hostPhotoStr);

        // Write ALL canonical + legacy fields so FeedAdapter, HomeFragment, LiveHub, AdminLive & Firestore Rules all match 100%!
        Map<String, Object> doc = new HashMap<>();
        doc.put("hostId", me);
        doc.put("hostName", hostNameStr);
        doc.put("hostPhoto", hostPhotoStr);
        doc.put("hostPhotoUrl", hostPhotoStr);
        doc.put("title", liveTitleStr);
        doc.put("category", selectedCategory);
        doc.put("privacy", privacyFriendsOnly ? "friends" : "public");
        doc.put("status", "live");
        doc.put("viewers", 1);
        doc.put("viewerCount", 1);
        doc.put("peakViewers", 1);
        doc.put("stars", 0);
        doc.put("totalStars", 0);
        doc.put("likesCount", 0);
        doc.put("filterIndex", currentFilterIdx);
        doc.put("cameraPaused", engine.isCameraPaused());
        doc.put("commentsEnabled", commentsEnabled);
        doc.put("slowMode", slowModeEnabled);
        doc.put("giftsEnabled", giftsEnabled);
        doc.put("qaEnabled", qaEnabled);
        doc.put("coHostAllowed", coHostAllowed);
        doc.put("blockedKeywords", blockedKeywordsCsv);
        doc.put("pinnedComment", "");
        doc.put("viewerIds", new ArrayList<>(activeViewerIds));
        doc.put("startedAtMs", liveStartMs);
        doc.put("lastHeartbeatMs", liveStartMs);
        doc.put("startedAt", FieldValue.serverTimestamp());
        doc.put("createdAt", FieldValue.serverTimestamp());

        liveHudOverlay.setVisibility(View.VISIBLE);
        Ui.avatar(this, hostTopAvatar, hostPhotoStr);
        hostTopName.setText(hostNameStr);

        docRef.set(doc).addOnSuccessListener(unused -> {
            setupRtdbFastLane();
            attachRoomListeners();
            // Start real-time camera video + real-time microphone voice streaming + speaker (for Co-Host)!
            engine.startBroadcasting(true);
            engine.startSpeakerPlayback();
            uiHandler.post(durationTicker);
            uiHandler.postDelayed(hostHeartbeatTask, 8000L);
            postSystemChat("🔴 " + hostNameStr + " started a live video & voice broadcast!");
            notifyFollowersGoingLive();
        }).addOnFailureListener(e -> {
            Ui.toast(this, "Could not start live: " + e.getMessage());
            finish();
        });
    }

    private void notifyFollowersGoingLive() {
        if (Fb.me == null || Fb.me.followers == null) return;
        int sent = 0;
        for (String fUid : Fb.me.followers) {
            if (fUid == null || fUid.isEmpty()) continue;
            Notify.send(fUid, "live", "🔴 is LIVE now: " + liveTitleStr, liveId);
            if (++sent >= 40) break;
        }
    }

    // ==================== VIEWER JOINING REAL-TIME VIDEO + VOICE ROOM ====================

    private void joinExistingLiveAsViewer() {
        isLiveStarted = true;
        engine.startSpeakerPlayback();
        setupRtdbFastLane();
        attachRoomListeners();

        // Update `viewers` (100% allowed by deployed firestore.rules!) + post join packet in `chat`!
        viewerCountJoined = true;
        Fb.db().collection("lives").document(liveId)
                .update("viewers", FieldValue.increment(1));

        Map<String, Object> joinPkt = new HashMap<>();
        joinPkt.put("type", "join");
        joinPkt.put("uid", Fb.uid());
        joinPkt.put("name", Fb.myName());
        joinPkt.put("photo", Fb.myPhoto() == null ? "" : Fb.myPhoto());
        joinPkt.put("text", "👋 joined the live");
        joinPkt.put("createdAtMs", System.currentTimeMillis());
        joinPkt.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("lives").document(liveId).collection("chat").add(joinPkt);
    }

    // ==================== DUAL-LANE STREAMING (RTDB FAST LANE + FIRESTORE GUARANTEED LANE) ====================

    private void setupRtdbFastLane() {
        if (liveId == null) return;
        try {
            FirebaseDatabase rtdb = Fb.rtdb();
            if (rtdb == null) return;
            rtdbStreamRef = rtdb.getReference("live_streams").child(liveId);
            if (!isHost) {
                rtdbStreamListener = new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (isFinishing() || isEnded || !snapshot.exists()) return;
                        String vB64 = snapshot.child("v").getValue(String.class);
                        Long vSeq = snapshot.child("vs").getValue(Long.class);
                        if (vB64 != null && !vB64.isEmpty()) {
                            engine.decodeRemoteFrame(vB64, vSeq == null ? 0L : vSeq, false);
                        }
                        String aB64 = snapshot.child("a").getValue(String.class);
                        Long aSeq = snapshot.child("as").getValue(Long.class);
                        if (aB64 != null && !aB64.isEmpty()) {
                            engine.enqueueRemoteAudio(aB64, aSeq == null ? 0L : aSeq, false);
                        }
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) { }
                };
                rtdbStreamRef.addValueEventListener(rtdbStreamListener);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onEncodedVideoFrame(String base64Webp, int width, int height) {
        if (isEnded || !isLiveStarted || liveId == null) return;
        if (isHost) {
            long seq = ++frameSeqOut;
            long now = System.currentTimeMillis();
            if (rtdbStreamRef != null) {
                Map<String, Object> fast = new HashMap<>();
                fast.put("v", base64Webp);
                fast.put("vs", seq);
                rtdbStreamRef.updateChildren(fast);
            }
            // Guaranteed Firestore real-time stream (100% allowed for hostId == request.auth.uid!)
            Map<String, Object> upd = new HashMap<>();
            upd.put("liveFrameB64", base64Webp);
            upd.put("frameSeq", seq);
            upd.put("frameTs", now);
            upd.put("cameraPaused", false);
            upd.put("filterIndex", currentFilterIdx);
            Fb.db().collection("lives").document(liveId).update(upd);
        }
    }

    @Override
    public void onEncodedAudioChunk(String base64Ulaw, int voiceLevel) {
        if (isEnded || !isLiveStarted || liveId == null) return;
        if (isHost) {
            long seq = ++audioSeqOut;
            if (rtdbStreamRef != null) {
                Map<String, Object> fast = new HashMap<>();
                fast.put("a", base64Ulaw);
                fast.put("as", seq);
                rtdbStreamRef.updateChildren(fast);
            }
            Map<String, Object> upd = new HashMap<>();
            upd.put("audioB64", base64Ulaw);
            upd.put("audioSeq", seq);
            upd.put("audioLevel", voiceLevel);
            Fb.db().collection("lives").document(liveId).update(upd);
        }
    }

    @Override
    public void onLocalVoiceLevel(int voiceLevel) {
        if (voiceMeterTv == null) return;
        if (engine.isMicMuted()) {
            voiceMeterTv.setText("🔇 Mic Muted");
            voiceMeterTv.setTextColor(0xFFFF8A80);
        } else {
            String bars = voiceLevel > 45 ? "▂▅▇" : (voiceLevel > 12 ? "▂▅" : "▂");
            voiceMeterTv.setText("🎙️ Live " + bars);
            voiceMeterTv.setTextColor(voiceLevel > 8 ? 0xFF69F0AE : 0xCCFFFFFF);
        }
    }

    @Override
    public void onDecodedRemoteVideo(Bitmap bitmap, boolean isCoHost) {
        if (isFinishing() || isEnded || bitmap == null) return;
        if (isCoHost) {
            coHostCard.setVisibility(View.VISIBLE);
            coHostRemoteImage.setVisibility(View.VISIBLE);
            coHostRemoteImage.setImageBitmap(bitmap);
        } else {
            lastHostVideoUpdateMs = System.currentTimeMillis();
            remoteVideoView.setVisibility(View.VISIBLE);
            remoteVideoView.setImageBitmap(bitmap);
            fallbackStage.setVisibility(View.GONE);
        }
    }

    @Override
    public void onCameraError(String message) {
        if (isHost) {
            fallbackStage.setVisibility(View.VISIBLE);
            stageStatusTv.setText("🎙️ " + message + " (Audio broadcast active)");
        }
    }

    // ==================== FIRESTORE ROOM & CHAT REALTIME LISTENERS ====================

    private void attachRoomListeners() {
        if (liveId == null) return;

        liveDocReg = Fb.db().collection("lives").document(liveId)
                .addSnapshotListener((snap, err) -> {
                    if (isFinishing() || snap == null || !snap.exists()) return;
                    onLiveDocumentSnapshot(snap);
                });

        chatColReg = Fb.db().collection("lives").document(liveId).collection("chat")
                .orderBy("createdAtMs", Query.Direction.ASCENDING)
                .limitToLast(100)
                .addSnapshotListener((snap, err) -> {
                    if (isFinishing() || snap == null) return;
                    onChatSnapshot(snap.getDocuments());
                });
    }

    @SuppressWarnings("unchecked")
    private void onLiveDocumentSnapshot(DocumentSnapshot d) {
        Map<String, Object> m = d.getData();
        if (m == null) return;

        String status = Format.str(m, "status");
        if ("ended".equals(status) && !isHost && !isEnded) {
            isEnded = true;
            showViewerBroadcastEndedDialog();
            return;
        }

        hostId = Format.strOr(m, "hostId", hostId);
        hostNameStr = Format.strOr(m, "hostName", hostNameStr);
        hostPhotoStr = Format.str(m, "hostPhotoUrl");
        if (hostPhotoStr.isEmpty()) hostPhotoStr = Format.str(m, "hostPhoto");
        liveTitleStr = Format.strOr(m, "title", liveTitleStr);

        // Kick check for Viewer
        Object kickedObj = m.get("kickedUserIds");
        if (kickedObj instanceof List) {
            kickedUserIds.clear();
            for (Object o : (List<?>) kickedObj) if (o != null) kickedUserIds.add(String.valueOf(o));
            if (!isHost && kickedUserIds.contains(Fb.uid())) {
                isEnded = true;
                Ui.toast(this, "You were removed from this broadcast");
                finish();
                return;
            }
        }
        Object mutedObj = m.get("mutedUserIds");
        if (mutedObj instanceof List) {
            mutedUserIds.clear();
            for (Object o : (List<?>) mutedObj) if (o != null) mutedUserIds.add(String.valueOf(o));
        }
        Object modObj = m.get("moderatorIds");
        if (modObj instanceof List) {
            moderatorIds.clear();
            for (Object o : (List<?>) modObj) if (o != null) moderatorIds.add(String.valueOf(o));
        }
        Object delObj = m.get("deletedMsgIds");
        if (delObj instanceof List) {
            deletedMsgIds.clear();
            for (Object o : (List<?>) delObj) if (o != null) deletedMsgIds.add(String.valueOf(o));
        }

        // Sync Room Settings
        commentsEnabled = m.get("commentsEnabled") == null || Boolean.TRUE.equals(m.get("commentsEnabled"));
        slowModeEnabled = Boolean.TRUE.equals(m.get("slowMode"));
        giftsEnabled = m.get("giftsEnabled") == null || Boolean.TRUE.equals(m.get("giftsEnabled"));
        qaEnabled = m.get("qaEnabled") == null || Boolean.TRUE.equals(m.get("qaEnabled"));
        coHostAllowed = m.get("coHostAllowed") == null || Boolean.TRUE.equals(m.get("coHostAllowed"));
        blockedKeywordsCsv = Format.str(m, "blockedKeywords");

        commentInput.setEnabled(commentsEnabled || isHost || isMeModerator());
        commentInput.setHint(!commentsEnabled ? "🔇 Host turned off comments"
                : (slowModeEnabled ? "🐢 Slow mode (5s)..." : "Say something nice..."));

        // Header Stats & Timer
        if (!isHost) {
            hostTopName.setText(hostNameStr);
            Ui.avatar(this, hostTopAvatar, hostPhotoStr);
            Ui.avatar(this, stageAvatar, hostPhotoStr);
            long startedMs = Format.longOf(m, "startedAtMs");
            if (startedMs > 0 && liveStartMs == 0L) {
                liveStartMs = startedMs;
                uiHandler.removeCallbacks(durationTicker);
                uiHandler.post(durationTicker);
            }
        }

        int vDoc = Math.max(1, Math.max(Format.intOf(m, "viewers"), Format.intOf(m, "viewerCount")));
        currentViewers = isHost ? Math.max(vDoc, activeViewerIds.size()) : vDoc;
        if (currentViewers > peakViewers) peakViewers = currentViewers;
        viewerCountTv.setText("👁 " + Format.count(currentViewers));

        totalStarsReceived = Math.max(Format.intOf(m, "stars"), Format.intOf(m, "totalStars"));
        hostTopSub.setText("⭐ " + Format.count(totalStarsReceived) + " Stars");

        // Filter Sync
        int remoteFilter = Format.intOf(m, "filterIndex");
        if (!isHost && remoteFilter >= 0 && remoteFilter < FILTER_OVERLAYS.length) {
            currentFilterIdx = remoteFilter;
            filterOverlayView.setBackgroundColor(FILTER_OVERLAYS[currentFilterIdx]);
        }

        // Viewer Decodes Real-Time Host Video + Real-Time Host Audio!
        if (!isHost) {
            boolean hostCamPaused = Boolean.TRUE.equals(m.get("cameraPaused"));
            if (hostCamPaused) {
                remoteVideoView.setVisibility(View.GONE);
                fallbackStage.setVisibility(View.VISIBLE);
                stageStatusTv.setText("⏸ " + hostNameStr + " paused camera · Live voice active 🔊");
            } else {
                String frameB64 = Format.str(m, "liveFrameB64");
                long fSeq = Format.longOf(m, "frameSeq");
                if (!frameB64.isEmpty()) {
                    engine.decodeRemoteFrame(frameB64, fSeq, false);
                } else if (System.currentTimeMillis() - lastHostVideoUpdateMs > 5000L) {
                    fallbackStage.setVisibility(View.VISIBLE);
                    stageStatusTv.setText("🎙️ " + hostNameStr + " is Live");
                }
            }

            String audioB64 = Format.str(m, "audioB64");
            long aSeq = Format.longOf(m, "audioSeq");
            int aLevel = Format.intOf(m, "audioLevel");
            if (!audioB64.isEmpty()) {
                engine.enqueueRemoteAudio(audioB64, aSeq, false);
            }
            if (voiceMeterTv != null) {
                if (engine.isSpeakerMuted()) {
                    voiceMeterTv.setText("🔇 Sound Muted");
                    voiceMeterTv.setTextColor(0xFFFF8A80);
                } else {
                    String bars = aLevel > 40 ? "▂▅▇" : (aLevel > 10 ? "▂▅" : "▂");
                    voiceMeterTv.setText("🔊 Live Voice " + bars);
                    voiceMeterTv.setTextColor(aLevel > 8 ? 0xFF69F0AE : 0xCCFFFFFF);
                }
            }
        }

        // Co-Host Stage State Sync
        String newCoUid = Format.str(m, "coHostUid");
        String newCoName = Format.str(m, "coHostName");
        handleCoHostStateUpdate(newCoUid, newCoName,
                Format.str(m, "coHostFrameB64"), Format.longOf(m, "coHostFrameSeq"),
                Format.str(m, "coHostAudioB64"), Format.longOf(m, "coHostAudioSeq"));

        // Pinned Comment Banner
        String pinned = Format.str(m, "pinnedComment");
        pinnedCommentBox.setVisibility(pinned.isEmpty() ? View.GONE : View.VISIBLE);
        pinnedCommentTv.setText("📌 " + pinned);

        // Stage Spotlight Q&A Card
        String qaSpot = Format.str(m, "spotlightQa");
        spotlightQaCard.setVisibility(qaSpot.isEmpty() ? View.GONE : View.VISIBLE);
        spotlightQaTv.setText(qaSpot);

        // Interactive Poll Sync
        String pId = Format.str(m, "pollId");
        String pQ = Format.str(m, "pollQuestion");
        Object optsObj = m.get("pollOptions");
        if (!pId.isEmpty() && !pQ.isEmpty() && optsObj instanceof List) {
            if (!pId.equals(activePollId)) {
                activePollId = pId;
                myVotedOption = -1;
                activePollVoterChoice.clear();
            }
            activePollQuestion = pQ;
            activePollOptions = new ArrayList<>();
            for (Object o : (List<?>) optsObj) activePollOptions.add(String.valueOf(o));
            renderActivePollCard();
        } else {
            activePollId = "";
            activePollCard.setVisibility(View.GONE);
        }
    }

    private final Set<String> processedPacketIds = new HashSet<>();

    private void onChatSnapshot(List<DocumentSnapshot> docs) {
        List<ChatItem> visibleMessages = new ArrayList<>();
        for (DocumentSnapshot d : docs) {
            Map<String, Object> m = d.getData();
            if (m == null) continue;
            String id = d.getId();
            if (deletedMsgIds.contains(id)) continue;

            String type = Format.strOr(m, "type", "chat");
            String uid = Format.str(m, "uid");
            String name = Format.strOr(m, "name", "Viewer");
            String photo = Format.str(m, "photo");
            String text = Format.str(m, "text");
            boolean isNewPacket = processedPacketIds.add(id);

            if (!uid.isEmpty()) {
                viewerNames.put(uid, name);
                if (!photo.isEmpty()) viewerPhotos.put(uid, photo);
            }

            if ("join".equals(type)) {
                if (!uid.isEmpty() && activeViewerIds.add(uid)) {
                    totalUniqueViewers++;
                }
                visibleMessages.add(new ChatItem(id, uid, name, photo, text, "join"));
            } else if ("leave".equals(type)) {
                activeViewerIds.remove(uid);
            } else if ("reaction".equals(type)) {
                if (isNewPacket) {
                    totalLikes++;
                    if (!uid.equals(Fb.uid())) {
                        spawnFloatingEmoji(text.isEmpty() ? "❤️" : text);
                    }
                }
            } else if ("gift".equals(type)) {
                int stars = Format.intOf(m, "stars");
                if (isNewPacket && stars > 0) {
                    int prev = supporterStars.containsKey(uid) ? supporterStars.get(uid) : 0;
                    supporterStars.put(uid, prev + stars);
                    supporterNames.put(uid, name);
                    spawnFloatingEmoji("⭐");
                    spawnFloatingEmoji("🎉");
                }
                visibleMessages.add(new ChatItem(id, uid, name, photo, text, "gift"));
            } else if ("qa".equals(type)) {
                if (isNewPacket) {
                    qaItems.add(0, new ChatItem(id, uid, name, photo, text, "qa"));
                    if (isHost) Ui.toast(this, "❓ New Q&A question from " + name);
                }
                visibleMessages.add(new ChatItem(id, uid, name, photo, "❓ Asked: " + text, "qa"));
            } else if ("poll_vote".equals(type)) {
                String pId = Format.str(m, "pollId");
                int optIdx = Format.intOf(m, "optionIndex");
                if (!pId.isEmpty() && pId.equals(activePollId) && !uid.isEmpty()) {
                    activePollVoterChoice.put(uid, optIdx);
                    if (isNewPacket) renderActivePollCard();
                }
            } else if ("cohost_req".equals(type)) {
                if (isNewPacket && !uid.isEmpty() && !uid.equals(hostId)) {
                    pendingCoHostRequests.put(uid, new ChatItem(id, uid, name, photo, text, "cohost_req"));
                    if (isHost) {
                        Ui.toast(this, "🙋 " + name + " requested to join your Live!");
                    }
                }
                visibleMessages.add(new ChatItem(id, uid, name, photo, "🙋 requested to join Live", "system"));
            } else if ("cohost_media".equals(type)) {
                // Co-Host real-time camera + voice relay packet!
                if (isNewPacket && uid.equals(activeCoHostUid) && !uid.equals(Fb.uid())) {
                    String cf = Format.str(m, "v");
                    String ca = Format.str(m, "a");
                    long seq = Format.longOf(m, "seq");
                    if (!cf.isEmpty()) engine.decodeRemoteFrame(cf, seq, true);
                    if (!ca.isEmpty()) engine.enqueueRemoteAudio(ca, seq, true);
                    if (isHost && liveId != null) {
                        Map<String, Object> relay = new HashMap<>();
                        if (!cf.isEmpty()) {
                            relay.put("coHostFrameB64", cf);
                            relay.put("coHostFrameSeq", seq);
                        }
                        if (!ca.isEmpty()) {
                            relay.put("coHostAudioB64", ca);
                            relay.put("coHostAudioSeq", seq);
                        }
                        if (!relay.isEmpty()) {
                            Fb.db().collection("lives").document(liveId).update(relay);
                        }
                    }
                }
            } else {
                if (isNewPacket && "chat".equals(type)) totalComments++;
                visibleMessages.add(new ChatItem(id, uid, name, photo, text, type));
            }
        }
        chatAdapter.setItems(visibleMessages);
        if (!visibleMessages.isEmpty()) {
            chatRv.scrollToPosition(visibleMessages.size() - 1);
        }
    }

    // ==================== CO-HOST ("JOIN LIVE" SPLIT SCREEN VIDEO + VOICE) ====================

    private void handleCoHostStateUpdate(String coUid, String coName,
                                         String coFrameB64, long coFrameSeq,
                                         String coAudioB64, long coAudioSeq) {
        activeCoHostUid = coUid == null ? "" : coUid;
        activeCoHostName = coName == null ? "" : coName;
        String me = Fb.uid();

        if (activeCoHostUid.isEmpty()) {
            if (isMeActiveCoHost) {
                stopLocalCoHostStreaming();
                Ui.toast(this, "You left the Co-Host stage");
            }
            coHostCard.setVisibility(View.GONE);
            return;
        }

        coHostCard.setVisibility(View.VISIBLE);
        coHostLabelTv.setText("🤝 " + (activeCoHostName.isEmpty() ? "Co-Host" : activeCoHostName));
        coHostCloseBtn.setVisibility((isHost || activeCoHostUid.equals(me)) ? View.VISIBLE : View.GONE);

        if (activeCoHostUid.equals(me)) {
            if (!isMeActiveCoHost) {
                isMeActiveCoHost = true;
                Ui.toast(this, "🎉 Host accepted! Starting your Co-Host camera & mic...");
                ensureLivePermissions(REQ_COHOST_PERMS);
            }
        } else {
            if (isMeActiveCoHost) stopLocalCoHostStreaming();
            coHostLocalTexture.setVisibility(View.GONE);
            coHostRemoteImage.setVisibility(View.VISIBLE);
            if (!isHost) {
                if (!coFrameB64.isEmpty()) engine.decodeRemoteFrame(coFrameB64, coFrameSeq, true);
                if (!coAudioB64.isEmpty()) engine.enqueueRemoteAudio(coAudioB64, coAudioSeq, true);
            }
        }
    }

    private void activateLocalCoHostStreaming() {
        if (!isMeActiveCoHost || isFinishing()) return;
        coHostRemoteImage.setVisibility(View.GONE);
        coHostLocalTexture.setVisibility(View.VISIBLE);
        if (coHostLocalEngine == null) {
            coHostLocalEngine = new LiveStreamEngine(this, new LiveStreamEngine.Listener() {
                @Override
                public void onEncodedVideoFrame(String base64Webp, int width, int height) {
                    pushCoHostMediaPacket(base64Webp, null);
                }
                @Override
                public void onEncodedAudioChunk(String base64Ulaw, int voiceLevel) {
                    pushCoHostMediaPacket(null, base64Ulaw);
                }
                @Override
                public void onLocalVoiceLevel(int voiceLevel) { }
                @Override
                public void onDecodedRemoteVideo(Bitmap bitmap, boolean isCoHost) { }
                @Override
                public void onCameraError(String message) { }
            });
            coHostLocalEngine.setStreamQuality(LiveStreamEngine.QUALITY_DATA_SAVER);
        }
        coHostLocalEngine.attachCameraPreview(coHostLocalTexture, true);
        coHostLocalEngine.startBroadcasting(true);
    }

    private void pushCoHostMediaPacket(@Nullable String vB64, @Nullable String aB64) {
        if (!isMeActiveCoHost || liveId == null || isEnded) return;
        long now = System.currentTimeMillis();
        if (vB64 != null && now - lastCoHostMediaPushMs < 650L) return;
        if (vB64 != null) lastCoHostMediaPushMs = now;

        Map<String, Object> pkt = new HashMap<>();
        pkt.put("type", "cohost_media");
        pkt.put("uid", Fb.uid());
        pkt.put("name", Fb.myName());
        pkt.put("seq", ++coHostSeqOut);
        if (vB64 != null) pkt.put("v", vB64);
        if (aB64 != null) pkt.put("a", aB64);
        pkt.put("createdAtMs", now);
        Fb.db().collection("lives").document(liveId).collection("chat").add(pkt);
    }

    private void stopLocalCoHostStreaming() {
        isMeActiveCoHost = false;
        if (coHostLocalEngine != null) {
            coHostLocalEngine.releaseAll();
            coHostLocalEngine = null;
        }
        coHostLocalTexture.setVisibility(View.GONE);
    }

    private void requestOrLeaveCoHostStage() {
        if (isMeActiveCoHost) {
            removeActiveCoHost();
            return;
        }
        if (!coHostAllowed) {
            Ui.toast(this, "Host has turned off Co-Host requests");
            return;
        }
        Map<String, Object> req = new HashMap<>();
        req.put("type", "cohost_req");
        req.put("uid", Fb.uid());
        req.put("name", Fb.myName());
        req.put("photo", Fb.myPhoto() == null ? "" : Fb.myPhoto());
        req.put("text", "wants to join on camera");
        req.put("createdAtMs", System.currentTimeMillis());
        Fb.db().collection("lives").document(liveId).collection("chat").add(req);
        Ui.toast(this, "🙋 Co-Host join request sent to " + hostNameStr + "!");
    }

    private void showCoHostManagerSheet() {
        List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        if (!activeCoHostUid.isEmpty()) {
            labels.add("❌ Remove current Co-Host (" + activeCoHostName + ")");
            actions.add(this::removeActiveCoHost);
        }
        for (final ChatItem req : pendingCoHostRequests.values()) {
            labels.add("✅ Accept " + req.name + " onto Split-Screen Live");
            actions.add(() -> {
                pendingCoHostRequests.remove(req.uid);
                Map<String, Object> upd = new HashMap<>();
                upd.put("coHostUid", req.uid);
                upd.put("coHostName", req.name);
                Fb.db().collection("lives").document(liveId).update(upd);
                postSystemChat("🤝 " + req.name + " joined as Co-Host!");
            });
        }
        labels.add(coHostAllowed ? "🔒 Turn OFF Co-Host Requests" : "🔓 Turn ON Co-Host Requests");
        actions.add(() -> {
            coHostAllowed = !coHostAllowed;
            if (liveId != null) Fb.db().collection("lives").document(liveId).update("coHostAllowed", coHostAllowed);
            Ui.toast(this, coHostAllowed ? "Co-Host requests enabled" : "Co-Host requests disabled");
        });

        Ui.choose(this, "🙋 Co-Host Split-Screen Studio",
                labels.toArray(new String[0]), which -> actions.get(which).run());
    }

    private void removeActiveCoHost() {
        if (isHost && liveId != null) {
            Map<String, Object> upd = new HashMap<>();
            upd.put("coHostUid", "");
            upd.put("coHostName", "");
            upd.put("coHostFrameB64", "");
            upd.put("coHostAudioB64", "");
            Fb.db().collection("lives").document(liveId).update(upd);
        } else if (isMeActiveCoHost) {
            stopLocalCoHostStreaming();
            coHostCard.setVisibility(View.GONE);
            postSystemChat("👋 " + Fb.myName() + " left the Co-Host stage");
        }
    }

    // ==================== INTERACTIVE LIVE POLLS & Q&A STAGE SPOTLIGHT ====================

    private void showCreatePollDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 12, 18, 8);
        final EditText qEt = new EditText(this);
        qEt.setHint("Ask viewers a poll question...");
        box.addView(qEt);

        final EditText o1 = new EditText(this);
        o1.setHint("Option 1 (e.g. Yes 🔥)");
        box.addView(o1);

        final EditText o2 = new EditText(this);
        o2.setHint("Option 2 (e.g. Next topic 💬)");
        box.addView(o2);

        final EditText o3 = new EditText(this);
        o3.setHint("Option 3 (optional)");
        box.addView(o3);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("📊 Launch Live Poll");
        b.setView(box);
        b.setPositiveButton("Launch Poll", (d, w) -> {
            String q = qEt.getText().toString().trim();
            List<String> opts = new ArrayList<>();
            if (!o1.getText().toString().trim().isEmpty()) opts.add(o1.getText().toString().trim());
            if (!o2.getText().toString().trim().isEmpty()) opts.add(o2.getText().toString().trim());
            if (!o3.getText().toString().trim().isEmpty()) opts.add(o3.getText().toString().trim());
            if (q.isEmpty() || opts.size() < 2) {
                Ui.toast(this, "Enter a question and at least 2 options");
                return;
            }
            String pid = "poll_" + System.currentTimeMillis();
            Map<String, Object> upd = new HashMap<>();
            upd.put("pollId", pid);
            upd.put("pollQuestion", q);
            upd.put("pollOptions", opts);
            Fb.db().collection("lives").document(liveId).update(upd);
            postSystemChat("📊 Host started a Live Poll: " + q);
        });
        if (!activePollId.isEmpty()) {
            b.setNeutralButton("End Current Poll", (d, w) -> {
                Map<String, Object> upd = new HashMap<>();
                upd.put("pollId", "");
                upd.put("pollQuestion", "");
                Fb.db().collection("lives").document(liveId).update(upd);
            });
        }
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void renderActivePollCard() {
        activePollCard.removeAllViews();
        if (activePollId.isEmpty() || activePollOptions.isEmpty()) {
            activePollCard.setVisibility(View.GONE);
            return;
        }
        activePollCard.setVisibility(View.VISIBLE);

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tvBold(this, "📊 " + activePollQuestion, 12, 0xFFFFFFFF),
                new LinearLayout.LayoutParams(0, -2, 1f));
        if (isHost) {
            TextView endP = Ui.tvBold(this, "End ✕", 11, 0xFFFF8A80);
            endP.setOnClickListener(v -> {
                Map<String, Object> upd = new HashMap<>();
                upd.put("pollId", "");
                upd.put("pollQuestion", "");
                Fb.db().collection("lives").document(liveId).update(upd);
            });
            head.addView(endP);
        }
        activePollCard.addView(head);

        int totalVotes = activePollVoterChoice.size();
        int[] counts = new int[activePollOptions.size()];
        for (Integer c : activePollVoterChoice.values()) {
            if (c != null && c >= 0 && c < counts.length) counts[c]++;
        }

        for (int i = 0; i < activePollOptions.size(); i++) {
            final int idx = i;
            int c = counts[i];
            int pct = totalVotes == 0 ? 0 : Math.round((c * 100f) / totalVotes);
            boolean picked = (myVotedOption == i)
                    || (activePollVoterChoice.containsKey(Fb.uid()) && activePollVoterChoice.get(Fb.uid()) == i);

            TextView row = Ui.tvBold(this,
                    (picked ? "✓ " : "") + activePollOptions.get(i) + "  ·  " + pct + "% (" + c + ")",
                    11, 0xFFFFFFFF);
            row.setBackground(Ui.round(this, picked ? 0xCC1877F2 : 0x33FFFFFF, 10));
            Ui.pad(row, 10, 6, 10, 6);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, Ui.dp(this, 5), 0, 0);
            row.setOnClickListener(v -> voteInPoll(idx));
            activePollCard.addView(row, rlp);
        }
    }

    private void voteInPoll(int idx) {
        if (activePollId.isEmpty() || liveId == null) return;
        myVotedOption = idx;
        activePollVoterChoice.put(Fb.uid(), idx);
        renderActivePollCard();

        Map<String, Object> vote = new HashMap<>();
        vote.put("type", "poll_vote");
        vote.put("pollId", activePollId);
        vote.put("optionIndex", idx);
        vote.put("uid", Fb.uid());
        vote.put("name", Fb.myName());
        vote.put("createdAtMs", System.currentTimeMillis());
        Fb.db().collection("lives").document(liveId).collection("chat").add(vote);
    }

    private void showAskQuestionDialog() {
        if (!qaEnabled) {
            Ui.toast(this, "Q&A box is currently closed by the host");
            return;
        }
        final EditText et = new EditText(this);
        et.setHint("Type your question for " + hostNameStr + "...");
        LinearLayout wrap = Ui.col(this);
        Ui.pad(wrap, 18, 12, 18, 8);
        wrap.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("❓ Ask Host a Question")
                .setView(wrap)
                .setPositiveButton("Submit Question", (d, w) -> {
                    String q = et.getText().toString().trim();
                    if (q.isEmpty()) return;
                    if (ContentSafetyEngine.checkText(q).unsafe) {
                        Ui.toast(this, "Question blocked by safety filter");
                        return;
                    }
                    Map<String, Object> pkt = new HashMap<>();
                    pkt.put("type", "qa");
                    pkt.put("uid", Fb.uid());
                    pkt.put("name", Fb.myName());
                    pkt.put("photo", Fb.myPhoto() == null ? "" : Fb.myPhoto());
                    pkt.put("text", q);
                    pkt.put("createdAtMs", System.currentTimeMillis());
                    Fb.db().collection("lives").document(liveId).collection("chat").add(pkt);
                    Ui.toast(this, "❓ Question sent to Host!");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showQaDrawerSheet() {
        if (qaItems.isEmpty()) {
            Ui.toast(this, "No viewer Q&A questions yet — viewers can tap ❓ to ask!");
            return;
        }
        List<String> labels = new ArrayList<>();
        for (ChatItem item : qaItems) {
            labels.add("🎤 Spotlight @" + item.name + ": " + item.text);
        }
        labels.add("🧹 Clear Current Stage Spotlight");
        Ui.choose(this, "❓ Viewer Q&A Questions (" + qaItems.size() + ")",
                labels.toArray(new String[0]), which -> {
                    if (which == qaItems.size()) {
                        clearSpotlightQa();
                    } else {
                        ChatItem picked = qaItems.get(which);
                        String banner = "❓ Q&A · @" + picked.name + ": " + picked.text;
                        Fb.db().collection("lives").document(liveId).update("spotlightQa", banner);
                    }
                });
    }

    private void clearSpotlightQa() {
        if (liveId != null) Fb.db().collection("lives").document(liveId).update("spotlightQa", "");
    }

    // ==================== CHAT, REACTIONS, STAR GIFTS & MODERATION ====================

    private void sendChatMessage() {
        if (liveId == null || isEnded) return;
        String me = Fb.uid();
        if (mutedUserIds.contains(me)) {
            Ui.toast(this, "🔇 You are muted in this live chat");
            return;
        }
        if (!commentsEnabled && !isHost && !isMeModerator()) {
            Ui.toast(this, "Comments are turned off");
            return;
        }
        if (slowModeEnabled && !isHost && !isMeModerator()) {
            long elapsed = System.currentTimeMillis() - lastCommentSentMs;
            if (elapsed < 5000L) {
                Ui.toast(this, "🐢 Slow mode: wait " + ((5000L - elapsed) / 1000L + 1) + "s");
                return;
            }
        }
        String txt = commentInput.getText().toString().trim();
        if (txt.isEmpty()) return;

        ContentSafetyEngine.Verdict v = ContentSafetyEngine.checkText(txt);
        if (v.unsafe) {
            Ui.toast(this, v.message);
            return;
        }
        if (matchesBlockedKeyword(txt) && !isHost) {
            Ui.toast(this, "Message blocked by host's keyword filter");
            return;
        }

        lastCommentSentMs = System.currentTimeMillis();
        commentInput.setText("");

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "chat");
        msg.put("uid", me);
        msg.put("name", Fb.myName());
        msg.put("photo", Fb.myPhoto() == null ? "" : Fb.myPhoto());
        msg.put("text", txt);
        msg.put("isHost", isHost);
        msg.put("isMod", isMeModerator());
        msg.put("createdAtMs", lastCommentSentMs);
        msg.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("lives").document(liveId).collection("chat").add(msg);
    }

    private boolean matchesBlockedKeyword(String text) {
        if (blockedKeywordsCsv == null || blockedKeywordsCsv.trim().isEmpty()) return false;
        String low = text.toLowerCase(Locale.US);
        for (String w : blockedKeywordsCsv.split(",")) {
            String kw = w.trim().toLowerCase(Locale.US);
            if (!kw.isEmpty() && low.contains(kw)) return true;
        }
        return false;
    }

    private boolean isMeModerator() {
        return moderatorIds.contains(Fb.uid());
    }

    private void postSystemChat(String text) {
        if (liveId == null) return;
        Map<String, Object> sys = new HashMap<>();
        sys.put("type", "system");
        sys.put("uid", Fb.uid());
        sys.put("name", Fb.myName());
        sys.put("text", text);
        sys.put("createdAtMs", System.currentTimeMillis());
        Fb.db().collection("lives").document(liveId).collection("chat").add(sys);
    }

    private void sendReaction(String emoji) {
        spawnFloatingEmoji(emoji);
        if (liveId == null || isEnded) return;
        Map<String, Object> r = new HashMap<>();
        r.put("type", "reaction");
        r.put("uid", Fb.uid());
        r.put("name", Fb.myName());
        r.put("text", emoji);
        r.put("createdAtMs", System.currentTimeMillis());
        Fb.db().collection("lives").document(liveId).collection("chat").add(r);
    }

    private void spawnFloatingEmoji(String emoji) {
        if (floatingHeartsLayer == null || isFinishing()) return;
        final TextView tv = new TextView(this);
        tv.setText(emoji);
        tv.setTextSize(26);
        int startX = Math.max(40, floatingHeartsLayer.getWidth() - Ui.dp(this, 86))
                + rng.nextInt(Ui.dp(this, 36)) - Ui.dp(this, 18);
        int startY = Math.max(200, floatingHeartsLayer.getHeight() - Ui.dp(this, 130));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = Math.max(16, startX);
        lp.topMargin = startY;
        floatingHeartsLayer.addView(tv, lp);

        ObjectAnimator up = ObjectAnimator.ofFloat(tv, "translationY", 0f, -Ui.dp(this, 270));
        ObjectAnimator drift = ObjectAnimator.ofFloat(tv, "translationX", 0f, rng.nextInt(90) - 45);
        ObjectAnimator fade = ObjectAnimator.ofFloat(tv, "alpha", 1f, 0f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(up, drift, fade);
        set.setDuration(1900L);
        set.setInterpolator(new DecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                floatingHeartsLayer.removeView(tv);
            }
        });
        set.start();
    }

    private void showSendGiftSheet() {
        if (!giftsEnabled) {
            Ui.toast(this, "Host turned off Star gifts for this stream");
            return;
        }
        final int[] amounts = {10, 50, 100, 500};
        final String[] names = {
                "⭐ 10 Stars — Cheer",
                "🌟 50 Stars — Super Heart",
                "💫 100 Stars — Spotlight Boost",
                "👑 500 Stars — Crown Supporter"
        };
        Ui.choose(this, "⭐ Send Community Stars to " + hostNameStr, names, which -> {
            int stars = amounts[which];
            // Matches deployed firestore.rules: `senderId == request.auth.uid` & `lives/{lid}` `stars`!
            Fb.db().collection("lives").document(liveId)
                    .update("stars", FieldValue.increment(stars));

            Map<String, Object> giftDoc = new HashMap<>();
            giftDoc.put("senderId", Fb.uid());
            giftDoc.put("senderName", Fb.myName());
            giftDoc.put("toUid", hostId);
            giftDoc.put("liveId", liveId);
            giftDoc.put("stars", stars);
            giftDoc.put("createdAt", FieldValue.serverTimestamp());
            Fb.db().collection("gifts").add(giftDoc);

            Map<String, Object> chatGift = new HashMap<>();
            chatGift.put("type", "gift");
            chatGift.put("uid", Fb.uid());
            chatGift.put("name", Fb.myName());
            chatGift.put("photo", Fb.myPhoto() == null ? "" : Fb.myPhoto());
            chatGift.put("stars", stars);
            chatGift.put("text", "sent ⭐ " + stars + " Stars!");
            chatGift.put("createdAtMs", System.currentTimeMillis());
            Fb.db().collection("lives").document(liveId).collection("chat").add(chatGift);

            spawnFloatingEmoji("⭐");
            Ui.toast(this, "Sent ⭐ " + stars + " Stars to " + hostNameStr + "!");
        });
    }

    private void onChatItemClicked(ChatItem item) {
        if (item == null || item.uid.isEmpty()) return;
        List<String> opts = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();

        opts.add("👤 View " + item.name + "'s Profile");
        acts.add(() -> {
            Intent p = new Intent(this, ProfileActivity.class);
            p.putExtra("uid", item.uid);
            startActivity(p);
        });

        if (isHost || isMeModerator()) {
            opts.add("📌 Pin this comment");
            acts.add(() -> updatePinnedComment(item.name + ": " + item.text));
        }

        if (isHost) {
            opts.add("🗑 Hide / Delete comment");
            acts.add(() -> {
                deletedMsgIds.add(item.id);
                Fb.db().collection("lives").document(liveId)
                        .update("deletedMsgIds", new ArrayList<>(deletedMsgIds));
            });

            if (!item.uid.equals(Fb.uid())) {
                boolean muted = mutedUserIds.contains(item.uid);
                opts.add(muted ? "🔊 Unmute " + item.name : "🔇 Mute " + item.name + " in Chat");
                acts.add(() -> {
                    if (muted) mutedUserIds.remove(item.uid);
                    else mutedUserIds.add(item.uid);
                    Fb.db().collection("lives").document(liveId)
                            .update("mutedUserIds", new ArrayList<>(mutedUserIds));
                    Ui.toast(this, muted ? "Unmuted " + item.name : "Muted " + item.name);
                });

                boolean isMod = moderatorIds.contains(item.uid);
                opts.add(isMod ? "🛡 Remove Live Moderator" : "🛡 Make " + item.name + " Live Moderator");
                acts.add(() -> {
                    if (isMod) moderatorIds.remove(item.uid);
                    else moderatorIds.add(item.uid);
                    Fb.db().collection("lives").document(liveId)
                            .update("moderatorIds", new ArrayList<>(moderatorIds));
                    postSystemChat(isMod ? ("🛡 " + item.name + " is no longer moderator")
                            : ("🛡 " + item.name + " is now a Live Moderator!"));
                });

                opts.add("🤝 Invite " + item.name + " as Split-Screen Co-Host");
                acts.add(() -> {
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("coHostUid", item.uid);
                    upd.put("coHostName", item.name);
                    Fb.db().collection("lives").document(liveId).update(upd);
                });

                opts.add("🚫 Remove / Kick " + item.name + " from Live");
                acts.add(() -> {
                    kickedUserIds.add(item.uid);
                    Fb.db().collection("lives").document(liveId)
                            .update("kickedUserIds", new ArrayList<>(kickedUserIds));
                    Ui.toast(this, "Removed " + item.name + " from broadcast");
                });
            }
        }

        Ui.choose(this, item.name, opts.toArray(new String[0]), w -> acts.get(w).run());
    }

    private void updatePinnedComment(String text) {
        if (liveId == null) return;
        if (isHost) {
            Fb.db().collection("lives").document(liveId).update("pinnedComment", text);
        } else {
            postSystemChat("📌 Moderator pinned: " + text);
        }
    }

    // ==================== COMPLETE STUDIO CONTROLS & SETTINGS SHEETS ====================

    private void toggleHostMic() {
        boolean nextMuted = !engine.isMicMuted();
        engine.setMicMuted(nextMuted);
        if (btnMic != null) btnMic.setText(nextMuted ? "🔇" : "🎙️");
        Ui.toast(this, nextMuted ? "🔇 Microphone muted" : "🎙️ Microphone live");
    }

    private void toggleHostCameraPause() {
        boolean nextPaused = !engine.isCameraPaused();
        engine.setCameraPaused(nextPaused);
        cameraTexture.setVisibility(nextPaused ? View.GONE : View.VISIBLE);
        fallbackStage.setVisibility(nextPaused ? View.VISIBLE : View.GONE);
        if (btnVideoPause != null) btnVideoPause.setText(nextPaused ? "🚫" : "📹");
        if (liveId != null && isLiveStarted) {
            Fb.db().collection("lives").document(liveId).update("cameraPaused", nextPaused);
        }
        Ui.toast(this, nextPaused ? "⏸ Camera paused (Audio still live)" : "▶️ Camera video resumed");
    }

    private void toggleViewerSpeaker() {
        boolean nextMuted = !engine.isSpeakerMuted();
        engine.setSpeakerMuted(nextMuted);
        if (btnSpeaker != null) btnSpeaker.setText(nextMuted ? "🔇" : "🔊");
        Ui.toast(this, nextMuted ? "🔇 Stream sound muted" : "🔊 Stream sound ON");
    }

    private void toggleRingLightOrTorch() {
        if (engine.isLensFront()) {
            ringLightOn = !ringLightOn;
            ringLightOverlay.setVisibility(ringLightOn ? View.VISIBLE : View.GONE);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = ringLightOn ? 1.0f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
            Ui.toast(this, ringLightOn ? "💡 Front Screen Ring-Light ON" : "Ring-Light OFF");
        } else {
            boolean on = engine.toggleTorch();
            Ui.toast(this, on ? "🔦 Rear Flash Torch ON" : "Rear Torch OFF");
        }
    }

    private void cycleVisualFilter() {
        currentFilterIdx = (currentFilterIdx + 1) % FILTER_NAMES.length;
        filterOverlayView.setBackgroundColor(FILTER_OVERLAYS[currentFilterIdx]);
        if (isHost && isLiveStarted && liveId != null) {
            Fb.db().collection("lives").document(liveId).update("filterIndex", currentFilterIdx);
        }
        Ui.toast(this, "Filter: " + FILTER_NAMES[currentFilterIdx]);
    }

    private void cycleStreamQuality() {
        int next = (engine.getStreamQuality() + 1) % 3;
        engine.setStreamQuality(next);
        String lbl = engine.getQualityLabel();
        if (qualityStudioChip != null) qualityStudioChip.setText("⚡ " + lbl);
        Ui.toast(this, "Stream Quality: " + lbl);
    }

    private void toggleCleanViewMode(boolean hide) {
        cleanViewMode = hide;
        liveHudOverlay.setVisibility(hide ? View.GONE : View.VISIBLE);
        restoreHudBtn.setVisibility(hide ? View.VISIBLE : View.GONE);
    }

    private void showHostSettingsSheet() {
        String[] opts = {
                "✏️ Edit Live Broadcast Title",
                "🎬 Stream Quality (" + engine.getQualityLabel() + ")",
                engine.isMicMuted() ? "🎙️ Unmute Microphone" : "🔇 Mute Microphone",
                engine.isCameraPaused() ? "▶️ Resume Camera Video" : "⏸ Pause Camera (Audio-Only Shield)",
                engine.isMirrorFront() ? "🪞 Front Mirror: ON (Tap to flip)" : "🪞 Front Mirror: OFF (Tap to flip)",
                ringLightOn ? "💡 Front Ring-Light: ON" : "💡 Front Ring-Light: OFF",
                engine.isBeautyBoost() ? "✨ Studio Brightness Boost: ON" : "✨ Studio Brightness Boost: OFF",
                "🎨 Studio Color Filter (" + FILTER_NAMES[currentFilterIdx] + ")",
                commentsEnabled ? "💬 Turn OFF Live Comments" : "💬 Turn ON Live Comments",
                slowModeEnabled ? "🐢 Disable Slow Mode (5s)" : "🐢 Enable Slow Mode (5s)",
                "🛡️ Set Blocked Chat Keywords" + (blockedKeywordsCsv.isEmpty() ? "" : " (Active)"),
                giftsEnabled ? "⭐ Turn OFF Star Gifts" : "⭐ Turn ON Star Gifts",
                qaEnabled ? "❓ Turn OFF Q&A Box" : "❓ Turn ON Q&A Box",
                coHostAllowed ? "🙋 Turn OFF Co-Host Requests" : "🙋 Turn ON Co-Host Requests"
        };

        Ui.choose(this, "⚙️ Live Studio Settings & Controls", opts, which -> {
            switch (which) {
                case 0: promptEditLiveTitle(); break;
                case 1: cycleStreamQuality(); break;
                case 2: toggleHostMic(); break;
                case 3: toggleHostCameraPause(); break;
                case 4:
                    engine.setMirrorFront(!engine.isMirrorFront());
                    Ui.toast(this, engine.isMirrorFront() ? "Mirror ON" : "Mirror OFF");
                    break;
                case 5: toggleRingLightOrTorch(); break;
                case 6:
                    boolean b = engine.toggleBeautyBoost();
                    Ui.toast(this, b ? "✨ Brightness Boost ON" : "Brightness normal");
                    break;
                case 7: cycleVisualFilter(); break;
                case 8:
                    commentsEnabled = !commentsEnabled;
                    syncHostSetting("commentsEnabled", commentsEnabled);
                    break;
                case 9:
                    slowModeEnabled = !slowModeEnabled;
                    syncHostSetting("slowMode", slowModeEnabled);
                    break;
                case 10: promptBlockedKeywords(); break;
                case 11:
                    giftsEnabled = !giftsEnabled;
                    syncHostSetting("giftsEnabled", giftsEnabled);
                    break;
                case 12:
                    qaEnabled = !qaEnabled;
                    syncHostSetting("qaEnabled", qaEnabled);
                    break;
                case 13:
                    coHostAllowed = !coHostAllowed;
                    syncHostSetting("coHostAllowed", coHostAllowed);
                    break;
            }
        });
    }

    private void syncHostSetting(String field, Object value) {
        if (isHost && isLiveStarted && liveId != null) {
            Fb.db().collection("lives").document(liveId).update(field, value);
        }
        Ui.toast(this, "Updated " + field);
    }

    private void promptEditLiveTitle() {
        final EditText et = new EditText(this);
        et.setText(isLiveStarted ? liveTitleStr : titleInput.getText().toString());
        LinearLayout w = Ui.col(this);
        Ui.pad(w, 18, 12, 18, 8);
        w.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("✏️ Broadcast Title")
                .setView(w)
                .setPositiveButton("Save", (d, x) -> {
                    String t = et.getText().toString().trim();
                    if (t.isEmpty() || ContentSafetyEngine.checkText(t).unsafe) return;
                    liveTitleStr = t;
                    titleInput.setText(t);
                    if (isLiveStarted && liveId != null) {
                        Fb.db().collection("lives").document(liveId).update("title", t);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptBlockedKeywords() {
        final EditText et = new EditText(this);
        et.setHint("Comma-separated words (e.g. spam, link, fake)");
        et.setText(blockedKeywordsCsv);
        LinearLayout w = Ui.col(this);
        Ui.pad(w, 18, 12, 18, 8);
        w.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("🛡️ Chat Keyword Filter")
                .setView(w)
                .setPositiveButton("Save Filter", (d, x) -> {
                    blockedKeywordsCsv = et.getText().toString().trim();
                    syncHostSetting("blockedKeywords", blockedKeywordsCsv);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showViewerSettingsSheet() {
        boolean boosted = engine.getSpeakerGain() > 1.05f;
        String[] opts = {
                engine.isSpeakerMuted() ? "🔊 Unmute Live Audio" : "🔇 Mute Live Audio",
                boosted ? "🔉 Normal Speaker Volume (100%)" : "📢 Speaker Volume Boost (150%)",
                fitVideoCenter ? "📱 Fill Fullscreen Video (Crop)" : "🖼 Fit Complete Video Frame",
                "👁 Enter Clean View Mode (Hide Chat & HUD)",
                "📤 Share or Invite Friends to Live",
                "🚩 Report Live Broadcast"
        };
        Ui.choose(this, "⚙️ Viewer Controls & Settings", opts, which -> {
            switch (which) {
                case 0: toggleViewerSpeaker(); break;
                case 1:
                    engine.setSpeakerGain(boosted ? 1.0f : 1.5f);
                    Ui.toast(this, boosted ? "Normal volume (100%)" : "📢 Volume Boost ON (150%)");
                    break;
                case 2:
                    fitVideoCenter = !fitVideoCenter;
                    remoteVideoView.setScaleType(fitVideoCenter
                            ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
                    break;
                case 3: toggleCleanViewMode(true); break;
                case 4: showShareAndInviteSheet(); break;
                case 5: reportLiveStream(); break;
            }
        });
    }

    private void showViewersAndLeaderboardSheet() {
        StringBuilder sb = new StringBuilder();
        sb.append("👁 Active Viewers: ").append(currentViewers)
          .append("   ·   Peak: ").append(peakViewers)
          .append("\n⭐ Total Stars: ").append(totalStarsReceived).append("\n\n");

        if (!supporterStars.isEmpty()) {
            sb.append("🏆 TOP STAR SUPPORTERS:\n");
            List<Map.Entry<String, Integer>> list = new ArrayList<>(supporterStars.entrySet());
            Collections.sort(list, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
            int rank = 1;
            for (Map.Entry<String, Integer> e : list) {
                sb.append(rank++).append(". ")
                  .append(supporterNames.getOrDefault(e.getKey(), "Supporter"))
                  .append(" — ⭐ ").append(e.getValue()).append("\n");
                if (rank > 5) break;
            }
            sb.append("\n");
        }

        sb.append("👥 RECENT PARTICIPANTS:\n");
        int shown = 0;
        for (Map.Entry<String, String> e : viewerNames.entrySet()) {
            sb.append("• ").append(e.getValue());
            if (moderatorIds.contains(e.getKey())) sb.append(" 🛡");
            sb.append("\n");
            if (++shown >= 12) break;
        }

        new AlertDialog.Builder(this)
                .setTitle("🔴 Live Audience & Leaderboard")
                .setMessage(sb.toString().trim())
                .setPositiveButton("Close", null)
                .show();
    }

    private void showShareAndInviteSheet() {
        String[] opts = {
                "🔔 Invite My Followers Inside friendbook",
                "📝 Share 'Watch Live Now' Card to Feed",
                "📲 Share Live Invite via Other Apps"
        };
        Ui.choose(this, "📤 Share & Invite to Live", opts, which -> {
            if (which == 0) {
                if (Fb.me == null || Fb.me.followers == null || Fb.me.followers.isEmpty()) {
                    Ui.toast(this, "No followers to invite yet — share to Feed instead!");
                    return;
                }
                int n = 0;
                for (String uid : Fb.me.followers) {
                    Notify.send(uid, "live_invite", "🔴 invited you to watch a Live broadcast: " + liveTitleStr, liveId);
                    if (++n >= 30) break;
                }
                Ui.toast(this, "🔔 Invited " + n + " friend(s) to join Live!");
            } else if (which == 1) {
                Map<String, Object> post = new HashMap<>();
                post.put("authorId", Fb.uid());
                post.put("authorName", Fb.myName());
                post.put("authorPhotoUrl", Fb.myPhoto() == null ? "" : Fb.myPhoto());
                post.put("text", "🔴 Watching LIVE right now: " + liveTitleStr + " — Join us in friendbook Live!");
                post.put("bgColor", "#FFE41E3F");
                post.put("privacy", "public");
                post.put("likesCount", 0);
                post.put("commentsCount", 0);
                post.put("sharesCount", 0);
                post.put("createdAt", FieldValue.serverTimestamp());
                Fb.db().collection("posts").add(post);
                Ui.toast(this, "✅ Shared Live invite to Home Feed!");
            } else {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("text/plain");
                s.putExtra(Intent.EXTRA_TEXT, "🔴 Watch " + hostNameStr + " LIVE on friendbook: " + liveTitleStr);
                startActivity(Intent.createChooser(s, "Share Live"));
            }
        });
    }

    private void followHostNow() {
        String me = Fb.uid();
        if (me == null || hostId == null || hostId.equals(me)) return;
        Fb.db().collection("users").document(hostId).update("followers", FieldValue.arrayUnion(me));
        Fb.db().collection("users").document(me).update("following", FieldValue.arrayUnion(hostId));
        if (followHostBtn != null) {
            followHostBtn.setText("✓ Following");
            followHostBtn.setBackground(Ui.round(this, 0x55FFFFFF, 14));
        }
        Notify.send(hostId, "follow", "started following you from your Live broadcast");
        postSystemChat("❤️ " + Fb.myName() + " followed " + hostNameStr + "!");
    }

    private void reportLiveStream() {
        String[] reasons = {"Inappropriate / Nudity", "Harassment or Bullying", "Violence / Harm", "Spam or Scam"};
        Ui.choose(this, "🚩 Report Live Broadcast", reasons, which -> {
            Map<String, Object> rep = new HashMap<>();
            rep.put("targetType", "live");
            rep.put("targetId", liveId);
            rep.put("targetOwnerId", hostId);
            rep.put("reporterId", Fb.uid());
            rep.put("reason", reasons[which]);
            rep.put("createdAt", FieldValue.serverTimestamp());
            Fb.db().collection("reports").add(rep);
            Ui.toast(this, "Thank you — our moderation team received your report.");
        });
    }

    // ==================== ENDING BROADCAST & POST-LIVE ANALYTICS SUMMARY ====================

    private void confirmExitOrEnd() {
        if (!isLiveStarted) {
            finish();
            return;
        }
        if (isHost) {
            Ui.confirm(this, "End Live Broadcast?",
                    "This will end the live video & audio stream for all " + currentViewers + " viewer(s).",
                    "End Live", this::endHostBroadcastAndShowSummary);
        } else {
            finish();
        }
    }

    private void endHostBroadcastAndShowSummary() {
        if (isEnded) return;
        isEnded = true;
        engine.stopBroadcasting();
        engine.stopSpeakerPlayback();

        long durSec = liveStartMs > 0 ? Math.max(1L, (System.currentTimeMillis() - liveStartMs) / 1000L) : 0L;
        if (liveId != null) {
            Map<String, Object> endMap = new HashMap<>();
            endMap.put("status", "ended");
            endMap.put("endedAt", FieldValue.serverTimestamp());
            endMap.put("durationSec", durSec);
            endMap.put("peakViewers", peakViewers);
            endMap.put("liveFrameB64", "");
            endMap.put("audioB64", "");
            Fb.db().collection("lives").document(liveId).update(endMap);
            if (rtdbStreamRef != null) {
                try { rtdbStreamRef.removeValue(); } catch (Throwable ignored) { }
            }
        }

        String summary = String.format(Locale.US,
                "⏱ Duration: %02d:%02d\n" +
                "👁 Peak Concurrent Viewers: %d\n" +
                "👥 Total Viewers Joined: %d\n" +
                "❤️ Floating Reactions: %d\n" +
                "💬 Live Chat Messages: %d\n" +
                "❓ Q&A Questions: %d\n" +
                "⭐ Community Stars Earned: %d",
                durSec / 60, durSec % 60,
                peakViewers, totalUniqueViewers, totalLikes,
                totalComments, qaItems.size(), totalStarsReceived);

        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle("🎬 Broadcast Summary")
                .setMessage(summary)
                .setPositiveButton("Share Recap to Feed", (d, w) -> {
                    publishRecapPost(durSec);
                    finish();
                })
                .setNegativeButton("Done", (d, w) -> finish())
                .show();
    }

    private void publishRecapPost(long durSec) {
        Map<String, Object> post = new HashMap<>();
        post.put("authorId", Fb.uid());
        post.put("authorName", Fb.myName());
        post.put("authorPhotoUrl", Fb.myPhoto() == null ? "" : Fb.myPhoto());
        post.put("text", String.format(Locale.US,
                "🔴 Just finished a %02d:%02d Live Broadcast: \"%s\"!\nThank you to %d viewers & everyone who sent ⭐ %d Stars! ❤️",
                durSec / 60, durSec % 60, liveTitleStr, totalUniqueViewers, totalStarsReceived));
        post.put("privacy", privacyFriendsOnly ? "friends" : "public");
        post.put("likesCount", 0);
        post.put("commentsCount", 0);
        post.put("sharesCount", 0);
        post.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("posts").add(post);
        Ui.toast(this, "✅ Live recap shared to Feed!");
    }

    private void showViewerBroadcastEndedDialog() {
        engine.stopSpeakerPlayback();
        stopLocalCoHostStreaming();
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle("🔴 Broadcast Ended")
                .setMessage(hostNameStr + " has ended this live broadcast. Thanks for watching!")
                .setPositiveButton("Back", (d, w) -> finish())
                .show();
    }

    @Override
    public void onBackPressed() {
        if (cleanViewMode) {
            toggleCleanViewMode(false);
            return;
        }
        confirmExitOrEnd();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (liveDocReg != null) { liveDocReg.remove(); liveDocReg = null; }
        if (chatColReg != null) { chatColReg.remove(); chatColReg = null; }
        if (rtdbStreamRef != null && rtdbStreamListener != null) {
            try { rtdbStreamRef.removeEventListener(rtdbStreamListener); } catch (Throwable ignored) { }
        }

        if (isHost && isLiveStarted && !isEnded && liveId != null) {
            Map<String, Object> endMap = new HashMap<>();
            endMap.put("status", "ended");
            endMap.put("endedAt", FieldValue.serverTimestamp());
            endMap.put("liveFrameB64", "");
            endMap.put("audioB64", "");
            Fb.db().collection("lives").document(liveId).update(endMap);
        } else if (!isHost && viewerCountJoined && liveId != null) {
            viewerCountJoined = false;
            Fb.db().collection("lives").document(liveId)
                    .update("viewers", FieldValue.increment(-1));
            Map<String, Object> leave = new HashMap<>();
            leave.put("type", "leave");
            leave.put("uid", Fb.uid());
            leave.put("name", Fb.myName());
            leave.put("createdAtMs", System.currentTimeMillis());
            Fb.db().collection("lives").document(liveId).collection("chat").add(leave);
        }

        stopLocalCoHostStreaming();
        if (engine != null) engine.releaseAll();
        super.onDestroy();
    }

    // ==================== LIVE CHAT ADAPTER ====================

    private static class ChatItem {
        final String id, uid, name, photo, text, type;
        ChatItem(String id, String uid, String name, String photo, String text, String type) {
            this.id = id;
            this.uid = uid == null ? "" : uid;
            this.name = name == null ? "User" : name;
            this.photo = photo == null ? "" : photo;
            this.text = text == null ? "" : text;
            this.type = type == null ? "chat" : type;
        }
    }

    private class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        private final List<ChatItem> items = new ArrayList<>();

        void setItems(List<ChatItem> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = Ui.row(LiveActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(LiveActivity.this, 0x55000000, 16));
            Ui.pad(row, 8, 5, 10, 5);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-2, -2);
            lp.setMargins(0, 0, 0, Ui.dp(LiveActivity.this, 4));
            row.setLayoutParams(lp);
            return new VH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final ChatItem it = items.get(pos);
            if ("gift".equals(it.type)) {
                h.root.setBackground(Ui.round(LiveActivity.this, 0xAAFF6F00, 16));
            } else if ("qa".equals(it.type)) {
                h.root.setBackground(Ui.round(LiveActivity.this, 0xAA1565C0, 16));
            } else {
                h.root.setBackground(Ui.round(LiveActivity.this, 0x58000000, 16));
            }

            Ui.avatar(LiveActivity.this, h.av, it.photo);
            String badge = "";
            if (it.uid.equals(hostId)) badge = " 👑";
            else if (moderatorIds.contains(it.uid)) badge = " 🛡";

            h.nameTv.setText(it.name + badge + "  ");
            h.msgTv.setText(it.text);
            h.root.setOnClickListener(v -> onChatItemClicked(it));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final LinearLayout root;
            final ImageView av;
            final TextView nameTv, msgTv;
            VH(LinearLayout r) {
                super(r);
                root = r;
                av = new ImageView(LiveActivity.this);
                int s = Ui.dp(LiveActivity.this, 22);
                r.addView(av, new LinearLayout.LayoutParams(s, s));

                LinearLayout textWrap = Ui.row(LiveActivity.this);
                textWrap.setPadding(Ui.dp(LiveActivity.this, 6), 0, 0, 0);
                nameTv = Ui.tvBold(LiveActivity.this, "", 11, 0xFFFFD54F);
                msgTv = Ui.tv(LiveActivity.this, "", 12, 0xFFFFFFFF);
                msgTv.setMaxWidth(Ui.dp(LiveActivity.this, 215));
                textWrap.addView(nameTv);
                textWrap.addView(msgTv);
                r.addView(textWrap);
            }
        }
    }
}
