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
import com.google.firebase.firestore.SetOptions;

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
    private static final int REQ_PK_GUEST_PERMS = 703;

    private static final String[] FILTER_NAMES = {
            "4K Natural", "Golden Warm", "Cinema Cool", "Rosy Face",
            "Teal Cinema", "Retro Film", "HDR Vivid", "Noir B&W"
    };
    private static final int[] FILTER_OVERLAYS = {
            0x00000000, 0x26FF9800, 0x2400B0FF, 0x26FF4081,
            0x380D1B2A, 0x2AD7CCC8, 0x1E7C4DFF, 0x48212121
    };
    /** GPU ColorMatrix preset keys that map 1:1 to FILTER_NAMES (real 4K UHD color science). */
    private static final String[] FILTER_PRESETS = {
            "vivid", "warm", "cool", "rose", "cool", "vintage", "vivid", "bw"
    };
    private static final String[] CATEGORIES = {
            "Chatting", "Gaming", "Music", "Q&A",
            "Sports", "Lifestyle", "Cooking", "Tech"
    };
    /** Viewer reaction tokens — every one is drawn as a real 2026 vector icon, never typed as an emoji. */
    private static final String[] REACTION_EMOJIS = {
            "heart", "flame", "laugh", "applause", "star", "confetti"
    };

    // Launch & Identity
    private String liveId;
    private String hostId;
    private boolean isHost;
    private boolean isLiveStarted;
    private boolean isEnded;
    private boolean viewerCountJoined;

    // Engines (main + optional Co-Host / PK Guest local engine when viewer joins stage)
    private LiveStreamEngine engine;
    private LiveStreamEngine coHostLocalEngine;
    private LiveStreamEngine pkGuestEngine;

    // Stage Views
    private FrameLayout rootStage;
    private View pkStageBackdrop;
    private TextureView cameraTexture;
    private ImageView hostBeautyOverlay;
    private ImageView remoteVideoView;
    private LinearLayout fallbackStage;
    private ImageView stageAvatar;
    private TextView stageStatusTv;
    private View filterOverlayView;
    private View ringLightOverlay;
    private FrameLayout floatingHeartsLayer;
    private FrameLayout giftFxLayer;

    // 2-User TikTok Split-Screen PK Battle Stage Views
    private FrameLayout pkStageOverlay;
    private View pkRedBar;
    private View pkBlueBar;
    private TextView pkScoreATv;
    private TextView pkScoreBTv;
    private TextView pkTimerPillTv;
    private FrameLayout pkLeftPane;
    private FrameLayout pkRightPane;
    private ImageView pkLeftRemoteImg;
    private ImageView pkRightRemoteImg;
    private TextureView pkRightGuestTexture;
    private ImageView pkGuestBeautyOverlay;
    private LinearLayout pkLeftFallbackBox;
    private LinearLayout pkRightFallbackBox;
    private ImageView pkLeftAvatar;
    private ImageView pkRightAvatar;
    private TextView pkLeftNameBadge;
    private TextView pkRightNameBadge;
    private TextView pkLeftMuteBtn;
    private TextView pkRightMuteBtn;
    private TextView pkLeftResultStamp;
    private TextView pkRightResultStamp;
    private LinearLayout pkResultBanner;
    private TextView pkWinnerHeadlineTv;
    private TextView pkRematchBtn;
    private TextView pkExitPkBtn;
    private TextView pkLeaveMatchBtn;

    // Co-Host PIP Card (Top-Right under header)
    private FrameLayout coHostCard;
    private TextureView coHostLocalTexture;
    private ImageView coHostBeautyOverlay;
    private ImageView coHostRemoteImage;
    private TextView coHostLabelTv;
    private TextView coHostCamToggleBtn;
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

    // Right Action Rail & TikTok Bottom Controls
    private TextView btnMic;
    private TextView btnVideoPause;
    private TextView btnSpeaker;
    private TextView btnTorch;
    private TextView btnRingLight;
    private TextView btnFilter;
    private TextView btnCoHost;
    private TextView btnPkRail;
    private TextView btnQa;
    private TextView btnPoll;
    private TextView likesBadgeTv;

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
    private boolean pkInvitesEnabled = true;
    private String blockedKeywordsCsv = "";
    private long lastCommentSentMs = 0L;

    // Metrics & Realtime Sync
    private long liveStartMs = 0L;
    private long frameSeqOut = 0L;
    private long audioSeqOut = 0L;
    private long coHostSeqOut = 0L;
    private long pkSeqOut = 0L;
    private long lastHostVideoUpdateMs = 0L;
    private long lastCoHostMediaPushMs = 0L;
    private long lastPkMediaPushMs = 0L;
    private int peakViewers = 1;
    private int currentViewers = 1;
    private int totalUniqueViewers = 1;
    private int totalLikes = 0;
    private int totalComments = 0;
    private int totalStarsReceived = 0;
    private int totalGiftsCount = 0;
    private int pkWinsCount = 0;
    private int pkPlayedCount = 0;
    private String hostNameStr = "Host";
    private String hostPhotoStr = "";
    private String liveTitleStr = "";

    // 2-User TikTok Live Match / PK Battle State
    private boolean pkBattleActive = false;
    private boolean pkVictoryPhase = false;
    private boolean isMePkGuest = false;
    private String pkBattleId = "";
    private String pkRole = ""; // "A", "B", or "viewer"
    private String pkHostAUid = "";
    private String pkHostAName = "";
    private String pkHostAPhoto = "";
    private String pkLiveIdA = "";
    private String pkHostBUid = "";
    private String pkHostBName = "";
    private String pkHostBPhoto = "";
    private String pkLiveIdB = "";
    private int pkScoreA = 0;
    private int pkScoreB = 0;
    private long pkEndsAtMs = 0L;
    private int pkDurationSec = 180;
    private String lastHandledPkInviteId = "";
    private String lastScoredPkRoundKey = "";
    private final Set<String> endedPkBattleIds = new HashSet<>();
    private long coHostLeftLocallyUntilMs = 0L;

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
    /** Polling stand-ins for the Firestore listeners; D1 has no push for these documents. */
    /**
     * How often the live room re-reads its documents.
     *
     * <p>This is the one screen that keeps a timer: a broadcast's chat and viewer count are
     * useless if they land a minute late, and neither is pushed over FCM. Five seconds instead
     * of three is a third fewer reads while still being fast enough to feel live.
     */
    private static final long LIVE_POLL_MS = 5000L;
    private final android.os.Handler livePollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable chatPollRunnable;
    private ListenerRegistration pkBattleReg;
    private ListenerRegistration pkMediaAReg;
    private ListenerRegistration pkMediaBReg;
    private String subscribedPkBattleId = "";
    private DatabaseReference rtdbStreamRef;
    private ValueEventListener rtdbStreamListener;
    private DatabaseReference pkRtdbARef;
    private DatabaseReference pkRtdbBRef;
    private ValueEventListener pkRtdbAListener;
    private ValueEventListener pkRtdbBListener;

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
                tickPkCountdown();
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
                com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, hb);
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

        // 0. PK Studio Dark Backdrop
        pkStageBackdrop = new View(this);
        pkStageBackdrop.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0E101A, 0xFF141828, 0xFF090A10}));
        pkStageBackdrop.setVisibility(View.GONE);
        rootStage.addView(pkStageBackdrop, new FrameLayout.LayoutParams(-1, -1));

        // 1. Local Camera2 TextureView (Host) + Real-Time Face Smoothing Overlay
        cameraTexture = new TextureView(this);
        cameraTexture.setVisibility(isHost ? View.VISIBLE : View.GONE);
        rootStage.addView(cameraTexture, new FrameLayout.LayoutParams(-1, -1));

        hostBeautyOverlay = new ImageView(this);
        hostBeautyOverlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hostBeautyOverlay.setVisibility(View.GONE);
        rootStage.addView(hostBeautyOverlay, new FrameLayout.LayoutParams(-1, -1));
        if (isHost) {
            engine.attachBeautyOverlay(hostBeautyOverlay);
        }

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
                isHost ? "Camera paused (Audio still live)" : "Connecting to live video & audio...",
                14, 0xDDFFFFFF);
        stageStatusTv.setGravity(Gravity.CENTER);
        Ui.pad(stageStatusTv, 20, 14, 20, 4);
        fallbackStage.addView(stageStatusTv);
        rootStage.addView(fallbackStage, new FrameLayout.LayoutParams(-1, -1));

        // 3B. 2-User TikTok Split-Screen Live Match / PK Battle Stage
        buildPkBattleStageOverlay();
        rootStage.addView(pkStageOverlay);

        // 4. Studio Visual Filter Layer (0.0ms GPU procedural lens shader, never a CPU bitmap)
        filterOverlayView = new View(this);
        filterOverlayView.setClickable(false);
        filterOverlayView.setFocusable(false);
        rootStage.addView(filterOverlayView, new FrameLayout.LayoutParams(-1, -1));
        applyLiveVisualGrade();

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

        // 9B. TikTok Gift Combo Banner & Celebration FX Layer
        giftFxLayer = new FrameLayout(this);
        giftFxLayer.setClickable(false);
        rootStage.addView(giftFxLayer, new FrameLayout.LayoutParams(-1, -1));

        // 10. Pre-Live Setup Studio Overlay + Live Broadcast HUD Overlay
        preLiveOverlay = buildPreLiveStudioOverlay();
        rootStage.addView(preLiveOverlay, new FrameLayout.LayoutParams(-1, -1));

        liveHudOverlay = buildLiveHudOverlay();
        rootStage.addView(liveHudOverlay, new FrameLayout.LayoutParams(-1, -1));

        // 11. Restore HUD Pill when user enters Clean View Mode
        restoreHudBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "eye", "Show Controls", 12f, 0xAA000000, 0xFFFFFFFF, 0xFFFFFFFF);
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

    private static final int PK_STAGE_TOP_DP = 76;
    private static final int PK_SCORE_BAR_H_DP = 24;
    private static final int PK_VIDEO_H_DP = 290;

    private void buildPkBattleStageOverlay() {
        pkStageOverlay = new FrameLayout(this);
        pkStageOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams rootLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        rootLp.topMargin = Ui.dp(this, PK_STAGE_TOP_DP);
        pkStageOverlay.setLayoutParams(rootLp);

        LinearLayout stack = Ui.col(this);
        pkStageOverlay.addView(stack, new FrameLayout.LayoutParams(-1, -2));

        // 1. Dynamic Red-vs-Blue PK Gift Score Bar (24dp)
        FrameLayout barFrame = new FrameLayout(this);
        LinearLayout barSplit = Ui.row(this);
        pkRedBar = new View(this);
        pkRedBar.setBackgroundColor(0xFFFE2C55);
        pkBlueBar = new View(this);
        pkBlueBar.setBackgroundColor(0xFF00B2FF);
        barSplit.addView(pkRedBar, new LinearLayout.LayoutParams(0, -1, 1f));
        barSplit.addView(pkBlueBar, new LinearLayout.LayoutParams(0, -1, 1f));
        barFrame.addView(barSplit, new FrameLayout.LayoutParams(-1, -1));

        pkScoreATv = com.friendbook.social.core.StudioVectorIcons.iconChip(this, "flame", "0", 11.5f, 0, 0xFFFF7043, 0xFFFFFFFF);
        Ui.pad(pkScoreATv, 10, 0, 10, 0);
        pkScoreATv.setGravity(Gravity.CENTER_VERTICAL);
        barFrame.addView(pkScoreATv, new FrameLayout.LayoutParams(-2, -1, Gravity.START | Gravity.CENTER_VERTICAL));

        pkScoreBTv = Ui.tvBold(this, "0", 11.5f, 0xFFFFFFFF);
        Ui.pad(pkScoreBTv, 10, 0, 10, 0);
        pkScoreBTv.setGravity(Gravity.CENTER_VERTICAL);
        barFrame.addView(pkScoreBTv, new FrameLayout.LayoutParams(-2, -1, Gravity.END | Gravity.CENTER_VERTICAL));

        TextView barCenterBadge = Ui.tvBold(this, "PK", 10f, 0xFFFFEB3B);
        barCenterBadge.setBackground(Ui.round(this, 0xCC000000, 10));
        Ui.pad(barCenterBadge, 7, 1, 7, 1);
        barFrame.addView(barCenterBadge, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        stack.addView(barFrame, new LinearLayout.LayoutParams(-1, Ui.dp(this, PK_SCORE_BAR_H_DP)));

        // 2. 50/50 Side-by-Side Dual Video Stage (290dp)
        FrameLayout stageWrap = new FrameLayout(this);
        LinearLayout splitRow = Ui.row(this);

        // Left 50% Pane (Side A — Red)
        pkLeftPane = new FrameLayout(this);
        pkLeftFallbackBox = Ui.col(this);
        pkLeftFallbackBox.setGravity(Gravity.CENTER);
        pkLeftFallbackBox.setBackgroundColor(0xFF171A26);
        pkLeftAvatar = new ImageView(this);
        Ui.avatar(this, pkLeftAvatar, null);
        pkLeftFallbackBox.addView(pkLeftAvatar, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        pkLeftPane.addView(pkLeftFallbackBox, new FrameLayout.LayoutParams(-1, -1));

        pkLeftRemoteImg = new ImageView(this);
        pkLeftRemoteImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pkLeftRemoteImg.setVisibility(View.GONE);
        pkLeftPane.addView(pkLeftRemoteImg, new FrameLayout.LayoutParams(-1, -1));

        pkLeftNameBadge = com.friendbook.social.core.StudioVectorIcons.iconChip(this, "dot", "Host A", 11f, 0xAA000000, 0xFFFE2C55, 0xFFFFFFFF);
        pkLeftNameBadge.setSingleLine(true);
        pkLeftNameBadge.setEllipsize(TextUtils.TruncateAt.END);
        pkLeftNameBadge.setBackground(Ui.round(this, 0xAA000000, 10));
        Ui.pad(pkLeftNameBadge, 8, 3, 8, 3);
        FrameLayout.LayoutParams lnLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lnLp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 24), Ui.dp(this, 6));
        pkLeftPane.addView(pkLeftNameBadge, lnLp);

        pkLeftMuteBtn = com.friendbook.social.core.StudioVectorIcons.circleIconButton(
                this, "speaker", 30, 0x99000000, 0xFFFFFFFF, null);
        pkLeftMuteBtn.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
        pkLeftMuteBtn.setOnClickListener(v -> {
            boolean m = !engine.isPrimaryHostAudioMuted();
            engine.setPrimaryHostAudioMuted(m);
            pkLeftMuteBtn.setText(m ? "mute" : "speaker");
            Ui.toast(this, m ? "Muted Side A audio" : "Unmuted Side A audio");
        });
        FrameLayout.LayoutParams lmLp = new FrameLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30), Gravity.BOTTOM | Gravity.END);
        lmLp.setMargins(0, 0, Ui.dp(this, 6), Ui.dp(this, 6));
        pkLeftPane.addView(pkLeftMuteBtn, lmLp);

        pkLeftResultStamp = Ui.tvBold(this, "", 15f, 0xFFFFFFFF);
        pkLeftResultStamp.setBackground(Ui.round(this, 0xDD000000, 14));
        Ui.pad(pkLeftResultStamp, 12, 6, 12, 6);
        pkLeftResultStamp.setVisibility(View.GONE);
        pkLeftPane.addView(pkLeftResultStamp, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // Center 2dp Divider
        View centerLine = new View(this);
        centerLine.setBackgroundColor(0x99FFFFFF);

        // Right 50% Pane (Side B — Blue)
        pkRightPane = new FrameLayout(this);
        pkRightFallbackBox = Ui.col(this);
        pkRightFallbackBox.setGravity(Gravity.CENTER);
        pkRightFallbackBox.setBackgroundColor(0xFF131B29);
        pkRightAvatar = new ImageView(this);
        Ui.avatar(this, pkRightAvatar, null);
        pkRightFallbackBox.addView(pkRightAvatar, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        pkRightPane.addView(pkRightFallbackBox, new FrameLayout.LayoutParams(-1, -1));

        pkRightRemoteImg = new ImageView(this);
        pkRightRemoteImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pkRightRemoteImg.setVisibility(View.GONE);
        pkRightPane.addView(pkRightRemoteImg, new FrameLayout.LayoutParams(-1, -1));

        pkRightGuestTexture = new TextureView(this);
        pkRightGuestTexture.setVisibility(View.GONE);
        pkRightPane.addView(pkRightGuestTexture, new FrameLayout.LayoutParams(-1, -1));

        pkGuestBeautyOverlay = new ImageView(this);
        pkGuestBeautyOverlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pkGuestBeautyOverlay.setVisibility(View.GONE);
        pkRightPane.addView(pkGuestBeautyOverlay, new FrameLayout.LayoutParams(-1, -1));

        pkRightNameBadge = com.friendbook.social.core.StudioVectorIcons.iconChip(this, "dot", "Host B", 11f, 0xAA000000, 0xFF00B2FF, 0xFFFFFFFF);
        pkRightNameBadge.setSingleLine(true);
        pkRightNameBadge.setEllipsize(TextUtils.TruncateAt.END);
        pkRightNameBadge.setBackground(Ui.round(this, 0xAA000000, 10));
        Ui.pad(pkRightNameBadge, 8, 3, 8, 3);
        FrameLayout.LayoutParams rnLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        rnLp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 24), Ui.dp(this, 6));
        pkRightPane.addView(pkRightNameBadge, rnLp);

        pkRightMuteBtn = com.friendbook.social.core.StudioVectorIcons.circleIconButton(
                this, "speaker", 30, 0x99000000, 0xFFFFFFFF, null);
        pkRightMuteBtn.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
        pkRightMuteBtn.setOnClickListener(v -> {
            boolean m = !engine.isCoHostAudioMuted();
            engine.setCoHostAudioMuted(m);
            pkRightMuteBtn.setText(m ? "mute" : "speaker");
            Ui.toast(this, m ? "Muted Side B audio" : "Unmuted Side B audio");
        });
        FrameLayout.LayoutParams rmLp = new FrameLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30), Gravity.BOTTOM | Gravity.END);
        rmLp.setMargins(0, 0, Ui.dp(this, 6), Ui.dp(this, 6));
        pkRightPane.addView(pkRightMuteBtn, rmLp);

        pkRightResultStamp = Ui.tvBold(this, "", 15f, 0xFFFFFFFF);
        pkRightResultStamp.setBackground(Ui.round(this, 0xDD000000, 14));
        Ui.pad(pkRightResultStamp, 12, 6, 12, 6);
        pkRightResultStamp.setVisibility(View.GONE);
        pkRightPane.addView(pkRightResultStamp, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        splitRow.addView(pkLeftPane, new LinearLayout.LayoutParams(0, -1, 1f));
        splitRow.addView(centerLine, new LinearLayout.LayoutParams(Ui.dp(this, 2), -1));
        splitRow.addView(pkRightPane, new LinearLayout.LayoutParams(0, -1, 1f));
        stageWrap.addView(splitRow, new FrameLayout.LayoutParams(-1, -1));

        // Top-Center PK Countdown Pill (`⚔️ PK 03:00`)
        pkTimerPillTv = Ui.tvBold(this, "PK 03:00", 12f, 0xFFFFFFFF);
        pkTimerPillTv.setBackground(Ui.roundStroke(this, 0xCC0D0F18, 14, 0xFFFFD54F, 1));
        Ui.pad(pkTimerPillTv, 11, 4, 11, 4);
        FrameLayout.LayoutParams tmLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tmLp.topMargin = Ui.dp(this, 6);
        stageWrap.addView(pkTimerPillTv, tmLp);

        // Always-visible "🚪 Leave Match" pill for any participant (Host A, Host B, or PK Guest) to leave anytime!
        pkLeaveMatchBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "close", "Leave Match", 11f, 0xEEFE2C55, 0xFFFFFFFF, 0xFFFFFFFF);
        pkLeaveMatchBtn.setVisibility(View.GONE);
        pkLeaveMatchBtn.setOnClickListener(v -> promptLeaveCurrentMatch());
        FrameLayout.LayoutParams lmBtnLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lmBtnLp.topMargin = Ui.dp(this, 6);
        lmBtnLp.rightMargin = Ui.dp(this, 8);
        stageWrap.addView(pkLeaveMatchBtn, lmBtnLp);

        // Center VS Clash Emblem
        TextView vsBadge = Ui.tvBold(this, "VS", 12f, 0xFFFFFFFF);
        GradientDrawable vsBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFE2C55, 0xFF00B2FF});
        vsBg.setCornerRadius(Ui.dp(this, 16));
        vsBg.setStroke(Ui.dp(this, 1.5f), 0xFFFFFFFF);
        vsBadge.setBackground(vsBg);
        Ui.pad(vsBadge, 9, 4, 9, 4);
        stageWrap.addView(vsBadge, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        stack.addView(stageWrap, new LinearLayout.LayoutParams(-1, Ui.dp(this, PK_VIDEO_H_DP)));

        // 3. PK Match Result / Winner Showcase Banner (shown when timer ends!)
        pkResultBanner = Ui.row(this);
        pkResultBanner.setGravity(Gravity.CENTER_VERTICAL);
        pkResultBanner.setBackground(Ui.roundStroke(this, 0xEE141824, 14, 0xFFFFD54F, 1));
        Ui.pad(pkResultBanner, 12, 8, 12, 8);
        pkResultBanner.setVisibility(View.GONE);

        pkWinnerHeadlineTv = Ui.tvBold(this, "PK Match Complete!", 12.5f, 0xFFFFD54F);
        pkResultBanner.addView(pkWinnerHeadlineTv, new LinearLayout.LayoutParams(0, -2, 1f));

        pkRematchBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "flip", "Rematch", 11f, 0xFFFE2C55, 0xFFFFFFFF, 0xFFFFFFFF);
        pkRematchBtn.setOnClickListener(v -> startPkRematchNow());
        pkResultBanner.addView(pkRematchBtn);

        pkExitPkBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "close", "Exit PK", 11f, 0x44FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF);
        LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(-2, -2);
        exLp.leftMargin = Ui.dp(this, 6);
        pkExitPkBtn.setOnClickListener(v -> endActivePkBattle());
        pkResultBanner.addView(pkExitPkBtn, exLp);

        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-1, -2);
        rbLp.setMargins(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), 0);
        stack.addView(pkResultBanner, rbLp);
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

        coHostBeautyOverlay = new ImageView(this);
        coHostBeautyOverlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coHostBeautyOverlay.setVisibility(View.GONE);
        coHostCard.addView(coHostBeautyOverlay, new FrameLayout.LayoutParams(-1, -1));

        coHostLabelTv = com.friendbook.social.core.StudioVectorIcons.iconChip(this, "cohost", "Co-Host", 10f, 0xAA000000, 0xFFFFFFFF, 0xFFFFFFFF);
        coHostLabelTv.setSingleLine(true);
        coHostLabelTv.setEllipsize(TextUtils.TruncateAt.END);
        coHostLabelTv.setBackground(Ui.round(this, 0xAA000000, 8));
        Ui.pad(coHostLabelTv, 6, 2, 6, 2);
        FrameLayout.LayoutParams lblLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lblLp.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), Ui.dp(this, 5));
        coHostCard.addView(coHostLabelTv, lblLp);

        coHostCamToggleBtn = com.friendbook.social.core.StudioVectorIcons.circleIconButton(
                this, "camera", 26, 0xAA000000, 0xFFFFFFFF, null);
        coHostCamToggleBtn.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        coHostCamToggleBtn.setVisibility(View.GONE);
        coHostCamToggleBtn.setOnClickListener(v -> toggleActiveUserCameraPause());
        FrameLayout.LayoutParams camLp = new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.TOP | Gravity.START);
        camLp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), 0, 0);
        coHostCard.addView(coHostCamToggleBtn, camLp);

        coHostCloseBtn = com.friendbook.social.core.StudioVectorIcons.circleIconButton(
                this, "close", 26, 0xCCE41E3F, 0xFFFFFFFF, null);
        coHostCloseBtn.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        coHostCloseBtn.setVisibility(View.GONE);
        coHostCloseBtn.setOnClickListener(v -> removeActiveCoHost());
        FrameLayout.LayoutParams xLp = new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.TOP | Gravity.END);
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
                            sendHeartLike();
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

        TextView closeBtn = circleBtn("close", 38, v -> finish());
        topBar.addView(closeBtn);

        TextView badge = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "camera", "LIVE CAMERA STUDIO", 12f, 0xAA000000, 0xFFFFFFFF, 0xFFFFFFFF);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
        bLp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        topBar.addView(badge, bLp);

        topBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        privacyChip = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "globe", "Public", 12f, 0xAA1877F2, 0xFFFFFFFF, 0xFFFFFFFF);
        privacyChip.setOnClickListener(v -> {
            privacyFriendsOnly = !privacyFriendsOnly;
            privacyChip.setText(privacyFriendsOnly ? "Friends" : "Public");
            privacyChip.setBackground(Ui.round(this, privacyFriendsOnly ? 0xAA2E7D32 : 0xAA1877F2, 20));
        });
        topBar.addView(privacyChip);
        wrap.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Right Studio Quick Tools Rail
        LinearLayout sideRail = Ui.col(this);
        sideRail.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(sideRail, 8, 8, 12, 8);

        sideRail.addView(studioSideItem("flip", "Flip", v -> {
            engine.flipCamera();
            Ui.toast(this, engine.isLensFront() ? "Front camera" : "Back camera");
        }));
        sideRail.addView(studioSideItem("mirror", "Mirror", v -> {
            boolean next = !engine.isMirrorFront();
            engine.setMirrorFront(next);
            Ui.toast(this, next ? "Front mirror ON" : "Front mirror OFF");
        }));
        sideRail.addView(studioSideItem("light", "Light", v -> toggleRingLightOrTorch()));
        sideRail.addView(studioSideItem("beauty", "Beauty", v -> openBeautyAndFilterStudioDrawer()));
        sideRail.addView(studioSideItem("filter", "Filter", v -> openBeautyAndFilterStudioDrawer()));
        sideRail.addView(studioSideItem("settings", "Settings", v -> showHostSettingsSheet()));

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

        qualityStudioChip = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "flash", "Auto 288p", 11f, 0x33FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF);
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
        TextView goBtn = Ui.tvBold(this, "GO LIVE NOW (VIDEO + VOICE)", 15, 0xFFFFFFFF);
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

        hostTopSub = com.friendbook.social.core.StudioVectorIcons.iconChip(this, "star", "0 Stars", 10f, 0, 0xFFFFD54F, 0xFFFFD54F);
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
        viewerCountTv = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "eye", "1", 11f, 0x88000000, 0xFFFFFFFF, 0xFFFFFFFF);
        viewerCountTv.setOnClickListener(v -> showViewersAndLeaderboardSheet());
        LinearLayout.LayoutParams vcLp = new LinearLayout.LayoutParams(-2, -2);
        vcLp.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
        topRow.addView(viewerCountTv, vcLp);

        // Close / End Button
        TextView exitBtn = circleBtn("close", 34, v -> confirmExitOrEnd());
        topRow.addView(exitBtn);
        topWrap.addView(topRow);

        // Second mini bar: Live Voice Meter + Quality + Leaderboard + Clean View
        LinearLayout subRow = Ui.row(this);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), 0);

        voiceMeterTv = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "mic", "Voice Live", 10f, 0x77000000, 0xFF69F0AE, 0xFF69F0AE);
        subRow.addView(voiceMeterTv);

        TextView topGiftChip = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "star", "Top Gifters", 10f, 0x77000000, 0xFFFFD54F, 0xFFFFD54F);
        topGiftChip.setOnClickListener(v -> showViewersAndLeaderboardSheet());
        LinearLayout.LayoutParams tgLp = new LinearLayout.LayoutParams(-2, -2);
        tgLp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        subRow.addView(topGiftChip, tgLp);

        TextView cleanChip = com.friendbook.social.core.StudioVectorIcons.iconChip(
                this, "eye", "Clean View", 10f, 0x77000000, 0xDDFFFFFF, 0xDDFFFFFF);
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
            ImageView unpin = com.friendbook.social.core.StudioVectorIcons.iconView(this, "close", 14, 0xFFFFFFFF);
            unpin.setOnClickListener(v -> updatePinnedComment(""));
            pinnedCommentBox.addView(unpin, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
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
            ImageView closeQa = com.friendbook.social.core.StudioVectorIcons.iconView(this, "close", 15, 0xFFFFD54F);
            closeQa.setOnClickListener(v -> clearSpotlightQa());
            spotlightQaCard.addView(closeQa, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
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
            btnPkRail = circleBtnSpaced("pk_swords", v -> openPkChallengeSheet());
            rail.addView(btnPkRail);
            rail.addView(circleBtnSpaced("flip", v -> flipActiveUserCamera()));
            btnFilter = circleBtnSpaced("beauty", v -> openBeautyAndFilterStudioDrawer());
            rail.addView(btnFilter);
            btnMic = circleBtnSpaced("mic", v -> toggleActiveUserMic());
            rail.addView(btnMic);
            btnVideoPause = circleBtnSpaced("camera", v -> toggleActiveUserCameraPause());
            rail.addView(btnVideoPause);
            btnRingLight = circleBtnSpaced("light", v -> toggleRingLightOrTorch());
            rail.addView(btnRingLight);
            rail.addView(circleBtnSpaced("users", v -> openInviteFriendsSheet()));
            btnCoHost = circleBtnSpaced("cohost", v -> showCoHostManagerSheet());
            rail.addView(btnCoHost);
            btnQa = circleBtnSpaced("qa", v -> showQaDrawerSheet());
            rail.addView(btnQa);
            btnPoll = circleBtnSpaced("poll", v -> showCreatePollDialog());
            rail.addView(btnPoll);
            rail.addView(circleBtnSpaced("send", v -> showShareAndInviteSheet()));
            rail.addView(circleBtnSpaced("settings", v -> showHostSettingsSheet()));
        } else {
            rail.addView(circleBtnSpaced("heart", v -> sendHeartLike()));
            rail.addView(circleBtnSpaced("gift", v -> showSendGiftSheet()));
            btnVideoPause = circleBtnSpaced("camera", v -> toggleActiveUserCameraPause());
            rail.addView(btnVideoPause);
            btnMic = circleBtnSpaced("mic", v -> toggleActiveUserMic());
            rail.addView(btnMic);
            rail.addView(circleBtnSpaced("flip", v -> flipActiveUserCamera()));
            btnFilter = circleBtnSpaced("beauty", v -> openBeautyAndFilterStudioDrawer());
            rail.addView(btnFilter);
            rail.addView(circleBtnSpaced("users", v -> openInviteFriendsSheet()));
            btnSpeaker = circleBtnSpaced("speaker", v -> toggleViewerSpeaker());
            rail.addView(btnSpeaker);
            btnCoHost = circleBtnSpaced("cohost", v -> requestOrLeaveCoHostStage());
            rail.addView(btnCoHost);
            btnPkRail = circleBtnSpaced("pk_swords", v -> viewerRequestPkBattle());
            rail.addView(btnPkRail);
            btnQa = circleBtnSpaced("qa", v -> showAskQuestionDialog());
            rail.addView(btnQa);
            rail.addView(circleBtnSpaced("send", v -> showShareAndInviteSheet()));
            rail.addView(circleBtnSpaced("settings", v -> showViewerSettingsSheet()));
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
            ImageView eBtn = com.friendbook.social.core.StudioVectorIcons.iconView(this, em, 20, 0xFFFFFFFF);
            eBtn.setBackground(Ui.round(this, 0x55000000, 18));
            Ui.pad(eBtn, 8, 8, 8, 8);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 36), Ui.dp(this, 36));
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
        Ui.pad(sendBtn, 12, 9, 12, 9);
        sendBtn.setOnClickListener(v -> sendChatMessage());
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-2, -2);
        sbLp.setMargins(Ui.dp(this, 5), 0, 0, 0);
        bottomComposerBar.addView(sendBtn, sbLp);

        if (isHost) {
            TextView pkQuick = com.friendbook.social.core.StudioVectorIcons.iconChip(this, "pk_swords", "PK", 12f, 0xFFFE2C55, 0xFFFFFFFF, 0xFFFFFFFF);
            Ui.pad(pkQuick, 11, 9, 11, 9);
            pkQuick.setOnClickListener(v -> openPkChallengeSheet());
            LinearLayout.LayoutParams pkLp = new LinearLayout.LayoutParams(-2, -2);
            pkLp.setMargins(Ui.dp(this, 5), 0, 0, 0);
            bottomComposerBar.addView(pkQuick, pkLp);

            TextView invQuick = com.friendbook.social.core.StudioVectorIcons.iconChip(this, "users", "Invite", 12f, 0x66000000, 0xFFFFFFFF, 0xFFFFFFFF);
            Ui.pad(invQuick, 10, 9, 10, 9);
            invQuick.setOnClickListener(v -> openInviteFriendsSheet());
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(-2, -2);
            ivLp.setMargins(Ui.dp(this, 5), 0, 0, 0);
            bottomComposerBar.addView(invQuick, ivLp);
        } else {
            TextView giftQuick = com.friendbook.social.core.StudioVectorIcons.iconChip(this, "gift", "Gift", 12f, 0xFFFE2C55, 0xFFFFFFFF, 0xFFFFFFFF);
            Ui.pad(giftQuick, 11, 9, 11, 9);
            giftQuick.setOnClickListener(v -> showSendGiftSheet());
            LinearLayout.LayoutParams gqLp = new LinearLayout.LayoutParams(-2, -2);
            gqLp.setMargins(Ui.dp(this, 5), 0, 0, 0);
            bottomComposerBar.addView(giftQuick, gqLp);

            likesBadgeTv = com.friendbook.social.core.StudioVectorIcons.iconChip(
                    this, "heart", "0", 12f, 0x66000000, 0xFFFFFFFF, 0xFFFFFFFF);
            likesBadgeTv.setOnClickListener(v -> sendHeartLike());
            LinearLayout.LayoutParams lkLp = new LinearLayout.LayoutParams(-2, -2);
            lkLp.setMargins(Ui.dp(this, 5), 0, 0, 0);
            bottomComposerBar.addView(likesBadgeTv, lkLp);
        }

        bottomZone.addView(bottomComposerBar, new LinearLayout.LayoutParams(-1, -2));
        hud.addView(bottomZone, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        return hud;
    }

    private TextView circleBtn(String emoji, int sizeDp, View.OnClickListener click) {
        return com.friendbook.social.core.StudioVectorIcons.circleIconButton(
                this, emoji, sizeDp, 0x77000000, 0xFFFFFFFF, click);
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
            } else if (reqCode == REQ_PK_GUEST_PERMS) {
                activateLocalPkGuestStreaming();
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
                stageStatusTv.setText("Audio-only mode (grant Camera permission for video)");
            }
        } else if (requestCode == REQ_COHOST_PERMS && camOk) {
            activateLocalCoHostStreaming();
        } else if (requestCode == REQ_PK_GUEST_PERMS && camOk) {
            activateLocalPkGuestStreaming();
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
                    countdownTv.setText("LIVE");
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
        liveTitleStr = entered.isEmpty() ? (hostNameStr + " is Live") : entered;
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
        doc.put("pkInvitesEnabled", pkInvitesEnabled);
        doc.put("pkActive", false);
        doc.put("pkBattleId", "");
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
            postSystemChat(hostNameStr + " started a live video & voice broadcast!");
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
            Notify.send(fUid, "live", "is LIVE now: " + liveTitleStr, liveId);
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
        joinPkt.put("text", "joined the live");
        joinPkt.put("createdAtMs", System.currentTimeMillis());
        joinPkt.put("createdAt", FieldValue.serverTimestamp());
        addChatPacket(joinPkt);
    }

    // ==================== DUAL-LANE STREAMING (RTDB FAST LANE + FIRESTORE GUARANTEED LANE) ====================

    private void setupRtdbFastLane() {
        if (liveId == null) return;
        try {
            FirebaseDatabase rtdb = Fb.rtdb();
            if (rtdb == null) return;
            rtdbStreamRef = rtdb.getReference("live_streams").child(liveId);
            rtdbStreamListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (isFinishing() || isEnded || !snapshot.exists()) return;
                    if (!isHost && !pkBattleActive) {
                        Boolean hCamOff = snapshot.child("camOff").getValue(Boolean.class);
                        if (Boolean.TRUE.equals(hCamOff)) {
                            remoteVideoView.setVisibility(View.GONE);
                            fallbackStage.setVisibility(View.VISIBLE);
                            stageStatusTv.setText(hostNameStr + " paused camera · Live voice active");
                        } else {
                            String vB64 = snapshot.child("v").getValue(String.class);
                            Long vSeq = snapshot.child("vs").getValue(Long.class);
                            if (vB64 != null && !vB64.isEmpty()) {
                                engine.decodeRemoteFrame(vB64, vSeq == null ? 0L : vSeq, false);
                            }
                        }
                        String aB64 = snapshot.child("a").getValue(String.class);
                        Long aSeq = snapshot.child("as").getValue(Long.class);
                        if (aB64 != null && !aB64.isEmpty()) {
                            engine.enqueueRemoteAudio(aB64, aSeq == null ? 0L : aSeq, false);
                        }
                    }
                    // Co-Host ultra-low-latency fast lane (received by Host and Viewers!)
                    if (!isMeActiveCoHost && !activeCoHostUid.isEmpty()) {
                        Boolean cCamOff = snapshot.child("cCamOff").getValue(Boolean.class);
                        if (Boolean.TRUE.equals(cCamOff)) {
                            coHostRemoteImage.setVisibility(View.GONE);
                        } else {
                            String cvB64 = snapshot.child("cv").getValue(String.class);
                            Long cvSeq = snapshot.child("cvs").getValue(Long.class);
                            if (cvB64 != null && !cvB64.isEmpty()) {
                                engine.decodeRemoteFrame(cvB64, cvSeq == null ? 0L : cvSeq, true);
                            }
                        }
                        String caB64 = snapshot.child("ca").getValue(String.class);
                        Long caSeq = snapshot.child("cas").getValue(Long.class);
                        if (caB64 != null && !caB64.isEmpty()) {
                            engine.enqueueRemoteAudio(caB64, caSeq == null ? 0L : caSeq, true);
                        }
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError error) { }
            };
            rtdbStreamRef.addValueEventListener(rtdbStreamListener);
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
                fast.put("camOff", false);
                rtdbStreamRef.updateChildren(fast);
            }
            // Guaranteed Firestore real-time stream (100% allowed for hostId == request.auth.uid!)
            Map<String, Object> upd = new HashMap<>();
            upd.put("liveFrameB64", base64Webp);
            upd.put("frameSeq", seq);
            upd.put("frameTs", now);
            upd.put("cameraPaused", false);
            upd.put("filterIndex", currentFilterIdx);
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, upd);
            Fb.db().collection("lives").document(liveId).update(upd);

            // Also publish to shared 2-User PK Battle Media Channel when PK is active!
            if (pkBattleActive && !pkBattleId.isEmpty() && ("A".equals(pkRole) || "B".equals(pkRole))) {
                String sideKey = "B".equals(pkRole) ? "b" : "a";
                String mediaDocId = "pkmedia_" + pkBattleId + "_" + sideKey;
                Map<String, Object> pkV = new HashMap<>();
                pkV.put("v", base64Webp);
                pkV.put("vs", seq);
                pkV.put("camOff", false);
                pkV.put("ts", now);
                DatabaseReference pkRef = "B".equals(pkRole) ? pkRtdbBRef : pkRtdbARef;
                if (pkRef != null) {
                    try { pkRef.updateChildren(pkV); } catch (Throwable ignored) { }
                }
                com.friendbook.social.core.DataRouter.mirrorPut("chats", mediaDocId, pkV);
                Fb.db().collection("chats").document(mediaDocId).set(pkV, SetOptions.merge());
            }
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
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, upd);
            Fb.db().collection("lives").document(liveId).update(upd);

            if (pkBattleActive && !pkBattleId.isEmpty() && ("A".equals(pkRole) || "B".equals(pkRole))) {
                String sideKey = "B".equals(pkRole) ? "b" : "a";
                String mediaDocId = "pkmedia_" + pkBattleId + "_" + sideKey;
                Map<String, Object> pkA = new HashMap<>();
                pkA.put("a", base64Ulaw);
                pkA.put("as", seq);
                DatabaseReference pkRef = "B".equals(pkRole) ? pkRtdbBRef : pkRtdbARef;
                if (pkRef != null) {
                    try { pkRef.updateChildren(pkA); } catch (Throwable ignored) { }
                }
                com.friendbook.social.core.DataRouter.mirrorPut("chats", mediaDocId, pkA);
                Fb.db().collection("chats").document(mediaDocId).set(pkA, SetOptions.merge());
            }
        }
    }

    @Override
    public void onLocalVoiceLevel(int voiceLevel) {
        if (voiceMeterTv == null) return;
        if (engine.isMicMuted()) {
            voiceMeterTv.setText("Mic Muted");
            voiceMeterTv.setTextColor(0xFFFF8A80);
        } else {
            String bars = voiceLevel > 45 ? "▂▅▇" : (voiceLevel > 12 ? "▂▅" : "▂");
            voiceMeterTv.setText("Live " + bars);
            voiceMeterTv.setTextColor(voiceLevel > 8 ? 0xFF69F0AE : 0xCCFFFFFF);
        }
    }

    @Override
    public void onDecodedRemoteVideo(Bitmap bitmap, boolean isCoHost) {
        if (isFinishing() || isEnded || bitmap == null) return;
        if (pkBattleActive) {
            if (!isCoHost) {
                // Side A (Left 50% Pane)
                if (!(isHost && "A".equals(pkRole))) {
                    pkLeftRemoteImg.setVisibility(View.VISIBLE);
                    pkLeftRemoteImg.setImageBitmap(bitmap);
                    pkLeftFallbackBox.setVisibility(View.GONE);
                }
            } else {
                // Side B (Right 50% Pane)
                if (!(isHost && "B".equals(pkRole)) && !isMePkGuest) {
                    pkRightRemoteImg.setVisibility(View.VISIBLE);
                    pkRightRemoteImg.setImageBitmap(bitmap);
                    pkRightFallbackBox.setVisibility(View.GONE);
                }
            }
            return;
        }
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
            stageStatusTv.setText(message + " (Audio broadcast active)");
        }
    }

    // ==================== FIRESTORE ROOM & CHAT REALTIME LISTENERS ====================

    /**
     * Adds a live-chat packet to both stores.
     *
     * <p>The packet maps here never carry the broadcast id - Firestore knew it from the
     * subcollection path. D1 is flat and the reader queries by liveId, so without this the
     * writer and the reader disagree and the room looks silent.
     */
    private void addChatPacket(Map<String, Object> packet) {
        addChatPacket(liveId, packet);
    }

    /** Same, for a packet addressed to a room other than the one on screen. */
    private void addChatPacket(String roomId, Map<String, Object> packet) {
        if (roomId == null || packet == null) return;
        com.google.firebase.firestore.DocumentReference ref =
                Fb.db().collection("lives").document(roomId).collection("chat").document();
        ref.set(packet);
        Map<String, Object> mirrored = new HashMap<>(packet);
        mirrored.put("liveId", roomId);
        com.friendbook.social.core.DataRouter.mirrorPut("live_chat", ref.getId(), mirrored);
    }

    private void attachRoomListeners() {
        if (liveId == null) return;

        // D1 has no push channel here, so the live room polls while it is on screen and stops
        // when it is not. The interval is deliberately modest: a broadcast is one document plus
        // a bounded chat tail, and D1 reads are cheap compared with the Firestore quota this
        // replaces. Polling only runs between onStart and onStop.
        fetchLiveRoom();
        fetchLiveChat();
        chatPollRunnable = () -> {
            if (isFinishing() || isDestroyed() || liveId == null) return;
            fetchLiveRoom();
            fetchLiveChat();
            fetchPkSignals();
            livePollHandler.postDelayed(chatPollRunnable, LIVE_POLL_MS);
        };
        fetchLiveRoom();
        fetchLiveChat();
        fetchPkSignals();
        livePollHandler.postDelayed(chatPollRunnable, LIVE_POLL_MS);
    }

    /** Fetches the broadcast document and hands it to the existing render logic. */
    private String pkBattleId, pkMediaAId, pkMediaBId;

    /** Reads the three PK documents once. Called from the live poll, so there is one timer. */
    private void fetchPkSignals() {
        if (pkBattleId != null) {
            com.friendbook.social.core.CloudflareStore.get("chats", pkBattleId, doc -> {
                if (isFinishing() || isEnded || doc == null) return;
                onPkBattleDocSnapshot(com.friendbook.social.core.Doc.of(pkBattleId, doc));
            });
        }
        if (pkMediaAId != null && "B".equals(pkRole)) {
            com.friendbook.social.core.CloudflareStore.get("chats", pkMediaAId, doc -> {
                if (isFinishing() || isEnded || !pkBattleActive || doc == null) return;
                onPkMediaDoc(pkLeftRemoteImg, pkLeftFallbackBox, doc);
            });
        }
        if (pkMediaBId != null && "A".equals(pkRole)) {
            com.friendbook.social.core.CloudflareStore.get("chats", pkMediaBId, doc -> {
                if (isFinishing() || isEnded || !pkBattleActive || doc == null) return;
                onPkMediaDoc(pkRightRemoteImg, pkRightFallbackBox, doc);
            });
        }
    }

    /** Applies one PK media document to the remote video pane. */
    private void onPkMediaDoc(android.widget.ImageView img, android.view.View fallback, Map<String, Object> m) {
        if (Boolean.TRUE.equals(m.get("camOff"))) {
            img.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
        } else {
            fallback.setVisibility(View.GONE);
            String v = Format.str(m, "v");
            if (!v.isEmpty()) {
                img.setVisibility(View.VISIBLE);
                Ui.img(LiveActivity.this, img, v);
            }
        }
    }

    private void fetchLiveRoom() {
        if (liveId == null || isFinishing()) return;
        com.friendbook.social.core.CloudflareStore.get("lives", liveId, m -> {
            if (m != null && !isFinishing()) onLiveDocumentSnapshot(m);
        });
    }

    /**
     * Fetches the newest chat packets.
     *
     * <p>The Firestore version asked for the last 100 by createdAtMs ascending. D1 pages from
     * the start, so the newest 100 are requested descending and reversed here - asking ascending
     * would return the first 100 ever sent and the room would look frozen in the past.
     */
    private void fetchLiveChat() {
        if (liveId == null || isFinishing()) return;
        com.friendbook.social.core.CloudflareStore.query(
                com.friendbook.social.core.DocQuery.of("live_chat")
                        .whereEqualTo("liveId", liveId)
                        .orderBy("createdAtMs", com.friendbook.social.core.DocQuery.DESC)
                        .limit(100).build(),
                page -> {
                    if (isFinishing()) return;
                    List<Map<String, Object>> docs = new ArrayList<>(page.documents);
                    java.util.Collections.reverse(docs);
                    onChatSnapshot(docs, false);
                }, () -> { });
    }

    @SuppressWarnings("unchecked")
    private void onLiveDocumentSnapshot(Map<String, Object> m) {
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
        pkInvitesEnabled = m.get("pkInvitesEnabled") == null || Boolean.TRUE.equals(m.get("pkInvitesEnabled"));
        blockedKeywordsCsv = Format.str(m, "blockedKeywords");

        commentInput.setEnabled(commentsEnabled || isHost || isMeModerator());
        commentInput.setHint(!commentsEnabled ? "Host turned off comments"
                : (slowModeEnabled ? "Slow mode (5s)..." : "Say something nice..."));

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
        viewerCountTv.setText(Format.count(currentViewers));

        totalStarsReceived = Math.max(Format.intOf(m, "stars"), Format.intOf(m, "totalStars"));
        int docLikes = Format.intOf(m, "likesCount");
        if (docLikes > totalLikes) totalLikes = docLikes;
        if (likesBadgeTv != null) likesBadgeTv.setText(Format.count(totalLikes));
        hostTopSub.setText(Format.count(totalStarsReceived) + " Stars  ·  " + Format.count(totalLikes) + " Likes");

        // 2-User Split-Screen PK Battle Room Sync
        boolean docPkActive = Boolean.TRUE.equals(m.get("pkActive"));
        String docPkId = Format.str(m, "pkBattleId");
        if (docPkActive && !docPkId.isEmpty() && !endedPkBattleIds.contains(docPkId)) {
            subscribeToPkBattle(docPkId);
        } else if (!docPkActive && !isHost && pkBattleActive) {
            teardownPkStageUi();
        }

        // Filter Sync
        int remoteFilter = Format.intOf(m, "filterIndex");
        if (!isHost && remoteFilter >= 0 && remoteFilter < FILTER_OVERLAYS.length) {
            currentFilterIdx = remoteFilter;
            applyLiveVisualGrade();
        }

        // Viewer Decodes Real-Time Host Video + Real-Time Host Audio (when not in split-screen PK)!
        if (!isHost && !pkBattleActive) {
            boolean hostCamPaused = Boolean.TRUE.equals(m.get("cameraPaused"));
            if (hostCamPaused) {
                remoteVideoView.setVisibility(View.GONE);
                fallbackStage.setVisibility(View.VISIBLE);
                stageStatusTv.setText(hostNameStr + " paused camera · Live voice active");
            } else {
                String frameB64 = Format.str(m, "liveFrameB64");
                long fSeq = Format.longOf(m, "frameSeq");
                if (!frameB64.isEmpty()) {
                    engine.decodeRemoteFrame(frameB64, fSeq, false);
                } else if (System.currentTimeMillis() - lastHostVideoUpdateMs > 5000L) {
                    fallbackStage.setVisibility(View.VISIBLE);
                    stageStatusTv.setText(hostNameStr + " is Live");
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
                    voiceMeterTv.setText("Sound Muted");
                    voiceMeterTv.setTextColor(0xFFFF8A80);
                } else {
                    String bars = aLevel > 40 ? "▂▅▇" : (aLevel > 10 ? "▂▅" : "▂");
                    voiceMeterTv.setText("Live Voice " + bars);
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
        pinnedCommentTv.setText(pinned);

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

    private final com.friendbook.social.core.RealtimeSoundGate giftSoundGate = new com.friendbook.social.core.RealtimeSoundGate(com.friendbook.social.core.Presence.now());
    private void onChatSnapshot(List<Map<String, Object>> docs, boolean fromCache) {
        boolean soundEligible = giftSoundGate.begin(fromCache);
        List<ChatItem> visibleMessages = new ArrayList<>();
        for (Map<String, Object> m : docs) {
            if (m == null) continue;
            String id = Format.str(m, "id");
            if (deletedMsgIds.contains(id)) continue;

            String type = Format.strOr(m, "type", "chat");
            String uid = Format.str(m, "uid");
            String name = Format.strOr(m, "name", "Viewer");
            String photo = Format.str(m, "photo");
            String text = Format.str(m, "text");
            boolean isNewPacket = processedPacketIds.add(id);

            // Immediately hide permanently blocked users from Live Chat!
            if (!uid.isEmpty() && Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(uid)) {
                if ("gift".equals(type)) giftSoundGate.observe(id, Format.longOf(m, "createdAtMs"),
                        com.friendbook.social.core.Presence.now(), soundEligible);
                continue;
            }

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
                    if (likesBadgeTv != null) likesBadgeTv.setText(Format.count(totalLikes));
                    if (!uid.equals(Fb.uid())) {
                        spawnFloatingEmoji(text.isEmpty() ? "heart" : text);
                    }
                }
            } else if ("gift".equals(type)) {
                int stars = Format.intOf(m, "stars");
                String gEmoji = Format.strOr(m, "giftEmoji", "gift");
                String gName = Format.strOr(m, "giftName", "Gift");
                long giftTime = Format.ts(m.get("createdAt"));
                if (giftTime <= 0) giftTime = Format.longOf(m, "createdAtMs");
                boolean freshGiftSound = giftSoundGate.observe(id, giftTime, com.friendbook.social.core.Presence.now(), soundEligible);
                if (isNewPacket && stars > 0) {
                    totalGiftsCount++;
                    int prev = supporterStars.containsKey(uid) ? supporterStars.get(uid) : 0;
                    supporterStars.put(uid, prev + stars);
                    supporterNames.put(uid, name);
                    spawnFloatingEmoji(gEmoji);
                    if (giftFxLayer != null) {
                        LivePkAndGiftsUi.playGiftBannerAnimation(
                                this, giftFxLayer, photo, name, gEmoji, gName, stars);
                    }
                }
                visibleMessages.add(new ChatItem(id, uid, name, photo, text, "gift"));
            } else if ("pk_invite".equals(type)) {
                String targetUid = Format.str(m, "targetUid");
                String pkId = Format.str(m, "pkBattleId");
                int durS = Math.max(60, Format.intOf(m, "durationSec"));
                if (isNewPacket && targetUid.equals(Fb.uid()) && !pkId.isEmpty()
                        && !id.equals(lastHandledPkInviteId) && pkInvitesEnabled) {
                    lastHandledPkInviteId = id;
                    promptIncomingPkChallenge(pkId, uid, name, photo, durS);
                }
            } else if ("pk_req".equals(type)) {
                if (isNewPacket && isHost && !uid.isEmpty() && !uid.equals(Fb.uid()) && pkInvitesEnabled) {
                    promptViewerRequestedPk(uid, name, photo);
                }
                visibleMessages.add(new ChatItem(id, uid, name, photo, "requested a PK Battle!", "system"));
            } else if ("pk_leave".equals(type)) {
                String leftPkId = Format.str(m, "pkBattleId");
                if (!leftPkId.isEmpty()) endedPkBattleIds.add(leftPkId);
                if (isNewPacket) {
                    if (isHost && liveId != null) {
                        Map<String, Object> lu = new HashMap<>();
                        lu.put("pkActive", false);
                        lu.put("pkBattleId", "");
                        com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, lu);
                        Fb.db().collection("lives").document(liveId).update(lu);
                    }
                    if (pkBattleActive) {
                        teardownPkStageUi();
                        if (!uid.equals(Fb.uid())) {
                            Ui.toast(this, name + " left the Live Match");
                        }
                    }
                }
                visibleMessages.add(new ChatItem(id, uid, name, photo, "left the Live Match", "system"));
            } else if ("cohost_leave".equals(type)) {
                if (isNewPacket && isHost && liveId != null && uid.equals(activeCoHostUid)) {
                    Map<String, Object> cu = new HashMap<>();
                    cu.put("coHostUid", "");
                    cu.put("coHostName", "");
                    cu.put("coHostFrameB64", "");
                    cu.put("coHostAudioB64", "");
                    com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, cu);
                    Fb.db().collection("lives").document(liveId).update(cu);
                    if (rtdbStreamRef != null) {
                        try {
                            Map<String, Object> rClear = new HashMap<>();
                            rClear.put("cv", "");
                            rClear.put("ca", "");
                            rClear.put("cCamOff", true);
                            rtdbStreamRef.updateChildren(rClear);
                        } catch (Throwable ignored) { }
                    }
                }
                visibleMessages.add(new ChatItem(id, uid, name, photo, "left the Co-Host stage", "system"));
            } else if ("qa".equals(type)) {
                if (isNewPacket) {
                    qaItems.add(0, new ChatItem(id, uid, name, photo, text, "qa"));
                    if (isHost) Ui.toast(this, "New Q&A question from " + name);
                }
                visibleMessages.add(new ChatItem(id, uid, name, photo, "Asked: " + text, "qa"));
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
                        Ui.toast(this, name + " requested to join your Live!");
                    }
                }
                visibleMessages.add(new ChatItem(id, uid, name, photo, "requested to join Live", "system"));
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
                            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, relay);
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
        giftSoundGate.end(fromCache);
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

        if (activeCoHostUid.equals(me) && System.currentTimeMillis() < coHostLeftLocallyUntilMs) {
            return;
        }

        coHostCard.setVisibility(View.VISIBLE);
        coHostLabelTv.setText(activeCoHostName.isEmpty() ? "Co-Host" : activeCoHostName);
        coHostCloseBtn.setText("close");
        coHostCloseBtn.setVisibility((isHost || activeCoHostUid.equals(me)) ? View.VISIBLE : View.GONE);
        if (coHostCamToggleBtn != null) {
            coHostCamToggleBtn.setVisibility(activeCoHostUid.equals(me) ? View.VISIBLE : View.GONE);
        }

        if (activeCoHostUid.equals(me)) {
            if (!isMeActiveCoHost) {
                isMeActiveCoHost = true;
                Ui.toast(this, "Host accepted! Starting your Co-Host camera & mic...");
                ensureLivePermissions(REQ_COHOST_PERMS);
            }
        } else {
            if (isMeActiveCoHost) stopLocalCoHostStreaming();
            coHostLocalTexture.setVisibility(View.GONE);
            if (coHostBeautyOverlay != null) coHostBeautyOverlay.setVisibility(View.GONE);
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
            coHostLocalEngine.setStreamQuality(LiveStreamEngine.QUALITY_BALANCED);
            coHostLocalEngine.setBeautyLevel(engine.getBeautyLevel());
        }
        coHostLocalEngine.attachCameraPreview(coHostLocalTexture, true);
        coHostLocalEngine.attachBeautyOverlay(coHostBeautyOverlay);
        coHostLocalEngine.startBroadcasting(true);
    }

    private void pushCoHostMediaPacket(@Nullable String vB64, @Nullable String aB64) {
        if (!isMeActiveCoHost || liveId == null || isEnded) return;
        long now = System.currentTimeMillis();
        long seq = ++coHostSeqOut;
        if (rtdbStreamRef != null) {
            Map<String, Object> fast = new HashMap<>();
            if (vB64 != null) {
                fast.put("cv", vB64);
                fast.put("cvs", seq);
                fast.put("cCamOff", false);
            }
            if (aB64 != null) {
                fast.put("ca", aB64);
                fast.put("cas", seq);
            }
            try { rtdbStreamRef.updateChildren(fast); } catch (Throwable ignored) { }
        }
        if (vB64 != null && now - lastCoHostMediaPushMs < 420L) return;
        if (vB64 != null) lastCoHostMediaPushMs = now;

        Map<String, Object> pkt = new HashMap<>();
        pkt.put("type", "cohost_media");
        pkt.put("uid", Fb.uid());
        pkt.put("name", Fb.myName());
        pkt.put("seq", seq);
        if (vB64 != null) pkt.put("v", vB64);
        if (aB64 != null) pkt.put("a", aB64);
        pkt.put("createdAtMs", now);
        addChatPacket(pkt);
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
        addChatPacket(req);
        Ui.toast(this, "Co-Host join request sent to " + hostNameStr + "!");
    }

    private void showCoHostManagerSheet() {
        List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        if (!activeCoHostUid.isEmpty()) {
            labels.add("Remove current Co-Host (" + activeCoHostName + ")");
            actions.add(this::removeActiveCoHost);
        }
        for (final ChatItem req : pendingCoHostRequests.values()) {
            labels.add("Accept " + req.name + " onto Split-Screen Live");
            actions.add(() -> {
                pendingCoHostRequests.remove(req.uid);
                Map<String, Object> upd = new HashMap<>();
                upd.put("coHostUid", req.uid);
                upd.put("coHostName", req.name);
                com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, upd);
                Fb.db().collection("lives").document(liveId).update(upd);
                postSystemChat(req.name + " joined as Co-Host!");
            });
        }
        labels.add(coHostAllowed ? "Turn OFF Co-Host Requests" : "Turn ON Co-Host Requests");
        actions.add(() -> {
            coHostAllowed = !coHostAllowed;
            if (liveId != null) Fb.db().collection("lives").document(liveId).update("coHostAllowed", coHostAllowed);
            Ui.toast(this, coHostAllowed ? "Co-Host requests enabled" : "Co-Host requests disabled");
        });

        Ui.choose(this, "Co-Host Split-Screen Studio",
                labels.toArray(new String[0]), which -> actions.get(which).run());
    }

    private void removeActiveCoHost() {
        if (isHost && liveId != null) {
            Map<String, Object> upd = new HashMap<>();
            upd.put("coHostUid", "");
            upd.put("coHostName", "");
            upd.put("coHostFrameB64", "");
            upd.put("coHostAudioB64", "");
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, upd);
            Fb.db().collection("lives").document(liveId).update(upd);
            if (rtdbStreamRef != null) {
                try {
                    Map<String, Object> rClear = new HashMap<>();
                    rClear.put("cv", "");
                    rClear.put("ca", "");
                    rClear.put("cCamOff", true);
                    rtdbStreamRef.updateChildren(rClear);
                } catch (Throwable ignored) { }
            }
        } else if (isMeActiveCoHost) {
            coHostLeftLocallyUntilMs = System.currentTimeMillis() + 15_000L;
            activeCoHostUid = "";
            stopLocalCoHostStreaming();
            coHostCard.setVisibility(View.GONE);
            if (rtdbStreamRef != null) {
                try {
                    Map<String, Object> rClear = new HashMap<>();
                    rClear.put("cv", "");
                    rClear.put("ca", "");
                    rClear.put("cCamOff", true);
                    rtdbStreamRef.updateChildren(rClear);
                } catch (Throwable ignored) { }
            }
            if (liveId != null) {
                Map<String, Object> leavePkt = new HashMap<>();
                leavePkt.put("type", "cohost_leave");
                leavePkt.put("uid", Fb.uid());
                leavePkt.put("name", Fb.myName());
                leavePkt.put("text", "left the Co-Host stage");
                leavePkt.put("createdAtMs", System.currentTimeMillis());
                addChatPacket(leavePkt);
            }
            Ui.toast(this, "You left the Co-Host match");
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
        o1.setHint("Option 1 (e.g. Yes)");
        box.addView(o1);

        final EditText o2 = new EditText(this);
        o2.setHint("Option 2 (e.g. Next topic)");
        box.addView(o2);

        final EditText o3 = new EditText(this);
        o3.setHint("Option 3 (optional)");
        box.addView(o3);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Launch Live Poll");
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
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, upd);
            Fb.db().collection("lives").document(liveId).update(upd);
            postSystemChat("Host started a Live Poll: " + q);
        });
        if (!activePollId.isEmpty()) {
            b.setNeutralButton("End Current Poll", (d, w) -> {
                Map<String, Object> upd = new HashMap<>();
                upd.put("pollId", "");
                upd.put("pollQuestion", "");
                com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, upd);
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
        head.addView(Ui.tvBold(this, activePollQuestion, 12, 0xFFFFFFFF),
                new LinearLayout.LayoutParams(0, -2, 1f));
        if (isHost) {
            TextView endP = Ui.tvBold(this, "End", 11, 0xFFFF8A80);
            endP.setOnClickListener(v -> {
                Map<String, Object> upd = new HashMap<>();
                upd.put("pollId", "");
                upd.put("pollQuestion", "");
                com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, upd);
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
                    (picked ? "Selected · " : "") + activePollOptions.get(i) + "  ·  " + pct + "% (" + c + ")",
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
        addChatPacket(vote);
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
                .setTitle("Ask Host a Question")
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
                    addChatPacket(pkt);
                    Ui.toast(this, "Question sent to Host!");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showQaDrawerSheet() {
        if (qaItems.isEmpty()) {
            Ui.toast(this, "No viewer Q&A questions yet — viewers can tap to ask!");
            return;
        }
        List<String> labels = new ArrayList<>();
        for (ChatItem item : qaItems) {
            labels.add("Spotlight @" + item.name + ": " + item.text);
        }
        labels.add("Clear Current Stage Spotlight");
        Ui.choose(this, "Viewer Q&A Questions (" + qaItems.size() + ")",
                labels.toArray(new String[0]), which -> {
                    if (which == qaItems.size()) {
                        clearSpotlightQa();
                    } else {
                        ChatItem picked = qaItems.get(which);
                        String banner = "Q&A · @" + picked.name + ": " + picked.text;
                        com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, "spotlightQa", banner);
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
            Ui.toast(this, "You are muted in this live chat");
            return;
        }
        if (!commentsEnabled && !isHost && !isMeModerator()) {
            Ui.toast(this, "Comments are turned off");
            return;
        }
        if (slowModeEnabled && !isHost && !isMeModerator()) {
            long elapsed = System.currentTimeMillis() - lastCommentSentMs;
            if (elapsed < 5000L) {
                Ui.toast(this, "Slow mode: wait " + ((5000L - elapsed) / 1000L + 1) + "s");
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
        addChatPacket(msg);
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
        addChatPacket(sys);
    }

    private void sendHeartLike() {
        spawnFloatingEmoji("heart");
        spawnFloatingEmoji("heart");
        totalLikes++;
        if (likesBadgeTv != null) likesBadgeTv.setText(Format.count(totalLikes));
        if (liveId == null || isEnded) return;
        // `likesCount` is explicitly allowed for any signed-in viewer in deployed firestore.rules!
        Fb.db().collection("lives").document(liveId)
                .update("likesCount", FieldValue.increment(1));
        Map<String, Object> r = new HashMap<>();
        r.put("type", "reaction");
        r.put("uid", Fb.uid());
        r.put("name", Fb.myName());
        r.put("text", "heart");
        r.put("createdAtMs", System.currentTimeMillis());
        addChatPacket(r);
    }

    private void sendReaction(String emoji) {
        if ("heart".equalsIgnoreCase(emoji) || "❤️".equals(emoji)) {
            sendHeartLike();
            return;
        }
        spawnFloatingEmoji(emoji);
        if (liveId == null || isEnded) return;
        Fb.db().collection("lives").document(liveId)
                .update("likesCount", FieldValue.increment(1));
        Map<String, Object> r = new HashMap<>();
        r.put("type", "reaction");
        r.put("uid", Fb.uid());
        r.put("name", Fb.myName());
        r.put("text", emoji);
        r.put("createdAtMs", System.currentTimeMillis());
        addChatPacket(r);
    }

    private void spawnFloatingEmoji(String emoji) {
        if (floatingHeartsLayer == null || isFinishing()) return;
        // Real drawn vector reactions (heart / flame / star / gift / confetti ...). Legacy emoji
        // payloads from older clients are normalized to the same vectors, and only genuinely
        // free-typed text falls back to a TextView.
        final View tv;
        if (com.friendbook.social.core.StudioVectorIcons.isVectorToken(emoji)) {
            String k = emoji == null ? "" : emoji.trim().toLowerCase(Locale.US);
            int tint = 0xFFFFFFFF;
            if (k.contains("heart")) tint = 0xFFFE2C55;
            else if (k.contains("flame")) tint = 0xFFFF7043;
            else if (k.contains("star")) tint = 0xFFFFD54F;
            else if (k.contains("confetti")) tint = 0xFF69F0AE;
            else if (k.contains("gift")) tint = 0xFFFFB300;
            else if (k.contains("laugh") || k.contains("applause")) tint = 0xFF80D8FF;
            ImageView iv = com.friendbook.social.core.StudioVectorIcons.iconView(this, emoji, 26, tint);
            iv.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
            tv = iv;
        } else {
            TextView tt = new TextView(this);
            tt.setText(emoji);
            tt.setTextSize(26);
            tv = tt;
        }
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
            Ui.toast(this, "Host turned off Gifts for this stream");
            return;
        }
        String nameA = pkBattleActive && !pkHostAName.isEmpty() ? pkHostAName : hostNameStr;
        String nameB = pkBattleActive ? pkHostBName : null;
        boolean defaultSideB = pkBattleActive && liveId != null && liveId.equals(pkLiveIdB)
                && !pkLiveIdA.equals(pkLiveIdB);

        LivePkAndGiftsUi.showGiftDrawer(this, nameA, pkBattleActive && !pkVictoryPhase, nameB, defaultSideB,
                (gift, targetPkSideB) -> {
                    int coins = gift.coins;
                    String recipientUid = (pkBattleActive && targetPkSideB && !pkHostBUid.isEmpty())
                            ? pkHostBUid : (pkBattleActive && !pkHostAUid.isEmpty() ? pkHostAUid : hostId);
                    String recipientLiveId = (pkBattleActive && targetPkSideB && !pkLiveIdB.isEmpty())
                            ? pkLiveIdB : (pkBattleActive && !pkLiveIdA.isEmpty() ? pkLiveIdA : liveId);
                    String recipientName = (pkBattleActive && targetPkSideB && !pkHostBName.isEmpty())
                            ? pkHostBName : nameA;

                    if (recipientLiveId != null && !recipientLiveId.isEmpty()) {
                        Fb.db().collection("lives").document(recipientLiveId)
                                .update("stars", FieldValue.increment(coins));
                    }

                    // Boost PK Battle Score Bar in real time across BOTH rooms!
                    if (pkBattleActive && !pkVictoryPhase && !pkBattleId.isEmpty()) {
                        String scoreField = targetPkSideB ? "scoreB" : "scoreA";
                        Fb.db().collection("chats").document("pkbattle_" + pkBattleId)
                                .set(Fb.map(scoreField, FieldValue.increment(coins)), SetOptions.merge());
                    }

                    Map<String, Object> giftDoc = new HashMap<>();
                    giftDoc.put("senderId", Fb.uid());
                    giftDoc.put("senderName", Fb.myName());
                    giftDoc.put("toUid", recipientUid);
                    giftDoc.put("liveId", recipientLiveId);
                    giftDoc.put("stars", coins);
                    giftDoc.put("giftId", gift.id);
                    giftDoc.put("giftName", gift.name);
                    giftDoc.put("createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("gifts").add(giftDoc);

                    Map<String, Object> chatGift = new HashMap<>();
                    chatGift.put("type", "gift");
                    chatGift.put("uid", Fb.uid());
                    chatGift.put("name", Fb.myName());
                    chatGift.put("photo", Fb.myPhoto() == null ? "" : Fb.myPhoto());
                    chatGift.put("stars", coins);
                    chatGift.put("giftId", gift.id);
                    chatGift.put("giftEmoji", gift.icon);
                    chatGift.put("giftName", gift.name);
                    chatGift.put("text", "sent " + gift.name + " (" + coins + " Stars)"
                            + (pkBattleActive ? " to " + recipientName : "") + "!");
                    chatGift.put("createdAtMs", System.currentTimeMillis());
                    chatGift.put("createdAt", FieldValue.serverTimestamp());
                    if (liveId != null) {
                        addChatPacket(chatGift);
                    }
                    // Mirror gift celebration banner to opponent's room if battling across 2 Live rooms!
                    if (pkBattleActive && !pkLiveIdA.isEmpty() && !pkLiveIdB.isEmpty() && !pkLiveIdA.equals(pkLiveIdB)) {
                        String otherRoom = pkLiveIdA.equals(liveId) ? pkLiveIdB : pkLiveIdA;
                        addChatPacket(otherRoom, chatGift);
                    }

                    spawnFloatingEmoji(gift.icon);
                });
    }

    private void onChatItemClicked(ChatItem item) {
        if (item == null || item.uid.isEmpty()) return;
        List<String> opts = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();

        opts.add("View " + item.name + "'s Profile");
        acts.add(() -> {
            Intent p = new Intent(this, ProfileActivity.class);
            p.putExtra("uid", item.uid);
            startActivity(p);
        });

        if (isHost || isMeModerator()) {
            opts.add("Pin this comment");
            acts.add(() -> updatePinnedComment(item.name + ": " + item.text));
        }

        if (isHost) {
            opts.add("Hide / Delete comment");
            acts.add(() -> {
                deletedMsgIds.add(item.id);
                Fb.db().collection("lives").document(liveId)
                        .update("deletedMsgIds", new ArrayList<>(deletedMsgIds));
            });

            if (!item.uid.equals(Fb.uid())) {
                boolean muted = mutedUserIds.contains(item.uid);
                opts.add(muted ? "Unmute " + item.name : "Mute " + item.name + " in Chat");
                acts.add(() -> {
                    if (muted) mutedUserIds.remove(item.uid);
                    else mutedUserIds.add(item.uid);
                    Fb.db().collection("lives").document(liveId)
                            .update("mutedUserIds", new ArrayList<>(mutedUserIds));
                    Ui.toast(this, muted ? "Unmuted " + item.name : "Muted " + item.name);
                });

                boolean isMod = moderatorIds.contains(item.uid);
                opts.add(isMod ? "Remove Live Moderator" : "Make " + item.name + " Live Moderator");
                acts.add(() -> {
                    if (isMod) moderatorIds.remove(item.uid);
                    else moderatorIds.add(item.uid);
                    Fb.db().collection("lives").document(liveId)
                            .update("moderatorIds", new ArrayList<>(moderatorIds));
                    postSystemChat(isMod ? (item.name + " is no longer moderator")
                            : (item.name + " is now a Live Moderator!"));
                });

                opts.add("Challenge " + item.name + " to Split-Screen PK Battle");
                acts.add(() -> challengeViewerToPk(item.uid, item.name,
                        viewerPhotos.getOrDefault(item.uid, ""), 180));

                opts.add("Invite " + item.name + " as Split-Screen Co-Host");
                acts.add(() -> {
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("coHostUid", item.uid);
                    upd.put("coHostName", item.name);
                    com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, upd);
                    Fb.db().collection("lives").document(liveId).update(upd);
                    if (Fb.uid() != null) {
                        LivePkAndGiftsUi.sendDirectLiveInviteToUser(
                                Fb.uid(), item.uid,
                                hostId != null && !hostId.isEmpty() ? hostId : Fb.uid(),
                                hostNameStr, hostPhotoStr, liveTitleStr, liveId);
                    }
                });

                opts.add("Remove / Kick " + item.name + " from Live");
                acts.add(() -> {
                    kickedUserIds.add(item.uid);
                    Fb.db().collection("lives").document(liveId)
                            .update("kickedUserIds", new ArrayList<>(kickedUserIds));
                    Ui.toast(this, "Removed " + item.name + " from broadcast");
                });
            }
        }

        if (!item.uid.equals(Fb.uid())) {
            opts.add("Block " + item.name + " Permanently");
            acts.add(() -> LivePkAndGiftsUi.blockUserPermanently(this, item.uid, item.name, () -> {
                if (isHost && liveId != null) {
                    mutedUserIds.add(item.uid);
                    kickedUserIds.add(item.uid);
                    Map<String, Object> modUpd = new HashMap<>();
                    modUpd.put("mutedUserIds", new ArrayList<>(mutedUserIds));
                    modUpd.put("kickedUserIds", new ArrayList<>(kickedUserIds));
                    com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, modUpd);
                    Fb.db().collection("lives").document(liveId).update(modUpd);
                }
            }));
        }

        Ui.choose(this, item.name, opts.toArray(new String[0]), w -> acts.get(w).run());
    }

    private void updatePinnedComment(String text) {
        if (liveId == null) return;
        if (isHost) {
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, "pinnedComment", text);
            Fb.db().collection("lives").document(liveId).update("pinnedComment", text);
        } else {
            postSystemChat("Moderator pinned: " + text);
        }
    }

    // ==================== COMPLETE STUDIO CONTROLS & SETTINGS SHEETS ====================

    private LiveStreamEngine getMyActiveStageEngine() {
        if (isMePkGuest && pkGuestEngine != null) return pkGuestEngine;
        if (isMeActiveCoHost && coHostLocalEngine != null) return coHostLocalEngine;
        return engine;
    }

    private void flipActiveUserCamera() {
        if (!isHost && !isMeActiveCoHost && !isMePkGuest) {
            Ui.toast(this, "Join on stage (Co-Host or PK) to use your camera!");
            return;
        }
        LiveStreamEngine activeEng = getMyActiveStageEngine();
        if (activeEng != null) {
            activeEng.flipCamera();
            Ui.toast(this, activeEng.isLensFront() ? "Front camera" : "Back camera");
        }
    }

    private void toggleActiveUserMic() {
        if (!isHost && !isMeActiveCoHost && !isMePkGuest) {
            requestOrLeaveCoHostStage();
            return;
        }
        LiveStreamEngine activeEng = getMyActiveStageEngine();
        if (activeEng == null) return;
        boolean nextMuted = !activeEng.isMicMuted();
        activeEng.setMicMuted(nextMuted);
        if (btnMic != null) btnMic.setText(nextMuted ? "mute" : "mic");
        Ui.toast(this, nextMuted ? "Microphone muted" : "Microphone live");
    }

    private void toggleActiveUserCameraPause() {
        if (isHost) {
            toggleHostCameraPause();
            return;
        }
        if (isMeActiveCoHost && coHostLocalEngine != null) {
            boolean nextPaused = !coHostLocalEngine.isCameraPaused();
            coHostLocalEngine.setCameraPaused(nextPaused);
            coHostLocalTexture.setVisibility(nextPaused ? View.GONE : View.VISIBLE);
            if (coHostBeautyOverlay != null) {
                coHostBeautyOverlay.setVisibility((!nextPaused && coHostLocalEngine.getBeautyLevel() > 0)
                        ? View.VISIBLE : View.GONE);
            }
            if (btnVideoPause != null) btnVideoPause.setText(nextPaused ? "none" : "camera");
            if (coHostCamToggleBtn != null) coHostCamToggleBtn.setText(nextPaused ? "none" : "camera");
            if (rtdbStreamRef != null) {
                try { rtdbStreamRef.child("cCamOff").setValue(nextPaused); } catch (Throwable ignored) { }
            }
            Ui.toast(this, nextPaused ? "Co-Host camera OFF (Voice still live)" : "Co-Host camera ON");
            return;
        }
        if (isMePkGuest && pkGuestEngine != null) {
            boolean nextPaused = !pkGuestEngine.isCameraPaused();
            pkGuestEngine.setCameraPaused(nextPaused);
            pkRightGuestTexture.setVisibility(nextPaused ? View.GONE : View.VISIBLE);
            if (pkGuestBeautyOverlay != null) {
                pkGuestBeautyOverlay.setVisibility((!nextPaused && pkGuestEngine.getBeautyLevel() > 0)
                        ? View.VISIBLE : View.GONE);
            }
            pkRightFallbackBox.setVisibility(nextPaused ? View.VISIBLE : View.GONE);
            if (btnVideoPause != null) btnVideoPause.setText(nextPaused ? "none" : "camera");
            if (pkRtdbBRef != null) {
                try { pkRtdbBRef.child("camOff").setValue(nextPaused); } catch (Throwable ignored) { }
            }
            if (!pkBattleId.isEmpty()) {
                Fb.db().collection("chats").document("pkmedia_" + pkBattleId + "_b")
                        .set(Fb.map("camOff", nextPaused), SetOptions.merge());
            }
            Ui.toast(this, nextPaused ? "PK camera OFF (Voice still live)" : "PK camera ON");
            return;
        }
        requestOrLeaveCoHostStage();
    }

    private void toggleHostMic() {
        toggleActiveUserMic();
    }

    private void toggleHostCameraPause() {
        boolean nextPaused = !engine.isCameraPaused();
        engine.setCameraPaused(nextPaused);
        cameraTexture.setVisibility(nextPaused ? View.GONE : View.VISIBLE);
        if (hostBeautyOverlay != null) {
            hostBeautyOverlay.setVisibility((!nextPaused && engine.getBeautyLevel() > 0)
                    ? View.VISIBLE : View.GONE);
        }
        if (pkBattleActive) {
            if ("A".equals(pkRole)) pkLeftFallbackBox.setVisibility(nextPaused ? View.VISIBLE : View.GONE);
            else if ("B".equals(pkRole)) pkRightFallbackBox.setVisibility(nextPaused ? View.VISIBLE : View.GONE);
        } else {
            fallbackStage.setVisibility(nextPaused ? View.VISIBLE : View.GONE);
        }
        if (btnVideoPause != null) btnVideoPause.setText(nextPaused ? "none" : "camera");
        if (rtdbStreamRef != null) {
            try { rtdbStreamRef.child("camOff").setValue(nextPaused); } catch (Throwable ignored) { }
        }
        if (liveId != null && isLiveStarted) {
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, "cameraPaused", nextPaused);
            Fb.db().collection("lives").document(liveId).update("cameraPaused", nextPaused);
        }
        if (pkBattleActive && !pkBattleId.isEmpty() && ("A".equals(pkRole) || "B".equals(pkRole))) {
            String sideKey = "B".equals(pkRole) ? "b" : "a";
            DatabaseReference pkRef = "B".equals(pkRole) ? pkRtdbBRef : pkRtdbARef;
            if (pkRef != null) {
                try { pkRef.child("camOff").setValue(nextPaused); } catch (Throwable ignored) { }
            }
            Fb.db().collection("chats").document("pkmedia_" + pkBattleId + "_" + sideKey)
                    .set(Fb.map("camOff", nextPaused), SetOptions.merge());
        }
        Ui.toast(this, nextPaused ? "Camera OFF (Voice still live)" : "Camera ON");
    }

    private void toggleViewerSpeaker() {
        boolean nextMuted = !engine.isSpeakerMuted();
        engine.setSpeakerMuted(nextMuted);
        if (btnSpeaker != null) btnSpeaker.setText(nextMuted ? "mute" : "speaker");
        Ui.toast(this, nextMuted ? "Stream sound muted" : "Stream sound ON");
    }

    private void toggleRingLightOrTorch() {
        if (engine.isLensFront()) {
            ringLightOn = !ringLightOn;
            ringLightOverlay.setVisibility(ringLightOn ? View.VISIBLE : View.GONE);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = ringLightOn ? 1.0f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
            Ui.toast(this, ringLightOn ? "Front Screen Ring-Light ON" : "Ring-Light OFF");
        } else {
            boolean on = engine.toggleTorch();
            Ui.toast(this, on ? "Rear Flash Torch ON" : "Rear Torch OFF");
        }
    }

    /**
     * Applies the real 4K UHD GPU color grade to the live camera:
     * a hardware ColorMatrixColorFilter straight on the camera TextureView (true color science,
     * not a flat tint) + a procedural lens bloom / face glow shader on the overlay layer.
     * Both run 100% on the GPU → 0.0ms per frame, so Live never lags or hangs.
     */
    private void applyLiveVisualGrade() {
        int idx = Math.max(0, Math.min(FILTER_PRESETS.length - 1, currentFilterIdx));
        LiveStreamEngine eng = getMyActiveStageEngine();
        int beauty = eng != null ? eng.getBeautyLevel() : 0;
        com.friendbook.social.core.StudioVectorIcons.apply4kUhdGpuGrade(
                cameraTexture, filterOverlayView, beauty,
                FILTER_PRESETS[idx], FILTER_OVERLAYS[idx]);
    }

    private void cycleVisualFilter() {
        currentFilterIdx = (currentFilterIdx + 1) % FILTER_NAMES.length;
        applyLiveVisualGrade();
        if (isHost && isLiveStarted && liveId != null) {
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, "filterIndex", currentFilterIdx);
            Fb.db().collection("lives").document(liveId).update("filterIndex", currentFilterIdx);
        }
        Ui.toast(this, "Filter: " + FILTER_NAMES[currentFilterIdx]);
    }

    private void cycleStreamQuality() {
        int next = (engine.getStreamQuality() + 1) % 3;
        engine.setStreamQuality(next);
        String lbl = engine.getQualityLabel();
        if (qualityStudioChip != null) qualityStudioChip.setText(lbl);
        Ui.toast(this, "Stream Quality: " + lbl);
    }

    private void toggleCleanViewMode(boolean hide) {
        cleanViewMode = hide;
        liveHudOverlay.setVisibility(hide ? View.GONE : View.VISIBLE);
        restoreHudBtn.setVisibility(hide ? View.VISIBLE : View.GONE);
    }

    private void openBeautyAndFilterStudioDrawer() {
        LiveStreamEngine activeEng = getMyActiveStageEngine();
        LivePkAndGiftsUi.showBeautyAndFilterDrawer(
                this,
                activeEng.getBeautyLevel(),
                currentFilterIdx,
                FILTER_NAMES,
                ringLightOn,
                activeEng.isMirrorFront(),
                new LivePkAndGiftsUi.BeautyFilterCallback() {
                    @Override
                    public void onBeautyChanged(int beautyLevel) {
                        engine.setBeautyLevel(beautyLevel);
                        if (coHostLocalEngine != null) coHostLocalEngine.setBeautyLevel(beautyLevel);
                        if (pkGuestEngine != null) pkGuestEngine.setBeautyLevel(beautyLevel);
                        Ui.toast(LiveActivity.this, "Face Smooth: " + engine.getBeautyLabel());
                    }
                    @Override
                    public void onColorFilterChanged(int filterIndex) {
                        currentFilterIdx = filterIndex;
                        applyLiveVisualGrade();
                        if (isHost && isLiveStarted && liveId != null) {
                            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, "filterIndex", currentFilterIdx);
                            Fb.db().collection("lives").document(liveId).update("filterIndex", currentFilterIdx);
                        }
                    }
                    @Override
                    public void onToggleRingLight() {
                        toggleRingLightOrTorch();
                    }
                    @Override
                    public void onToggleMirror() {
                        LiveStreamEngine cur = getMyActiveStageEngine();
                        cur.setMirrorFront(!cur.isMirrorFront());
                    }
                });
    }

    private void openInviteFriendsSheet() {
        if (liveId == null) {
            Ui.toast(this, "Start your Live first to invite friends!");
            return;
        }
        String hUid = (hostId != null && !hostId.isEmpty()) ? hostId : (Fb.uid() == null ? "" : Fb.uid());
        LivePkAndGiftsUi.showInviteFriendsDrawer(this, liveId, hUid, hostNameStr, hostPhotoStr, liveTitleStr);
    }

    private void showHostSettingsSheet() {
        String[] opts = {
                "Edit Live Broadcast Title",
                "Stream Quality (" + engine.getQualityLabel() + ")",
                "4K UHD Beauty & Camera Filters (" + engine.getBeautyLabel() + ")",
                engine.isMicMuted() ? "Unmute Microphone" : "Mute Microphone",
                engine.isCameraPaused() ? "Resume Camera Video" : "Pause Camera (Audio-Only Shield)",
                engine.isMirrorFront() ? "Front Mirror: ON (Tap to flip)" : "Front Mirror: OFF (Tap to flip)",
                ringLightOn ? "Front Ring-Light: ON" : "Front Ring-Light: OFF",
                commentsEnabled ? "Turn OFF Live Comments" : "Turn ON Live Comments",
                slowModeEnabled ? "Disable Slow Mode (5s)" : "Enable Slow Mode (5s)",
                "Set Blocked Chat Keywords" + (blockedKeywordsCsv.isEmpty() ? "" : " (Active)"),
                "Manage Muted & Removed Viewers (" + (mutedUserIds.size() + kickedUserIds.size()) + ")",
                giftsEnabled ? "Turn OFF Live Gifts" : "Turn ON Live Gifts",
                pkInvitesEnabled ? "Turn OFF PK Battle Invites" : "Turn ON PK Battle Invites",
                qaEnabled ? "Turn OFF Q&A Box" : "Turn ON Q&A Box",
                coHostAllowed ? "Turn OFF Co-Host Requests" : "Turn ON Co-Host Requests"
        };

        Ui.choose(this, "Live Studio Settings & Controls", opts, which -> {
            switch (which) {
                case 0: promptEditLiveTitle(); break;
                case 1: cycleStreamQuality(); break;
                case 2: openBeautyAndFilterStudioDrawer(); break;
                case 3: toggleHostMic(); break;
                case 4: toggleHostCameraPause(); break;
                case 5:
                    engine.setMirrorFront(!engine.isMirrorFront());
                    Ui.toast(this, engine.isMirrorFront() ? "Mirror ON" : "Mirror OFF");
                    break;
                case 6: toggleRingLightOrTorch(); break;
                case 7:
                    commentsEnabled = !commentsEnabled;
                    syncHostSetting("commentsEnabled", commentsEnabled);
                    break;
                case 8:
                    slowModeEnabled = !slowModeEnabled;
                    syncHostSetting("slowMode", slowModeEnabled);
                    break;
                case 9: promptBlockedKeywords(); break;
                case 10:
                    LivePkAndGiftsUi.showModerationManagerDialog(this, mutedUserIds, kickedUserIds, viewerNames,
                            new LivePkAndGiftsUi.ModerationActionCallback() {
                                @Override
                                public void onUnmuteUser(@NonNull String uid) {
                                    mutedUserIds.remove(uid);
                                    if (liveId != null) {
                                        Fb.db().collection("lives").document(liveId)
                                                .update("mutedUserIds", new ArrayList<>(mutedUserIds));
                                    }
                                    Ui.toast(LiveActivity.this, "Unmuted viewer");
                                }
                                @Override
                                public void onUnkickUser(@NonNull String uid) {
                                    kickedUserIds.remove(uid);
                                    if (liveId != null) {
                                        Fb.db().collection("lives").document(liveId)
                                                .update("kickedUserIds", new ArrayList<>(kickedUserIds));
                                    }
                                    Ui.toast(LiveActivity.this, "Viewer allowed back");
                                }
                            });
                    break;
                case 11:
                    giftsEnabled = !giftsEnabled;
                    syncHostSetting("giftsEnabled", giftsEnabled);
                    break;
                case 12:
                    pkInvitesEnabled = !pkInvitesEnabled;
                    syncHostSetting("pkInvitesEnabled", pkInvitesEnabled);
                    break;
                case 13:
                    qaEnabled = !qaEnabled;
                    syncHostSetting("qaEnabled", qaEnabled);
                    break;
                case 14:
                    coHostAllowed = !coHostAllowed;
                    syncHostSetting("coHostAllowed", coHostAllowed);
                    break;
            }
        });
    }

    private void syncHostSetting(String field, Object value) {
        if (isHost && isLiveStarted && liveId != null) {
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, field);
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
                .setTitle("Broadcast Title")
                .setView(w)
                .setPositiveButton("Save", (d, x) -> {
                    String t = et.getText().toString().trim();
                    if (t.isEmpty() || ContentSafetyEngine.checkText(t).unsafe) return;
                    liveTitleStr = t;
                    titleInput.setText(t);
                    if (isLiveStarted && liveId != null) {
                        com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, "title", t);
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
                .setTitle("Chat Keyword Filter")
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
                engine.isSpeakerMuted() ? "Unmute Live Audio" : "Mute Live Audio",
                boosted ? "Normal Speaker Volume (100%)" : "Speaker Volume Boost (150%)",
                fitVideoCenter ? "Fill Fullscreen Video (Crop)" : "Fit Complete Video Frame",
                "Enter Clean View Mode (Hide Chat & HUD)",
                "Share or Invite Friends to Live",
                "Report Live Broadcast"
        };
        Ui.choose(this, "Viewer Controls & Settings", opts, which -> {
            switch (which) {
                case 0: toggleViewerSpeaker(); break;
                case 1:
                    engine.setSpeakerGain(boosted ? 1.0f : 1.5f);
                    Ui.toast(this, boosted ? "Normal volume (100%)" : "Volume Boost ON (150%)");
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
        sb.append("Active Viewers: ").append(currentViewers)
          .append("   ·   Peak: ").append(peakViewers)
          .append("\nTotal Stars: ").append(totalStarsReceived).append("\n\n");

        if (!supporterStars.isEmpty()) {
            sb.append("TOP STAR SUPPORTERS:\n");
            List<Map.Entry<String, Integer>> list = new ArrayList<>(supporterStars.entrySet());
            Collections.sort(list, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
            int rank = 1;
            for (Map.Entry<String, Integer> e : list) {
                sb.append(rank++).append(". ")
                  .append(supporterNames.getOrDefault(e.getKey(), "Supporter"))
                  .append(" — ").append(e.getValue()).append("\n");
                if (rank > 5) break;
            }
            sb.append("\n");
        }

        sb.append("RECENT PARTICIPANTS:\n");
        int shown = 0;
        for (Map.Entry<String, String> e : viewerNames.entrySet()) {
            sb.append("• ").append(e.getValue());
            if (moderatorIds.contains(e.getKey())) sb.append(" · Mod");
            sb.append("\n");
            if (++shown >= 12) break;
        }

        new AlertDialog.Builder(this)
                .setTitle("Live Audience & Leaderboard")
                .setMessage(sb.toString().trim())
                .setPositiveButton("Close", null)
                .show();
    }

    private void showShareAndInviteSheet() {
        String[] opts = {
                "Invite Friends & Followers to Watch Live",
                "Share 'Watch Live Now' Card to Feed",
                "Share Live Invite via Other Apps"
        };
        Ui.choose(this, "Share & Invite to Live", opts, which -> {
            if (which == 0) {
                openInviteFriendsSheet();
            } else if (which == 1) {
                Map<String, Object> post = new HashMap<>();
                post.put("authorId", Fb.uid());
                post.put("authorName", Fb.myName());
                post.put("authorPhotoUrl", Fb.myPhoto() == null ? "" : Fb.myPhoto());
                post.put("text", "Watching LIVE right now: " + liveTitleStr + " — Join us in friendbook Live!");
                post.put("bgColor", "#FFE41E3F");
                post.put("privacy", "public");
                post.put("likesCount", 0);
                post.put("commentsCount", 0);
                post.put("sharesCount", 0);
                post.put("createdAt", FieldValue.serverTimestamp());
                Fb.db().collection("posts").add(post);
                Ui.toast(this, "Shared Live invite to Home Feed!");
            } else {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("text/plain");
                // ALWAYS share Play Store link
                s.putExtra(Intent.EXTRA_TEXT, "Check out friendbook!\n" + Ui.PLAY_STORE_LINK);
                startActivity(Intent.createChooser(s, "Share friendbook"));
            }
        });
    }

    private void followHostNow() {
        String me = Fb.uid();
        if (me == null || hostId == null || hostId.equals(me)) return;
        Notify.follow(hostId, true);
        if (followHostBtn != null) {
            followHostBtn.setText("Following");
            followHostBtn.setBackground(Ui.round(this, 0x55FFFFFF, 14));
        }
        postSystemChat(Fb.myName() + " followed " + hostNameStr + "!");
    }

    private void reportLiveStream() {
        String[] reasons = {"Inappropriate / Nudity", "Harassment or Bullying", "Violence / Harm", "Spam or Scam"};
        Ui.choose(this, "Report Live Broadcast", reasons, which -> {
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

    // ==================== 2-USER TIKTOK SPLIT-SCREEN LIVE MATCH / PK BATTLE ====================

    private void openPkChallengeSheet() {
        if (!isLiveStarted || liveId == null) {
            Ui.toast(this, "Start your Live broadcast first!");
            return;
        }
        LivePkAndGiftsUi.showPkChallengeDrawer(
                this, liveId, pkBattleActive, viewerNames, viewerPhotos,
                new LivePkAndGiftsUi.PkChallengeCallback() {
                    @Override
                    public void onChallengeLiveHost(@NonNull String targetLiveId,
                                                    @NonNull String targetHostUid,
                                                    @NonNull String targetHostName,
                                                    @NonNull String targetHostPhoto,
                                                    int durationSec) {
                        challengeLiveHostToPk(targetLiveId, targetHostUid, targetHostName, targetHostPhoto, durationSec);
                    }
                    @Override
                    public void onChallengeViewerOrFriend(@NonNull String targetUid,
                                                          @NonNull String targetName,
                                                          @NonNull String targetPhoto,
                                                          int durationSec) {
                        challengeViewerToPk(targetUid, targetName, targetPhoto, durationSec);
                    }
                    @Override
                    public void onEndCurrentPk() {
                        endActivePkBattle();
                    }
                });
    }

    private void viewerRequestPkBattle() {
        if (isMePkGuest && pkBattleActive) {
            Ui.confirm(this, "Leave PK Battle?", "Your split-screen camera & mic will turn off.",
                    "Leave PK", this::endActivePkBattle);
            return;
        }
        if (!pkInvitesEnabled) {
            Ui.toast(this, "Host turned off PK Battle requests");
            return;
        }
        if (liveId == null) return;
        Map<String, Object> req = new HashMap<>();
        req.put("type", "pk_req");
        req.put("uid", Fb.uid());
        req.put("name", Fb.myName());
        req.put("photo", Fb.myPhoto() == null ? "" : Fb.myPhoto());
        req.put("text", "challenged Host to a Split-Screen PK Battle!");
        req.put("createdAtMs", System.currentTimeMillis());
        addChatPacket(req);
        Ui.toast(this, "PK Battle challenge sent to " + hostNameStr + "!");
    }

    private void promptViewerRequestedPk(final String vUid, final String vName, final String vPhoto) {
        if (isFinishing() || pkBattleActive) {
            Ui.toast(this, vName + " wants to battle next!");
            return;
        }
        Ui.confirm(this, "PK Challenge from " + vName + "!",
                vName + " wants to join a 3-Minute Split-Screen Live PK Battle with you!",
                "Start PK", () -> challengeViewerToPk(vUid, vName, vPhoto, 180));
    }

    private void challengeLiveHostToPk(@NonNull String targetLiveId,
                                       @NonNull String targetHostUid,
                                       @NonNull String targetHostName,
                                       @NonNull String targetHostPhoto,
                                       int durationSec) {
        String me = Fb.uid();
        if (me == null || liveId == null) return;
        String newPkId = liveId + "_" + System.currentTimeMillis();
        pkDurationSec = durationSec;

        Map<String, Object> pkDoc = new HashMap<>();
        pkDoc.put("pkBattleId", newPkId);
        pkDoc.put("status", "invited");
        pkDoc.put("hostAUid", me);
        pkDoc.put("hostAName", hostNameStr);
        pkDoc.put("hostAPhoto", hostPhotoStr);
        pkDoc.put("liveIdA", liveId);
        pkDoc.put("hostBUid", targetHostUid);
        pkDoc.put("hostBName", targetHostName);
        pkDoc.put("hostBPhoto", targetHostPhoto);
        pkDoc.put("liveIdB", targetLiveId);
        pkDoc.put("durationSec", durationSec);
        pkDoc.put("scoreA", 0);
        pkDoc.put("scoreB", 0);
        pkDoc.put("createdAtMs", System.currentTimeMillis());

        com.friendbook.social.core.DataRouter.mirrorPut("chats", "pkbattle_" + newPkId, pkDoc);
        Fb.db().collection("chats").document("pkbattle_" + newPkId).set(pkDoc);
        subscribeToPkBattle(newPkId);

        Map<String, Object> inv = new HashMap<>();
        inv.put("type", "pk_invite");
        inv.put("uid", me);
        inv.put("name", hostNameStr);
        inv.put("photo", hostPhotoStr);
        inv.put("targetUid", targetHostUid);
        inv.put("pkBattleId", newPkId);
        inv.put("durationSec", durationSec);
        inv.put("text", "challenged " + targetHostName + " to a PK Battle!");
        inv.put("createdAtMs", System.currentTimeMillis());
        addChatPacket(targetLiveId, inv);
        LivePkAndGiftsUi.sendDirectLiveInviteToUser(
                me, targetHostUid,
                hostId != null && !hostId.isEmpty() ? hostId : me,
                hostNameStr, hostPhotoStr, liveTitleStr, liveId);

        Ui.toast(this, "PK Challenge & Live Video sent to " + targetHostName + "'s Inbox!");
    }

    private void challengeViewerToPk(@NonNull String targetUid,
                                     @NonNull String targetName,
                                     @NonNull String targetPhoto,
                                     int durationSec) {
        String me = Fb.uid();
        if (me == null || liveId == null) return;
        String newPkId = liveId + "_" + System.currentTimeMillis();
        pkDurationSec = durationSec;

        Map<String, Object> pkDoc = new HashMap<>();
        pkDoc.put("pkBattleId", newPkId);
        pkDoc.put("status", "invited");
        pkDoc.put("hostAUid", me);
        pkDoc.put("hostAName", hostNameStr);
        pkDoc.put("hostAPhoto", hostPhotoStr);
        pkDoc.put("liveIdA", liveId);
        pkDoc.put("hostBUid", targetUid);
        pkDoc.put("hostBName", targetName);
        pkDoc.put("hostBPhoto", targetPhoto);
        pkDoc.put("liveIdB", liveId);
        pkDoc.put("durationSec", durationSec);
        pkDoc.put("scoreA", 0);
        pkDoc.put("scoreB", 0);
        pkDoc.put("createdAtMs", System.currentTimeMillis());

        com.friendbook.social.core.DataRouter.mirrorPut("chats", "pkbattle_" + newPkId, pkDoc);
        Fb.db().collection("chats").document("pkbattle_" + newPkId).set(pkDoc);
        subscribeToPkBattle(newPkId);

        Map<String, Object> inv = new HashMap<>();
        inv.put("type", "pk_invite");
        inv.put("uid", me);
        inv.put("name", hostNameStr);
        inv.put("photo", hostPhotoStr);
        inv.put("targetUid", targetUid);
        inv.put("pkBattleId", newPkId);
        inv.put("durationSec", durationSec);
        inv.put("text", "invited " + targetName + " to a Split-Screen PK Battle!");
        inv.put("createdAtMs", System.currentTimeMillis());
        addChatPacket(inv);

        LivePkAndGiftsUi.sendDirectLiveInviteToUser(
                me, targetUid,
                hostId != null && !hostId.isEmpty() ? hostId : me,
                hostNameStr, hostPhotoStr, liveTitleStr, liveId);

        Ui.toast(this, "PK Invite & Live Video sent to " + targetName + "'s Inbox!");
    }

    private void promptIncomingPkChallenge(final String incomingPkId,
                                           final String challengerUid,
                                           final String challengerName,
                                           final String challengerPhoto,
                                           final int durationSec) {
        if (isFinishing() || isEnded) return;
        int mins = Math.max(1, durationSec / 60);
        new AlertDialog.Builder(this)
                .setTitle("Incoming Live PK Battle!")
                .setMessage(challengerName + " challenged you to a " + mins
                        + "-minute Split-Screen Live Video + Voice PK Match!\n\nAccept to go side-by-side live now?")
                .setPositiveButton("Accept PK", (d, w) -> acceptIncomingPkBattle(incomingPkId, durationSec))
                .setNegativeButton("Decline", (d, w) -> Fb.db().collection("chats")
                        .document("pkbattle_" + incomingPkId)
                        .set(Fb.map("status", "declined"), SetOptions.merge()))
                .show();
    }

    private void acceptIncomingPkBattle(@NonNull String incomingPkId, int durationSec) {
        long now = System.currentTimeMillis();
        long ends = now + Math.max(60, durationSec) * 1000L;
        Map<String, Object> upd = new HashMap<>();
        upd.put("status", "active");
        upd.put("startedAtMs", now);
        upd.put("endsAtMs", ends);
        upd.put("scoreA", 0);
        upd.put("scoreB", 0);
        if (isHost && liveId != null) {
            upd.put("liveIdB", liveId);
        }
        com.friendbook.social.core.DataRouter.mirrorPut("chats", "pkbattle_" + incomingPkId, upd);
        Fb.db().collection("chats").document("pkbattle_" + incomingPkId).set(upd, SetOptions.merge());
        subscribeToPkBattle(incomingPkId);

        if (isHost && liveId != null) {
            Map<String, Object> liveUpd = new HashMap<>();
            liveUpd.put("pkActive", true);
            liveUpd.put("pkBattleId", incomingPkId);
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, liveUpd);
            Fb.db().collection("lives").document(liveId).update(liveUpd);
        }
    }

    private void subscribeToPkBattle(@NonNull String targetPkId) {
        if (targetPkId.isEmpty() || targetPkId.equals(subscribedPkBattleId)) return;
        cleanupPkListeners();
        subscribedPkBattleId = targetPkId;
        pkBattleId = targetPkId;
        engine.resetCoHostSequences();

        try {
            FirebaseDatabase rtdb = Fb.rtdb();
            if (rtdb != null) {
                pkRtdbARef = rtdb.getReference("pk_streams").child(targetPkId).child("a");
                pkRtdbBRef = rtdb.getReference("pk_streams").child(targetPkId).child("b");

                pkRtdbAListener = new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        if (isFinishing() || isEnded || !pkBattleActive || !snap.exists()) return;
                        if ("A".equals(pkRole)) return;
                        Boolean camOff = snap.child("camOff").getValue(Boolean.class);
                        if (Boolean.TRUE.equals(camOff)) {
                            pkLeftRemoteImg.setVisibility(View.GONE);
                            pkLeftFallbackBox.setVisibility(View.VISIBLE);
                        } else {
                            String v = snap.child("v").getValue(String.class);
                            Long vs = snap.child("vs").getValue(Long.class);
                            if (v != null && !v.isEmpty()) engine.decodeRemoteFrame(v, vs == null ? 0L : vs, false);
                        }
                        String a = snap.child("a").getValue(String.class);
                        Long as = snap.child("as").getValue(Long.class);
                        if (a != null && !a.isEmpty()) engine.enqueueRemoteAudio(a, as == null ? 0L : as, false);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) { }
                };
                pkRtdbARef.addValueEventListener(pkRtdbAListener);

                pkRtdbBListener = new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        if (isFinishing() || isEnded || !pkBattleActive || !snap.exists()) return;
                        if ("B".equals(pkRole)) return;
                        Boolean camOff = snap.child("camOff").getValue(Boolean.class);
                        if (Boolean.TRUE.equals(camOff)) {
                            pkRightRemoteImg.setVisibility(View.GONE);
                            pkRightFallbackBox.setVisibility(View.VISIBLE);
                        } else {
                            String v = snap.child("v").getValue(String.class);
                            Long vs = snap.child("vs").getValue(Long.class);
                            if (v != null && !v.isEmpty()) engine.decodeRemoteFrame(v, vs == null ? 0L : vs, true);
                        }
                        String a = snap.child("a").getValue(String.class);
                        Long as = snap.child("as").getValue(Long.class);
                        if (a != null && !a.isEmpty()) engine.enqueueRemoteAudio(a, as == null ? 0L : as, true);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) { }
                };
                pkRtdbBRef.addValueEventListener(pkRtdbBListener);
            }
        } catch (Throwable ignored) { }

        // PK signalling is read on the same tick as the live room, not on its own subscription.
        // Both sides already refresh every few seconds, so a second channel only doubles the
        // reads without making the battle start any sooner.
        pkBattleId = "pkbattle_" + targetPkId;
        pkMediaAId = "pkmedia_" + targetPkId + "_a";
        pkMediaBId = "pkmedia_" + targetPkId + "_b";
        fetchPkSignals();

    private void onPkBattleDocSnapshot(@NonNull com.friendbook.social.core.Doc snap) {
        Map<String, Object> m = snap.getData();
        if (m == null) return;
        String status = Format.str(m, "status");
        if ("declined".equals(status)) {
            if (Fb.uid() != null && Fb.uid().equals(Format.str(m, "hostAUid"))) {
                Ui.toast(this, "Opponent declined PK challenge");
            }
            teardownPkStageUi();
            return;
        }
        if ("ended".equals(status)) {
            if (!pkBattleId.isEmpty()) endedPkBattleIds.add(pkBattleId);
            if (isHost && liveId != null) {
                Map<String, Object> lu = new HashMap<>();
                lu.put("pkActive", false);
                lu.put("pkBattleId", "");
                com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, lu);
                Fb.db().collection("lives").document(liveId).update(lu);
            }
            teardownPkStageUi();
            return;
        }
        if (!"active".equals(status) && !"victory".equals(status)) {
            return;
        }

        String me = Fb.uid() == null ? "" : Fb.uid();
        pkHostAUid = Format.str(m, "hostAUid");
        pkHostAName = Format.strOr(m, "hostAName", "Host A");
        pkHostAPhoto = Format.str(m, "hostAPhoto");
        pkLiveIdA = Format.str(m, "liveIdA");
        pkHostBUid = Format.str(m, "hostBUid");
        pkHostBName = Format.strOr(m, "hostBName", "Host B");
        pkHostBPhoto = Format.str(m, "hostBPhoto");
        pkLiveIdB = Format.str(m, "liveIdB");
        pkScoreA = Format.intOf(m, "scoreA");
        pkScoreB = Format.intOf(m, "scoreB");
        pkDurationSec = Math.max(60, Format.intOf(m, "durationSec"));
        long newEndsAt = Format.longOf(m, "endsAtMs");

        // Detect Rematch reset!
        if (newEndsAt > System.currentTimeMillis() + 5000L && pkVictoryPhase) {
            pkVictoryPhase = false;
            pkLeftResultStamp.setVisibility(View.GONE);
            pkRightResultStamp.setVisibility(View.GONE);
            pkResultBanner.setVisibility(View.GONE);
        }
        pkEndsAtMs = newEndsAt;

        if (me.equals(pkHostAUid)) pkRole = "A";
        else if (me.equals(pkHostBUid)) pkRole = "B";
        else pkRole = "viewer";

        boolean wasActive = pkBattleActive;
        pkBattleActive = true;

        // Host A marks own live room `pkActive = true` as soon as B accepts!
        if (!wasActive && isHost && liveId != null) {
            Map<String, Object> lu = new HashMap<>();
            lu.put("pkActive", true);
            lu.put("pkBattleId", pkBattleId);
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, lu);
            Fb.db().collection("lives").document(liveId).update(lu);
            engine.startSpeakerPlayback();
            postSystemChat("PK BATTLE STARTED: " + pkHostAName + " VS " + pkHostBName + "!");
        }

        // If I am a Viewer who accepted PK (`!isHost && "B".equals(pkRole)`), start my PK Guest camera + mic!
        if (!isHost && "B".equals(pkRole) && !isMePkGuest) {
            isMePkGuest = true;
            ensureLivePermissions(REQ_PK_GUEST_PERMS);
        }

        if (!wasActive) {
            applyPkLayoutGeometry(true);
        }

        // Update PK Names, Avatars, Mute Buttons, Leave Match Button & Score Bar!
        pkLeftNameBadge.setText(pkHostAName);
        pkRightNameBadge.setText(pkHostBName);
        Ui.avatar(this, pkLeftAvatar, pkHostAPhoto);
        Ui.avatar(this, pkRightAvatar, pkHostBPhoto);
        pkLeftMuteBtn.setVisibility("A".equals(pkRole) ? View.GONE : View.VISIBLE);
        pkRightMuteBtn.setVisibility("B".equals(pkRole) ? View.GONE : View.VISIBLE);
        if (pkLeaveMatchBtn != null) {
            boolean canLeave = isHost || "A".equals(pkRole) || "B".equals(pkRole) || isMePkGuest;
            pkLeaveMatchBtn.setVisibility(canLeave ? View.VISIBLE : View.GONE);
        }
        liveBadgeTv.setText("PK LIVE");

        updatePkScoreBarUi();
        tickPkCountdown();
    }

    private void applyPkLayoutGeometry(boolean enterPk) {
        if (enterPk) {
            pkStageBackdrop.setVisibility(View.VISIBLE);
            pkStageOverlay.setVisibility(View.VISIBLE);
            remoteVideoView.setVisibility(View.GONE);
            fallbackStage.setVisibility(View.GONE);
            coHostCard.setVisibility(View.GONE);

            if (isHost) {
                if ("A".equals(pkRole)) {
                    pkLeftFallbackBox.setVisibility(View.GONE);
                    pkLeftRemoteImg.setVisibility(View.GONE);
                } else if ("B".equals(pkRole)) {
                    pkRightFallbackBox.setVisibility(View.GONE);
                    pkRightRemoteImg.setVisibility(View.GONE);
                }
                rootStage.post(() -> {
                    if (!pkBattleActive || rootStage.getWidth() <= 0) return;
                    int halfW = rootStage.getWidth() / 2;
                    int vidH = Ui.dp(this, PK_VIDEO_H_DP);
                    int topPx = Ui.dp(this, PK_STAGE_TOP_DP + PK_SCORE_BAR_H_DP);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(halfW, vidH, Gravity.TOP | Gravity.START);
                    lp.topMargin = topPx;
                    lp.leftMargin = "B".equals(pkRole) ? halfW : 0;
                    cameraTexture.setLayoutParams(lp);
                    if (hostBeautyOverlay != null) {
                        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(halfW, vidH, Gravity.TOP | Gravity.START);
                        blp.topMargin = topPx;
                        blp.leftMargin = "B".equals(pkRole) ? halfW : 0;
                        hostBeautyOverlay.setLayoutParams(blp);
                    }
                });
            }
        } else {
            pkStageBackdrop.setVisibility(View.GONE);
            pkStageOverlay.setVisibility(View.GONE);
            pkLeftRemoteImg.setVisibility(View.GONE);
            pkRightRemoteImg.setVisibility(View.GONE);
            pkLeftFallbackBox.setVisibility(View.VISIBLE);
            pkRightFallbackBox.setVisibility(View.VISIBLE);
            pkLeftResultStamp.setVisibility(View.GONE);
            pkRightResultStamp.setVisibility(View.GONE);
            pkResultBanner.setVisibility(View.GONE);
            if (pkLeaveMatchBtn != null) pkLeaveMatchBtn.setVisibility(View.GONE);
            liveBadgeTv.setText("LIVE");

            if (isHost) {
                cameraTexture.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
                cameraTexture.setVisibility(engine.isCameraPaused() ? View.GONE : View.VISIBLE);
                if (hostBeautyOverlay != null) {
                    hostBeautyOverlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
                }
            } else {
                remoteVideoView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updatePkScoreBarUi() {
        pkScoreATv.setText(Format.count(pkScoreA));
        pkScoreBTv.setText(Format.count(pkScoreB));
        float wA = 1f;
        float wB = 1f;
        int sum = pkScoreA + pkScoreB;
        if (sum > 0) {
            float ratioA = Math.max(0.15f, Math.min(0.85f, (float) pkScoreA / (float) sum));
            wA = ratioA * 100f;
            wB = (1f - ratioA) * 100f;
        }
        pkRedBar.setLayoutParams(new LinearLayout.LayoutParams(0, -1, wA));
        pkBlueBar.setLayoutParams(new LinearLayout.LayoutParams(0, -1, wB));
    }

    private void tickPkCountdown() {
        if (!pkBattleActive || pkTimerPillTv == null || pkEndsAtMs <= 0L) return;
        long remMs = pkEndsAtMs - System.currentTimeMillis();
        if (remMs > 0L) {
            long remSec = remMs / 1000L;
            pkTimerPillTv.setText(String.format(Locale.US, "PK %02d:%02d", remSec / 60L, remSec % 60L));
        } else {
            pkTimerPillTv.setText("PK 00:00");
            if (!pkVictoryPhase) {
                pkVictoryPhase = true;
                declarePkMatchWinner();
            }
        }
    }

    private void declarePkMatchWinner() {
        String roundKey = pkBattleId + "_" + pkEndsAtMs;
        boolean firstScoreForRound = !roundKey.equals(lastScoredPkRoundKey);
        if (firstScoreForRound) {
            lastScoredPkRoundKey = roundKey;
            pkPlayedCount++;
            if (("A".equals(pkRole) && pkScoreA > pkScoreB)
                    || ("B".equals(pkRole) && pkScoreB > pkScoreA)) {
                pkWinsCount++;
            }
        }

        pkLeftResultStamp.setVisibility(View.VISIBLE);
        pkRightResultStamp.setVisibility(View.VISIBLE);
        pkResultBanner.setVisibility(View.VISIBLE);
        boolean canControl = "A".equals(pkRole) || "B".equals(pkRole) || isHost;
        pkRematchBtn.setVisibility(canControl ? View.VISIBLE : View.GONE);
        pkExitPkBtn.setVisibility(canControl ? View.VISIBLE : View.GONE);

        if (pkScoreA > pkScoreB) {
            pkLeftResultStamp.setText("VICTORY");
            pkLeftResultStamp.setBackground(Ui.round(this, 0xEEFFB300, 14));
            pkRightResultStamp.setText("DEFEAT");
            pkRightResultStamp.setBackground(Ui.round(this, 0xCC263238, 14));
            pkWinnerHeadlineTv.setText("WINNER: " + pkHostAName + " (" + pkScoreA + " vs " + pkScoreB + ")!");
        } else if (pkScoreB > pkScoreA) {
            pkRightResultStamp.setText("VICTORY");
            pkRightResultStamp.setBackground(Ui.round(this, 0xEEFFB300, 14));
            pkLeftResultStamp.setText("DEFEAT");
            pkLeftResultStamp.setBackground(Ui.round(this, 0xCC263238, 14));
            pkWinnerHeadlineTv.setText("WINNER: " + pkHostBName + " (" + pkScoreB + " vs " + pkScoreA + ")!");
        } else {
            pkLeftResultStamp.setText("TIE");
            pkRightResultStamp.setText("TIE");
            pkLeftResultStamp.setBackground(Ui.round(this, 0xCC1877F2, 14));
            pkRightResultStamp.setBackground(Ui.round(this, 0xCC1877F2, 14));
            pkWinnerHeadlineTv.setText("DRAW MATCH (" + pkScoreA + " - " + pkScoreB + ")!");
        }

        spawnFloatingEmoji("");
        spawnFloatingEmoji("");
        spawnFloatingEmoji("");
    }

    private void startPkRematchNow() {
        if (pkBattleId.isEmpty()) return;
        long now = System.currentTimeMillis();
        Map<String, Object> reset = new HashMap<>();
        reset.put("status", "active");
        reset.put("scoreA", 0);
        reset.put("scoreB", 0);
        reset.put("startedAtMs", now);
        reset.put("endsAtMs", now + pkDurationSec * 1000L);
        com.friendbook.social.core.DataRouter.mirrorPut("chats", "pkbattle_" + pkBattleId, reset);
        Fb.db().collection("chats").document("pkbattle_" + pkBattleId).set(reset, SetOptions.merge());
        postSystemChat("PK REMATCH STARTED!");
    }

    private void promptLeaveCurrentMatch() {
        Ui.confirm(this, "Leave Live Match?",
                "You will exit the current split-screen match while staying in the Live stream.",
                "Leave Match", this::endActivePkBattle);
    }

    private void endActivePkBattle() {
        String oldPkId = pkBattleId;
        String oldLiveA = pkLiveIdA;
        String oldLiveB = pkLiveIdB;
        if (!oldPkId.isEmpty()) {
            endedPkBattleIds.add(oldPkId);
            Map<String, Object> endDoc = new HashMap<>();
            endDoc.put("status", "ended");
            endDoc.put("endedByUid", Fb.uid() == null ? "" : Fb.uid());
            endDoc.put("endedByName", Fb.myName());
            Fb.db().collection("chats").document("pkbattle_" + oldPkId)
                    .set(endDoc, SetOptions.merge());
        }
        if (isHost && liveId != null) {
            Map<String, Object> lu = new HashMap<>();
            lu.put("pkActive", false);
            lu.put("pkBattleId", "");
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, lu);
            Fb.db().collection("lives").document(liveId).update(lu);
        }
        Map<String, Object> leavePkt = new HashMap<>();
        leavePkt.put("type", "pk_leave");
        leavePkt.put("uid", Fb.uid() == null ? "" : Fb.uid());
        leavePkt.put("name", Fb.myName());
        leavePkt.put("pkBattleId", oldPkId);
        leavePkt.put("text", "left the Live Match");
        leavePkt.put("createdAtMs", System.currentTimeMillis());
        if (liveId != null && !liveId.isEmpty()) {
            addChatPacket(leavePkt);
        }
        if (!oldLiveA.isEmpty() && !oldLiveA.equals(liveId)) {
            addChatPacket(oldLiveA, leavePkt);
        }
        if (!oldLiveB.isEmpty() && !oldLiveB.equals(liveId) && !oldLiveB.equals(oldLiveA)) {
            addChatPacket(oldLiveB, leavePkt);
        }
        teardownPkStageUi();
        Ui.toast(this, "Left Live Match");
    }

    private void teardownPkStageUi() {
        pkBattleActive = false;
        pkVictoryPhase = false;
        pkRole = "";
        pkBattleId = "";
        stopLocalPkGuestStreaming();
        cleanupPkListeners();
        applyPkLayoutGeometry(false);
    }

    private void activateLocalPkGuestStreaming() {
        if (!isMePkGuest || isFinishing()) return;
        pkRightFallbackBox.setVisibility(View.GONE);
        pkRightRemoteImg.setVisibility(View.GONE);
        pkRightGuestTexture.setVisibility(View.VISIBLE);
        if (pkGuestEngine == null) {
            pkGuestEngine = new LiveStreamEngine(this, new LiveStreamEngine.Listener() {
                @Override
                public void onEncodedVideoFrame(String base64Webp, int width, int height) {
                    pushPkGuestMediaPacket(base64Webp, null);
                }
                @Override
                public void onEncodedAudioChunk(String base64Ulaw, int voiceLevel) {
                    pushPkGuestMediaPacket(null, base64Ulaw);
                }
                @Override
                public void onLocalVoiceLevel(int voiceLevel) { }
                @Override
                public void onDecodedRemoteVideo(Bitmap bitmap, boolean isCoHost) { }
                @Override
                public void onCameraError(String message) { }
            });
            pkGuestEngine.setStreamQuality(LiveStreamEngine.QUALITY_BALANCED);
            pkGuestEngine.setBeautyLevel(engine.getBeautyLevel());
        }
        pkGuestEngine.attachCameraPreview(pkRightGuestTexture, true);
        pkGuestEngine.attachBeautyOverlay(pkGuestBeautyOverlay);
        pkGuestEngine.startBroadcasting(true);
    }

    private void pushPkGuestMediaPacket(@Nullable String vB64, @Nullable String aB64) {
        if (!isMePkGuest || !pkBattleActive || pkBattleId.isEmpty() || isEnded) return;
        long now = System.currentTimeMillis();
        long seq = ++pkSeqOut;
        if (pkRtdbBRef != null) {
            Map<String, Object> fast = new HashMap<>();
            if (vB64 != null) {
                fast.put("v", vB64);
                fast.put("vs", seq);
                fast.put("camOff", false);
            }
            if (aB64 != null) {
                fast.put("a", aB64);
                fast.put("as", seq);
            }
            try { pkRtdbBRef.updateChildren(fast); } catch (Throwable ignored) { }
        }
        if (vB64 != null && now - lastPkMediaPushMs < 380L) return;
        if (vB64 != null) lastPkMediaPushMs = now;
        Map<String, Object> pkt = new HashMap<>();
        if (vB64 != null) {
            pkt.put("v", vB64);
            pkt.put("vs", seq);
            pkt.put("camOff", false);
        }
        if (aB64 != null) {
            pkt.put("a", aB64);
            pkt.put("as", seq);
        }
        com.friendbook.social.core.DataRouter.mirrorPut("chats", "pkmedia_" + pkBattleId + "_b", pkt);
        Fb.db().collection("chats").document("pkmedia_" + pkBattleId + "_b").set(pkt, SetOptions.merge());
    }

    private void stopLocalPkGuestStreaming() {
        isMePkGuest = false;
        if (pkGuestEngine != null) {
            pkGuestEngine.releaseAll();
            pkGuestEngine = null;
        }
        if (pkRightGuestTexture != null) pkRightGuestTexture.setVisibility(View.GONE);
        if (pkGuestBeautyOverlay != null) pkGuestBeautyOverlay.setVisibility(View.GONE);
    }

    private void cleanupPkListeners() {
        subscribedPkBattleId = "";
        if (pkRtdbARef != null && pkRtdbAListener != null) {
            try { pkRtdbARef.removeEventListener(pkRtdbAListener); } catch (Throwable ignored) { }
            pkRtdbAListener = null;
        }
        if (pkRtdbBRef != null && pkRtdbBListener != null) {
            try { pkRtdbBRef.removeEventListener(pkRtdbBListener); } catch (Throwable ignored) { }
            pkRtdbBListener = null;
        }
        if (pkBattleReg != null) { pkBattleReg.remove(); pkBattleReg = null; }
        if (pkMediaAReg != null) { pkMediaAReg.remove(); pkMediaAReg = null; }
        if (pkMediaBReg != null) { pkMediaBReg.remove(); pkMediaBReg = null; }
    }

    // ==================== ENDING BROADCAST & POST-LIVE ANALYTICS SUMMARY ====================

    private void confirmExitOrEnd() {
        if (!isLiveStarted) {
            finish();
            return;
        }
        boolean inPkMatch = pkBattleActive && (isHost || "A".equals(pkRole) || "B".equals(pkRole) || isMePkGuest);
        boolean inCoHostMatch = isMeActiveCoHost || (isHost && !activeCoHostUid.isEmpty());
        if (inPkMatch) {
            String[] opts = {
                    "Leave Match Only (Stay in Live)",
                    isHost ? "End Entire Live Broadcast" : "Exit Live Room"
            };
            Ui.choose(this, "Live Match Active", opts, which -> {
                if (which == 0) endActivePkBattle();
                else if (isHost) endHostBroadcastAndShowSummary();
                else finish();
            });
            return;
        }
        if (inCoHostMatch) {
            String[] opts = {
                    isHost ? "End Co-Host Match (Return to Solo Live)" : "Leave Co-Host Match (Keep Watching)",
                    isHost ? "End Entire Live Broadcast" : "Exit Live Room"
            };
            Ui.choose(this, "Co-Host Match Active", opts, which -> {
                if (which == 0) removeActiveCoHost();
                else if (isHost) endHostBroadcastAndShowSummary();
                else finish();
            });
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
        if (pkBattleActive) {
            endActivePkBattle();
        }
        engine.stopBroadcasting();
        engine.stopSpeakerPlayback();

        final long durSec = liveStartMs > 0 ? Math.max(1L, (System.currentTimeMillis() - liveStartMs) / 1000L) : 0L;
        if (liveId != null) {
            Map<String, Object> endMap = new HashMap<>();
            endMap.put("status", "ended");
            endMap.put("endedAt", FieldValue.serverTimestamp());
            endMap.put("durationSec", durSec);
            endMap.put("peakViewers", peakViewers);
            endMap.put("pkActive", false);
            endMap.put("pkBattleId", "");
            endMap.put("liveFrameB64", "");
            endMap.put("audioB64", "");
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, endMap);
            Fb.db().collection("lives").document(liveId).update(endMap);
            if (rtdbStreamRef != null) {
                try { rtdbStreamRef.removeValue(); } catch (Throwable ignored) { }
            }
        }

        liveHudOverlay.setVisibility(View.GONE);
        View resultOverlay = LivePkAndGiftsUi.buildPostLiveSummaryOverlay(
                this,
                hostPhotoStr,
                hostNameStr,
                liveTitleStr,
                durSec,
                Math.max(1, totalUniqueViewers),
                Math.max(1, peakViewers),
                totalGiftsCount,
                totalStarsReceived,
                totalLikes,
                totalComments,
                pkWinsCount,
                pkPlayedCount,
                supporterStars,
                supporterNames,
                true,
                () -> {
                    publishRecapPost(durSec);
                    finish();
                },
                this::finish);
        rootStage.addView(resultOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void publishRecapPost(long durSec) {
        Map<String, Object> post = new HashMap<>();
        post.put("authorId", Fb.uid());
        post.put("authorName", Fb.myName());
        post.put("authorPhotoUrl", Fb.myPhoto() == null ? "" : Fb.myPhoto());
        post.put("text", String.format(Locale.US,
                "Just finished a %02d:%02d Live Broadcast: \"%s\"!\nThank you to %d viewers & everyone who sent %d Stars!",
                durSec / 60, durSec % 60, liveTitleStr, totalUniqueViewers, totalStarsReceived));
        post.put("privacy", privacyFriendsOnly ? "friends" : "public");
        post.put("likesCount", 0);
        post.put("commentsCount", 0);
        post.put("sharesCount", 0);
        post.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("posts").add(post);
        Ui.toast(this, "Live recap shared to Feed!");
    }

    private void showViewerBroadcastEndedDialog() {
        engine.stopSpeakerPlayback();
        stopLocalCoHostStreaming();
        stopLocalPkGuestStreaming();
        cleanupPkListeners();
        if (isFinishing()) return;
        long durSec = liveStartMs > 0 ? Math.max(1L, (System.currentTimeMillis() - liveStartMs) / 1000L) : 0L;
        liveHudOverlay.setVisibility(View.GONE);
        View resultOverlay = LivePkAndGiftsUi.buildPostLiveSummaryOverlay(
                this,
                hostPhotoStr,
                hostNameStr,
                liveTitleStr,
                durSec,
                Math.max(1, totalUniqueViewers),
                Math.max(1, peakViewers),
                totalGiftsCount,
                totalStarsReceived,
                totalLikes,
                totalComments,
                pkWinsCount,
                pkPlayedCount,
                supporterStars,
                supporterNames,
                false,
                null,
                this::finish);
        rootStage.addView(resultOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    public void onBackPressed() {
        if (isEnded) {
            finish();
            return;
        }
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
        // Stop polling when the room is not visible, or a backgrounded broadcast keeps
        // reading D1 forever.
        if (chatPollRunnable != null) { livePollHandler.removeCallbacks(chatPollRunnable); chatPollRunnable = null; }
        cleanupPkListeners();
        if (rtdbStreamRef != null && rtdbStreamListener != null) {
            try { rtdbStreamRef.removeEventListener(rtdbStreamListener); } catch (Throwable ignored) { }
        }

        if (isHost && isLiveStarted && !isEnded && liveId != null) {
            Map<String, Object> endMap = new HashMap<>();
            endMap.put("status", "ended");
            endMap.put("endedAt", FieldValue.serverTimestamp());
            endMap.put("pkActive", false);
            endMap.put("pkBattleId", "");
            endMap.put("liveFrameB64", "");
            endMap.put("audioB64", "");
            com.friendbook.social.core.DataRouter.mirrorPut("lives", liveId, endMap);
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
            addChatPacket(leave);
        }

        stopLocalCoHostStreaming();
        stopLocalPkGuestStreaming();
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
            if (it.uid.equals(hostId)) badge = "  ·  Host";
            else if (moderatorIds.contains(it.uid)) badge = "  ·  Mod";

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
