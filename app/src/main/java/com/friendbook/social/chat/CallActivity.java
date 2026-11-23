package com.friendbook.social.chat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.friendbook.social.live.LiveStreamEngine;
import com.friendbook.social.d1.documents.DocumentReference;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.ListenerRegistration;
import com.friendbook.social.d1.documents.SetOptions;

import com.friendbook.social.live.LiveRelayClient;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real-time 2-Way Voice Call & Video Call screen for friendbook Chat.
 * Uses Firestore signaling on `chats/{chatId}.activeCall` + full-duplex isolated media channels
 * `chats/callmedia_{callId}_a` and `chats/callmedia_{callId}_b` powered by {@link LiveStreamEngine}.
 */
public class CallActivity extends Activity implements LiveStreamEngine.Listener {

    private static final int REQ_CALL_PERMS = 4401;
    private static final long RING_TIMEOUT_MS = 45_000L;

    private static volatile boolean activeCallScreenOpen = false;
    private static final Set<String> handledCallIds = Collections.synchronizedSet(new HashSet<>());

    public static boolean isBusy() {
        return activeCallScreenOpen;
    }

    /** Launch an outgoing Voice or Video Call from ChatActivity. */
    public static void startOutgoing(@NonNull Context c,
                                     @NonNull String chatId,
                                     @Nullable String peerId,
                                     @Nullable String peerName,
                                     @Nullable String peerPhoto,
                                     boolean isGroup,
                                     boolean isVideo) {
        String me = Fb.uid();
        if (me == null || chatId.isEmpty()) return;
        if (isGroup) { Ui.toast(c, "Calls are available in direct chats only"); return; }
        if (peerId != null && Ui.TEAM_USER_ID.equals(peerId)) {
            Ui.toast(c, "Direct calls to official support account are unavailable");
            return;
        }
        String callId = java.util.UUID.randomUUID().toString();
        handledCallIds.add(callId);

        Intent i = new Intent(c, CallActivity.class);
        i.putExtra("mode", "outgoing");
        i.putExtra("callId", callId);
        i.putExtra("chatId", chatId);
        i.putExtra("peerId", peerId == null ? "" : peerId);
        i.putExtra("peerName", peerName == null || peerName.isEmpty() ? "friendbook User" : peerName);
        i.putExtra("peerPhoto", peerPhoto == null ? "" : peerPhoto);
        i.putExtra("isGroup", isGroup);
        i.putExtra("isVideo", isVideo);
        if (!(c instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        c.startActivity(i);
    }

    /**
     * Global hook invoked by MainActivity / ConversationsActivity / ChatActivity whenever a
     * `chats/{chatId}` document snapshot updates. Automatically opens Incoming Call UI when ringing!
     */
    @SuppressWarnings("unchecked")
    public static void maybeHandleIncomingFromRoomDoc(@Nullable Context c, @Nullable DocumentSnapshot roomDoc) {
        if (c == null || roomDoc == null || !roomDoc.exists() || roomDoc.getMetadata().isFromCache()) return;
        String me = Fb.uid();
        if (me == null || activeCallScreenOpen) return;

        Object raw = roomDoc.get("activeCall");
        if (!(raw instanceof Map)) return;
        Map<String, Object> ac = (Map<String, Object>) raw;

        String status = Format.str(ac, "status");
        if (!"ringing".equals(status) || !me.equals(Format.str(ac, "calleeId"))
                || Boolean.TRUE.equals(roomDoc.getBoolean("isGroup"))) return;

        String callerId = Format.str(ac, "callerId");
        if (callerId.isEmpty() || callerId.equals(me)) return;

        String callId = Format.str(ac, "callId");
        if (callId.isEmpty() || handledCallIds.contains(callId)) return;

        long startedAt = 0L;
        Object stObj = ac.get("startedAt");
        if (stObj instanceof Number) startedAt = ((Number) stObj).longValue();
        else startedAt = Format.ts(stObj);
        if (startedAt <= 0L || Math.abs(System.currentTimeMillis() - startedAt) > RING_TIMEOUT_MS) {
            return;
        }

        // Respect blocked users
        if (Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(callerId)) {
            return;
        }

        handledCallIds.add(callId);
        boolean isGroup = Boolean.TRUE.equals(roomDoc.getBoolean("isGroup"));
        String callerName = Format.strOr(ac, "callerName", "friendbook User");
        String callerPhoto = Format.str(ac, "callerPhoto");
        if (isGroup) {
            String gName = roomDoc.getString("groupName");
            if (gName != null && !gName.trim().isEmpty()) {
                callerName = callerName + " · " + gName.trim();
            }
        }
        boolean isVideo = "video".equalsIgnoreCase(Format.str(ac, "callType"));

        Intent i = new Intent(c, CallActivity.class);
        i.putExtra("mode", "incoming");
        i.putExtra("callId", callId);
        i.putExtra("chatId", roomDoc.getId());
        i.putExtra("peerId", callerId);
        i.putExtra("peerName", callerName);
        i.putExtra("peerPhoto", callerPhoto);
        i.putExtra("isGroup", isGroup);
        i.putExtra("isVideo", isVideo);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            c.startActivity(i);
        } catch (Throwable ignored) { }
    }

    // ---- Instance fields ----
    private String mode = "outgoing"; // "outgoing" | "incoming"
    private String callId = "";
    private String chatId = "";
    private String peerId = "";
    private String peerName = "";
    private String peerPhoto = "";
    private boolean isGroup = false;
    private volatile LiveRelayClient mediaRelay;
    private boolean accepting;
    private boolean isVideo = false;
    private boolean isCaller = true;

    private volatile boolean connected = false;
    private volatile boolean ended = false;
    private volatile boolean micMuted = false;
    private boolean speakerOn = true;
    private volatile boolean cameraEnabled = false;
    private long connectedAtMs = 0L;

    private volatile LiveStreamEngine engine;
    private DocumentReference chatDocRef;
    private ListenerRegistration signalReg;

    private final AtomicLong localVideoSeq = new AtomicLong(0);
    private final AtomicLong localAudioSeq = new AtomicLong(0);
    private volatile long lastVideoPushMs = 0L;

    private FrameLayout root;
    private ImageView remoteVideoView;
    private View darkScrim;
    private LinearLayout centerProfileBox;
    private View voicePulseRing;
    private ImageView peerAvatarView;
    private TextView peerNameTv;
    private TextView statusTv;

    private FrameLayout localPipCard;
    private TextureView localPreviewTexture;
    private ImageView flipCamFloatingBtn;

    private LinearLayout incomingControlsRow;
    private LinearLayout activeControlsRow;
    private ImageView btnMicToggle;
    private ImageView btnSpeakerToggle;
    private ImageView btnVideoToggle;
    private ImageView btnFlipCam;

    private AudioManager audioManager;
    private int savedAudioMode = AudioManager.MODE_NORMAL;
    private boolean savedSpeakerphone = false;
    private ToneGenerator toneGen;
    private Vibrator vibrator;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable durationTicker = new Runnable() {
        @Override
        public void run() {
            if (ended || isFinishing()) return;
            if (connected && connectedAtMs > 0L) {
                long sec = Math.max(0L, (System.currentTimeMillis() - connectedAtMs) / 1000L);
                statusTv.setText(formatDuration(sec));
            }
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private final Runnable ringTimeoutRunnable = () -> {
        if (!connected && !ended && !isFinishing()) {
            endCallInternal(isCaller ? "missed" : "ended", true);
        }
    };

    private final Runnable ringToneLoop = new Runnable() {
        @Override
        public void run() {
            if (connected || ended || isFinishing()) return;
            try {
                if (toneGen == null) {
                    int stream = isCaller ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_RING;
                    toneGen = new ToneGenerator(stream, 75);
                }
                toneGen.startTone(isCaller
                        ? ToneGenerator.TONE_SUP_RINGTONE
                        : ToneGenerator.TONE_CDMA_HIGH_L, 700);
            } catch (Throwable ignored) { }
            if (!isCaller && vibrator != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(450);
                    }
                } catch (Throwable ignored) { }
            }
            mainHandler.postDelayed(this, 2200L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeCallScreenOpen = true;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        com.friendbook.social.core.Ui.statusBar(getWindow(), 0xFF0E1117);

        Intent in = getIntent();
        mode = in.getStringExtra("mode");
        if (mode == null) mode = "outgoing";
        isCaller = "outgoing".equals(mode);
        callId = in.getStringExtra("callId");
        chatId = in.getStringExtra("chatId");
        peerId = in.getStringExtra("peerId");
        peerName = in.getStringExtra("peerName");
        peerPhoto = in.getStringExtra("peerPhoto");
        isGroup = in.getBooleanExtra("isGroup", false);
        isVideo = in.getBooleanExtra("isVideo", false);

        if (callId == null || callId.isEmpty() || chatId == null || chatId.isEmpty() || Fb.uid() == null) {
            activeCallScreenOpen = false;
            finish();
            return;
        }
        handledCallIds.add(callId);
        cameraEnabled = isVideo;
        speakerOn = isVideo; // Video calls default to speakerphone; voice calls default to earpiece (toggleable!)

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            savedAudioMode = audioManager.getMode();
            savedSpeakerphone = audioManager.isSpeakerphoneOn();
        }
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        chatDocRef = Fb.db().collection("chats").document(chatId);
        engine = new LiveStreamEngine(this, this);
        engine.setStreamQuality(LiveStreamEngine.QUALITY_DATA_SAVER);

        buildCallUi();
        attachSignalingListener();

        if (isCaller) {
            publishOutgoingCallSignal();
            ensureCallPermissionsAndPreparePreview(false);
            startRingingFeedback();
            mainHandler.postDelayed(ringTimeoutRunnable, RING_TIMEOUT_MS);
        } else {
            startRingingFeedback();
            mainHandler.postDelayed(ringTimeoutRunnable, RING_TIMEOUT_MS);
        }
    }

    private void buildCallUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF10131A);

        // 1. Fullscreen remote video surface
        remoteVideoView = new ImageView(this);
        remoteVideoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        remoteVideoView.setVisibility(View.GONE);
        root.addView(remoteVideoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Subtle dark gradient scrim for readability
        darkScrim = new View(this);
        GradientDrawable scrimBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x990B0E14, 0x330B0E14, 0xCC0B0E14});
        darkScrim.setBackground(scrimBg);
        root.addView(darkScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 2. Top minimize/back bar + encryption badge
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 10));

        ImageView backIv = new ImageView(this);
        backIv.setImageResource(Icons.BACK);
        backIv.setColorFilter(Color.WHITE);
        int bp = Ui.dp(this, 8);
        backIv.setPadding(bp, bp, bp, bp);
        backIv.setOnClickListener(v -> endCallInternal(connected ? "completed" : (isCaller ? "cancelled" : "declined"), true));
        topBar.addView(backIv, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        TextView encBadge = new TextView(this);
        encBadge.setText(isVideo ? "friendbook Video Call" : "friendbook Voice Call");
        encBadge.setTextColor(0xCCFFFFFF);
        encBadge.setTextSize(14f);
        encBadge.setTypeface(Typeface.DEFAULT_BOLD);
        encBadge.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(encBadge, ebLp);

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        root.addView(topBar, topLp);

        // 3. Center caller/callee avatar + name + live status
        centerProfileBox = new LinearLayout(this);
        centerProfileBox.setOrientation(LinearLayout.VERTICAL);
        centerProfileBox.setGravity(Gravity.CENTER_HORIZONTAL);
        centerProfileBox.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), Ui.dp(this, 60));

        FrameLayout avatarWrap = new FrameLayout(this);
        int ringSize = Ui.dp(this, 136);
        voicePulseRing = new View(this);
        GradientDrawable pulseDrawable = new GradientDrawable();
        pulseDrawable.setShape(GradientDrawable.OVAL);
        pulseDrawable.setColor(0x280084FF);
        pulseDrawable.setStroke(Ui.dp(this, 2), 0x880084FF);
        voicePulseRing.setBackground(pulseDrawable);
        avatarWrap.addView(voicePulseRing, new FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER));

        int avSize = Ui.dp(this, 114);
        peerAvatarView = new ImageView(this);
        peerAvatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams avLp = new FrameLayout.LayoutParams(avSize, avSize, Gravity.CENTER);
        avatarWrap.addView(peerAvatarView, avLp);
        Ui.avatar(this, peerAvatarView, peerPhoto);
        centerProfileBox.addView(avatarWrap, new LinearLayout.LayoutParams(ringSize, ringSize));

        peerNameTv = new TextView(this);
        peerNameTv.setText(peerName == null || peerName.isEmpty() ? "friendbook User" : peerName);
        peerNameTv.setTextColor(Color.WHITE);
        peerNameTv.setTextSize(24f);
        peerNameTv.setTypeface(Typeface.DEFAULT_BOLD);
        peerNameTv.setGravity(Gravity.CENTER);
        peerNameTv.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        centerProfileBox.addView(peerNameTv);

        statusTv = new TextView(this);
        statusTv.setText(isCaller
                ? (isVideo ? "Video calling…" : "Calling…")
                : (isVideo ? "Incoming video call…" : "Incoming voice call…"));
        statusTv.setTextColor(0xCCFFFFFF);
        statusTv.setTextSize(15.5f);
        statusTv.setGravity(Gravity.CENTER);
        centerProfileBox.addView(statusTv);

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        centerLp.gravity = Gravity.CENTER;
        root.addView(centerProfileBox, centerLp);

        // 4. Floating draggable Local Camera PiP Card (top-right)
        localPipCard = new FrameLayout(this);
        GradientDrawable pipBg = new GradientDrawable();
        pipBg.setColor(0xFF1F2430);
        pipBg.setCornerRadius(Ui.dp(this, 16));
        pipBg.setStroke(Ui.dp(this, 1), 0x55FFFFFF);
        localPipCard.setBackground(pipBg);
        localPipCard.setClipToOutline(true);
        localPipCard.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        localPipCard.setElevation(Ui.dp(this, 8));

        localPreviewTexture = new TextureView(this);
        localPipCard.addView(localPreviewTexture, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        flipCamFloatingBtn = new ImageView(this);
        flipCamFloatingBtn.setImageResource(Icons.CAM_SWITCH);
        flipCamFloatingBtn.setColorFilter(Color.WHITE);
        GradientDrawable miniCircle = new GradientDrawable();
        miniCircle.setShape(GradientDrawable.OVAL);
        miniCircle.setColor(0x77000000);
        flipCamFloatingBtn.setBackground(miniCircle);
        int fp = Ui.dp(this, 6);
        flipCamFloatingBtn.setPadding(fp, fp, fp, fp);
        flipCamFloatingBtn.setOnClickListener(v -> {
            if (engine != null) engine.flipCamera();
        });
        FrameLayout.LayoutParams flipLp = new FrameLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30));
        flipLp.gravity = Gravity.BOTTOM | Gravity.END;
        flipLp.setMargins(0, 0, Ui.dp(this, 6), Ui.dp(this, 6));
        localPipCard.addView(flipCamFloatingBtn, flipLp);

        enablePipDrag(localPipCard);
        localPipCard.setVisibility(cameraEnabled ? View.VISIBLE : View.GONE);

        FrameLayout.LayoutParams pipLp = new FrameLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 160));
        pipLp.gravity = Gravity.TOP | Gravity.END;
        pipLp.setMargins(Ui.dp(this, 14), Ui.dp(this, 72), Ui.dp(this, 14), Ui.dp(this, 14));
        root.addView(localPipCard, pipLp);

        // 5. Bottom Control Dock (Incoming Accept/Decline vs Active/Outgoing Controls)
        LinearLayout bottomDock = new LinearLayout(this);
        bottomDock.setOrientation(LinearLayout.VERTICAL);
        bottomDock.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomDock.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 34));

        // 5A. Incoming Ringing Row (Decline + Accept)
        incomingControlsRow = new LinearLayout(this);
        incomingControlsRow.setOrientation(LinearLayout.HORIZONTAL);
        incomingControlsRow.setGravity(Gravity.CENTER);

        LinearLayout declineCol = buildLabeledActionCol(
                Icons.CLOSE, 0xFFE53935, Color.WHITE, 64, "Decline",
                v -> endCallInternal("declined", true));
        LinearLayout acceptCol = buildLabeledActionCol(
                isVideo ? Icons.VIDEOCAM_FILLED : Icons.CALL_FILLED,
                0xFF2DBE60, Color.WHITE, 64, "Accept",
                v -> acceptIncomingCall());

        LinearLayout.LayoutParams incBtnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        incBtnLp.setMargins(Ui.dp(this, 36), 0, Ui.dp(this, 36), 0);
        incomingControlsRow.addView(declineCol, incBtnLp);
        incomingControlsRow.addView(acceptCol, incBtnLp);

        // 5B. Active / Outgoing Call Pill Row
        activeControlsRow = new LinearLayout(this);
        activeControlsRow.setOrientation(LinearLayout.HORIZONTAL);
        activeControlsRow.setGravity(Gravity.CENTER);
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(0xBB1C202B);
        dockBg.setCornerRadius(Ui.dp(this, 36));
        activeControlsRow.setBackground(dockBg);
        activeControlsRow.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));

        btnMicToggle = buildRoundControlButton(Icons.MIC_FILLED, 50, 0x33FFFFFF, Color.WHITE);
        btnMicToggle.setOnClickListener(v -> toggleMicMute());

        btnVideoToggle = buildRoundControlButton(Icons.VIDEOCAM_FILLED, 50,
                cameraEnabled ? Color.WHITE : 0x33FFFFFF,
                cameraEnabled ? 0xFF10131A : Color.WHITE);
        btnVideoToggle.setOnClickListener(v -> toggleCameraEnabled());

        btnFlipCam = buildRoundControlButton(Icons.CAM_SWITCH, 50, 0x33FFFFFF, Color.WHITE);
        btnFlipCam.setVisibility(cameraEnabled ? View.VISIBLE : View.GONE);
        btnFlipCam.setOnClickListener(v -> {
            if (engine != null) engine.flipCamera();
        });

        btnSpeakerToggle = buildRoundControlButton(Icons.VOLUME_ON, 50,
                speakerOn ? Color.WHITE : 0x33FFFFFF,
                speakerOn ? 0xFF10131A : Color.WHITE);
        btnSpeakerToggle.setOnClickListener(v -> toggleSpeakerphone());

        ImageView btnHangup = buildRoundControlButton(Icons.CALL_FILLED, 56, 0xFFE53935, Color.WHITE);
        btnHangup.setRotation(135f);
        btnHangup.setOnClickListener(v -> endCallInternal(
                connected ? "completed" : "cancelled", true));

        addControlWithMargin(activeControlsRow, btnMicToggle);
        addControlWithMargin(activeControlsRow, btnVideoToggle);
        addControlWithMargin(activeControlsRow, btnFlipCam);
        addControlWithMargin(activeControlsRow, btnSpeakerToggle);
        addControlWithMargin(activeControlsRow, btnHangup);

        incomingControlsRow.setVisibility(isCaller ? View.GONE : View.VISIBLE);
        activeControlsRow.setVisibility(isCaller ? View.VISIBLE : View.GONE);

        bottomDock.addView(incomingControlsRow);
        bottomDock.addView(activeControlsRow);

        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        dockLp.gravity = Gravity.BOTTOM;
        root.addView(bottomDock, dockLp);

        setContentView(root);
    }

    private void addControlWithMargin(LinearLayout row, View child) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(child.getLayoutParams());
        lp.setMargins(Ui.dp(this, 7), 0, Ui.dp(this, 7), 0);
        row.addView(child, lp);
    }

    private ImageView buildRoundControlButton(int iconRes, int sizeDp, int bgColor, int tintColor) {
        ImageView iv = new ImageView(this);
        int px = Ui.dp(this, sizeDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(bgColor);
        iv.setBackground(bg);
        iv.setImageResource(iconRes);
        iv.setColorFilter(tintColor);
        int pad = Ui.dp(this, sizeDp >= 56 ? 15 : 13);
        iv.setPadding(pad, pad, pad, pad);
        return iv;
    }

    private LinearLayout buildLabeledActionCol(int iconRes, int bgColor, int tint, int sizeDp,
                                               String label, View.OnClickListener click) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView btn = buildRoundControlButton(iconRes, sizeDp, bgColor, tint);
        btn.setOnClickListener(click);
        col.addView(btn);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13.5f);
        tv.setPadding(0, Ui.dp(this, 8), 0, 0);
        col.addView(tv);
        return col;
    }

    private void enablePipDrag(View view) {
        final float[] dxy = new float[2];
        view.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dxy[0] = v.getX() - ev.getRawX();
                    dxy[1] = v.getY() - ev.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float nx = Math.max(16f, Math.min(root.getWidth() - v.getWidth() - 16f, ev.getRawX() + dxy[0]));
                    float ny = Math.max(64f, Math.min(root.getHeight() - v.getHeight() - 180f, ev.getRawY() + dxy[1]));
                    v.setX(nx);
                    v.setY(ny);
                    return true;
                default:
                    return false;
            }
        });
    }

    // ==================== SIGNALING & MEDIA ====================

    private void publishOutgoingCallSignal() {
        if (isGroup) { callFailure(); return; }
        Fb.db().signalCall(chatId, callId, "start", peerId, isVideo)
                .addOnSuccessListener(result -> {
                    if (ended) Fb.db().signalCall(chatId, callId, "cancelled", peerId, isVideo);
                }).addOnFailureListener(error -> callFailure());
    }

    private void callFailure() {
        if (ended) return;
        Ui.toast(this, isGroup ? "Calls are available in direct chats only" : "Call could not connect");
        endCallInternal("ended", false);
    }

    @SuppressWarnings("unchecked")
    private void attachSignalingListener() {
        signalReg = chatDocRef.addSnapshotListener((snap, err) -> {
            if (ended || isFinishing() || snap == null || !snap.exists()) return;
            Object raw = snap.get("activeCall");
            if (!(raw instanceof Map)) return;
            Map<String, Object> ac = (Map<String, Object>) raw;
            String cid = Format.str(ac, "callId");
            if (!callId.equals(cid)) return;

            String st = Format.str(ac, "status");
            if ("connected".equals(st) && !connected) {
                Object cat = ac.get("connectedAt");
                long cMs = cat instanceof Number ? ((Number) cat).longValue() : System.currentTimeMillis();
                onCallConnected(cMs > 0 ? cMs : System.currentTimeMillis());
            } else if ("declined".equals(st)) {
                statusTv.setText("Call declined");
                endCallInternal("declined", false);
            } else if ("ended".equals(st) || "missed".equals(st) || "cancelled".equals(st)) {
                endCallInternal(st, false);
            }
        });
    }

    private void acceptIncomingCall() {
        if (ended || accepting) return;
        accepting = true;
        stopRingingFeedback();
        incomingControlsRow.setVisibility(View.GONE);
        activeControlsRow.setVisibility(View.VISIBLE);
        statusTv.setText("Connecting…");
        Fb.db().signalCall(chatId, callId, "accept", peerId, isVideo)
                .addOnSuccessListener(result -> {
                    if (ended) Fb.db().signalCall(chatId, callId, "end", peerId, isVideo);
                    else onCallConnected(result.optLong("connectedAt"));
                }).addOnFailureListener(error -> { if (!connected) callFailure(); });
    }

    private void onCallConnected(long cMs) {
        if (connected || ended) return;
        connected = true;
        connectedAtMs = cMs > 0 ? cMs : System.currentTimeMillis();
        mainHandler.removeCallbacks(ringTimeoutRunnable);
        stopRingingFeedback();

        configureCallAudioRouting();
        statusTv.setText("00:00");
        mainHandler.removeCallbacks(durationTicker);
        mainHandler.post(durationTicker);

        attachRemoteMediaListener();
    }

    private void configureCallAudioRouting() {
        if (audioManager == null) return;
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(speakerOn);
        } catch (Throwable ignored) { }
    }

    private void ensureCallPermissionsAndPreparePreview(boolean startStreamingNow) {
        boolean needCam = cameraEnabled;
        boolean hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCam = !needCam || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        if (!hasAudio || !hasCam) {
            if (needCam) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA},
                        REQ_CALL_PERMS);
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQ_CALL_PERMS);
            }
            return;
        }
        startLocalCaptureIfPermitted(startStreamingNow || connected);
    }

    private void startLocalCaptureIfPermitted(boolean beginTransmit) {
        if (engine == null || ended) return;
        if (cameraEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            localPipCard.setVisibility(View.VISIBLE);
            btnFlipCam.setVisibility(View.VISIBLE);
            engine.attachCameraPreview(localPreviewTexture, true);
            engine.setCameraPaused(false);
        }
        if (beginTransmit) {
            engine.startSpeakerPlayback();
            if (cameraEnabled) {
                engine.startBroadcasting(true);
            } else {
                engine.startMicrophoneStream();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL_PERMS) {
            boolean hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasAudio) {
                Ui.toast(this, "Microphone permission is required for voice & video calls");
            }
            if (cameraEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                cameraEnabled = false;
                localPipCard.setVisibility(View.GONE);
                btnFlipCam.setVisibility(View.GONE);
                updateControlVisuals();
            }
            startLocalCaptureIfPermitted(connected);
        }
    }

    private void attachRemoteMediaListener() {
        mediaRelay = new LiveRelayClient(new LiveRelayClient.Listener() {
            @Override public void ready() { if (!ended) ensureCallPermissionsAndPreparePreview(true); }
            @Override public void audience(int count) { }
            @Override public void disconnected() {
                if (!ended) {
                    Ui.toast(CallActivity.this, "Call connection lost");
                    endCallInternal("completed", true);
                }
            }
            @Override public void packet(String kind, String data, long seq) {
                LiveStreamEngine current = engine;
                if (ended || current == null) return;
                if ("audio".equals(kind)) current.enqueueRemoteAudio(data, seq, false);
                if ("video".equals(kind)) current.decodeRemoteFrame(data, seq, false);
                if ("camera".equals(kind)) mainHandler.post(() -> {
                    if (ended) return;
                    remoteVideoView.setVisibility(View.GONE);
                    centerProfileBox.setVisibility(View.VISIBLE);
                });
            }
        });
        mediaRelay.openCall(chatId, callId);
    }

    @Override public void onEncodedVideoFrame(String data, int width, int height) {
        LiveRelayClient relay = mediaRelay;
        if (!connected || ended || !cameraEnabled || relay == null) return;
        long now = System.currentTimeMillis();
        if (now - lastVideoPushMs < 140L) return;
        lastVideoPushMs = now;
        relay.send("video", data, localVideoSeq.incrementAndGet());
    }

    @Override public void onEncodedAudioChunk(String data, int voiceLevel) {
        LiveRelayClient relay = mediaRelay;
        if (connected && !ended && !micMuted && relay != null) relay.send("audio", data, localAudioSeq.incrementAndGet());
    }

    @Override
    public void onLocalVoiceLevel(int voiceLevel) {
        if (voicePulseRing == null) return;
        float scale = 1.0f + Math.min(0.24f, voiceLevel / 280f);
        voicePulseRing.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
    }

    @Override
    public void onDecodedRemoteVideo(Bitmap bitmap, boolean isCoHost) {
        if (ended || isFinishing() || bitmap == null) return;
        remoteVideoView.setImageBitmap(bitmap);
        if (remoteVideoView.getVisibility() != View.VISIBLE) {
            remoteVideoView.setVisibility(View.VISIBLE);
            centerProfileBox.setVisibility(View.GONE);
        }
    }

    @Override
    public void onCameraError(String message) { }

    // ==================== CONTROLS ====================

    private void toggleMicMute() {
        micMuted = !micMuted;
        if (engine != null) engine.setMicMuted(micMuted);
        updateControlVisuals();
        Ui.toast(this, micMuted ? "Microphone muted" : "Microphone on");
    }

    private void toggleSpeakerphone() {
        speakerOn = !speakerOn;
        if (audioManager != null) {
            try { audioManager.setSpeakerphoneOn(speakerOn); } catch (Throwable ignored) { }
        }
        updateControlVisuals();
        Ui.toast(this, speakerOn ? "Speaker on" : "Earpiece mode");
    }

    private void toggleCameraEnabled() {
        if (!isVideo) { Ui.toast(this, "Start a video call to use the camera"); return; }
        cameraEnabled = !cameraEnabled;
        if (cameraEnabled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                        REQ_CALL_PERMS);
                return;
            }
            localPipCard.setVisibility(View.VISIBLE);
            btnFlipCam.setVisibility(View.VISIBLE);
            if (engine != null) {
                engine.attachCameraPreview(localPreviewTexture, true);
                engine.setCameraPaused(false);
                if (connected) engine.startBroadcasting(true);
            }

        } else {
            localPipCard.setVisibility(View.GONE);
            btnFlipCam.setVisibility(View.GONE);
            if (engine != null) {
                engine.setCameraPaused(true);
            }
            if (mediaRelay != null) mediaRelay.send("camera", "AA==", localVideoSeq.incrementAndGet());
        }
        updateControlVisuals();
    }

    private void updateControlVisuals() {
        styleRoundBtn(btnMicToggle, micMuted ? Color.WHITE : 0x33FFFFFF,
                micMuted ? 0xFFE53935 : Color.WHITE);
        styleRoundBtn(btnSpeakerToggle, speakerOn ? Color.WHITE : 0x33FFFFFF,
                speakerOn ? 0xFF10131A : Color.WHITE);
        styleRoundBtn(btnVideoToggle, cameraEnabled ? Color.WHITE : 0x33FFFFFF,
                cameraEnabled ? 0xFF10131A : Color.WHITE);
    }

    private void styleRoundBtn(ImageView iv, int bgCol, int tintCol) {
        if (iv == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(bgCol);
        iv.setBackground(bg);
        iv.setColorFilter(tintCol);
    }

    // ==================== RINGING & TEARDOWN ====================

    private void startRingingFeedback() {
        mainHandler.removeCallbacks(ringToneLoop);
        mainHandler.post(ringToneLoop);
    }

    private void stopRingingFeedback() {
        mainHandler.removeCallbacks(ringToneLoop);
        if (toneGen != null) {
            try { toneGen.stopTone(); toneGen.release(); } catch (Throwable ignored) { }
            toneGen = null;
        }
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Throwable ignored) { }
        }
    }

    private void endCallInternal(@NonNull String reason, boolean updateServer) {
        if (ended) return;
        ended = true;
        activeCallScreenOpen = false;

        mainHandler.removeCallbacks(ringTimeoutRunnable);
        mainHandler.removeCallbacks(durationTicker);
        stopRingingFeedback();

        int durSec = 0;
        if (connected && connectedAtMs > 0L) {
            durSec = (int) Math.max(1L, (System.currentTimeMillis() - connectedAtMs) / 1000L);
        }

        if (updateServer && chatDocRef != null) {
            String action = connected || "ended".equals(reason) ? "end" : "declined".equals(reason) ? "decline" : reason;
            final int duration = durSec;
            Fb.db().signalCall(chatId, callId, action, peerId, isVideo)
                    .addOnSuccessListener(result -> writeDeterministicCallLogMessage(reason, duration));
        }

        releaseResources();
        finish();
    }

    private void writeDeterministicCallLogMessage(@NonNull String reason, int durSec) {
        String me = Fb.uid();
        if (me == null || chatId == null || chatId.isEmpty()) return;

        String kindLabel = isVideo ? "Video call" : "Voice call";
        String finalStatus;
        String summaryText;
        if (connected || "completed".equals(reason) || durSec > 0) {
            finalStatus = "completed";
            summaryText = (isVideo ? "📹 " : "📞 ") + kindLabel + " · " + formatHumanDuration(durSec);
        } else if ("declined".equals(reason)) {
            finalStatus = "declined";
            summaryText = (isVideo ? "📹 " : "📞 ") + "Declined " + kindLabel.toLowerCase(Locale.US);
        } else {
            finalStatus = "missed";
            summaryText = (isVideo ? "📹 " : "📞 ") + "Missed " + kindLabel.toLowerCase(Locale.US);
        }

        String callerUid = isCaller ? me : (peerId == null ? me : peerId);
        String myName = (Fb.me != null && Fb.me.displayName != null) ? Fb.me.displayName : "User";
        String myPhoto = (Fb.me != null && Fb.me.photoUrl != null) ? Fb.me.photoUrl : "";

        Map<String, Object> msg = new HashMap<>();
        msg.put("senderId", callerUid);
        msg.put("senderName", isCaller ? myName : peerName);
        msg.put("senderPhoto", isCaller ? myPhoto : peerPhoto);
        msg.put("receiverId", isCaller ? (peerId == null ? "" : peerId) : me);
        msg.put("text", summaryText);
        msg.put("type", "call");
        msg.put("callType", isVideo ? "video" : "voice");
        msg.put("callStatus", finalStatus);
        msg.put("callDurationSec", durSec);
        msg.put("isDelivered", true);
        msg.put("isSeen", connected);
        msg.put("createdAt", FieldValue.serverTimestamp());
        msg.put("createdAtMs", System.currentTimeMillis());

        chatDocRef.collection("messages").document("call_" + callId).set(msg, SetOptions.merge());

        Map<String, Object> roomMeta = new HashMap<>();
        roomMeta.put("lastMessage", summaryText);
        roomMeta.put("lastMessageTime", FieldValue.serverTimestamp());
        roomMeta.put("lastMessageTimeMs", System.currentTimeMillis());
        chatDocRef.set(roomMeta, SetOptions.merge());
    }

    private static String formatDuration(long sec) {
        long hrs = sec / 3600L;
        long mins = (sec % 3600L) / 60L;
        long rem = sec % 60L;
        if (hrs > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hrs, mins, rem);
        }
        return String.format(Locale.US, "%02d:%02d", mins, rem);
    }

    private static String formatHumanDuration(int sec) {
        if (sec < 60) return Math.max(1, sec) + "s";
        int mins = sec / 60;
        int rem = sec % 60;
        return rem > 0 ? (mins + "m " + rem + "s") : (mins + "m");
    }

    private void releaseResources() {
        if (signalReg != null) {
            try { signalReg.remove(); } catch (Throwable ignored) { }
            signalReg = null;
        }
        LiveRelayClient relay = mediaRelay;
        mediaRelay = null;
        if (relay != null) relay.close();
        if (engine != null) {
            try { engine.releaseAll(); } catch (Throwable ignored) { }
            engine = null;
        }
        if (audioManager != null) {
            try {
                audioManager.setSpeakerphoneOn(savedSpeakerphone);
                audioManager.setMode(savedAudioMode);
            } catch (Throwable ignored) { }
        }
    }

    @Override
    public void onBackPressed() {
        endCallInternal(connected ? "completed" : (isCaller ? "cancelled" : "declined"), true);
    }

    @Override
    protected void onDestroy() {
        activeCallScreenOpen = false;
        stopRingingFeedback();
        releaseResources();
        super.onDestroy();
    }
}
