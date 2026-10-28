package com.friendbook.social.live;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.AdsConfig;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.TickCache;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 🔴 friendbook Pro Live Broadcast Studio & Real-Time Interactive Live Room:
 *  - Real Camera2 Front/Rear hardware video preview (`TextureView`) for Host + Flip, Flash/Torch & Studio Color Filters!
 *  - Pre-Live Setup Studio (Title, Category, Public/Friends privacy, Comments/Slow-Mode/Gifts toggles, 3-2-1 Countdown)
 *  - Live Broadcast HUD: Host Avatar + Follow + Pulsing LIVE Timer + Active Viewers List + Stars Counter + Pinned Topic
 *  - Real-time scrolling Live Chat with Host/Moderator controls (Pin message, Delete comment, Mute user, Slow Mode)
 *  - Animated floating Live Reactions (❤️ 🔥 😂 👏 😍 🎉) synced across all viewers in real time!
 *  - Post-Live Broadcast Summary screen with Duration, Peak Viewers, Likes, Comments & Stars earned!
 */
public class LiveActivity extends Activity {

    private static final int REQ_CAMERA_AUDIO = 941;

    private static final String[] CATEGORIES = {
            "💬 Chatting", "🎮 Gaming", "🎵 Music", "📚 Q&A", "🏏 Sports", "✨ Lifestyle"
    };
    private static final String[] FILTER_NAMES = {
            "✨ Natural", "🔥 Warm", "❄️ Cool", "🌸 Rose", "🌙 Cinema"
    };
    private static final int[] FILTER_TINTS = {
            0x00000000, 0x24FF8A00, 0x2400B0FF, 0x24FF4081, 0x380D1B2A
    };
    private static final String[] REACTIONS = {"❤️", "🔥", "😂", "👏", "😍", "🎉"};

    private String liveId;
    private boolean isHost;
    private boolean isLiveStarted;
    private boolean joinedCounted;
    private boolean endingShown;

    // Live metadata & settings
    private String hostId = "";
    private String hostName = "";
    private String hostPhoto = "";
    private String hostTick = "none";
    private String liveTitle = "";
    private String liveCategory = CATEGORIES[0];
    private String livePrivacy = "Public";
    private String pinnedComment = "";
    private boolean allowComments = true;
    private boolean slowMode = false;
    private boolean allowGifts = true;
    private boolean micMuted = false;
    private boolean viewerAudioMuted = false;
    private boolean torchOn = false;
    private boolean useFrontCamera = true;
    private int filterIndex = 0;

    // Live counters & timers
    private long liveStartMs = 0L;
    private int currentViewers = 0;
    private int peakViewers = 0;
    private int totalStars = 0;
    private int totalLikes = 0;
    private int lastSyncedLikes = -1;
    private int totalCommentsCount = 0;
    private long lastCommentSentAt = 0L;
    private final List<String> viewerIds = new ArrayList<>();
    private final List<String> mutedUserIds = new ArrayList<>();

    // Camera2 hardware fields
    private TextureView cameraTexture;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private HandlerThread camThread;
    private Handler camHandler;
    private Size previewSize = new Size(1280, 720);

    // UI layers
    private FrameLayout rootStage;
    private FrameLayout fallbackStage;
    private ImageView stageAvatar;
    private TextView stageStatusLabel;
    private View filterOverlay;
    private FrameLayout preLiveLayer;
    private FrameLayout broadcastLayer;
    private TextView countdownTv;

    // Broadcast HUD widgets
    private ImageView hostAvIv, hostTickIv;
    private TextView hostNameTv, hostSubTv, followBtnTv;
    private TextView liveBadgeTv, viewersPillTv, starsPillTv, endOrLeaveBtn;
    private LinearLayout pinnedBox;
    private TextView pinnedTextTv;
    private TextView giftBannerTv;
    private RecyclerView chatRv;
    private EditText chatInput;
    private TextView sendBtn, giftBtn;
    private TextView micRailBtn, flashRailBtn, filterRailBtn;

    private final List<Map<String, Object>> comments = new ArrayList<>();
    private ChatAdapter chatAdapter;
    private ListenerRegistration liveReg, chatReg;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (isLiveStarted && liveBadgeTv != null && liveStartMs > 0) {
                long sec = Math.max(0L, (System.currentTimeMillis() - liveStartMs) / 1000L);
                long mm = sec / 60L, ss = sec % 60L;
                String clock = sec >= 3600
                        ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600L, (sec % 3600L) / 60L, ss)
                        : String.format(Locale.US, "%02d:%02d", mm, ss);
                liveBadgeTv.setText((micMuted ? "🔇 LIVE " : "🔴 LIVE ") + clock);
            }
            uiHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF0A0A0F);
        }

        liveId = getIntent().getStringExtra("live_id");
        isHost = getIntent().getBooleanExtra("host", false);
        String passedTitle = getIntent().getStringExtra("title");
        if (passedTitle != null && !passedTitle.trim().isEmpty()) {
            liveTitle = passedTitle.trim();
        }

        if (!isHost && (liveId == null || liveId.isEmpty())) {
            finish();
            return;
        }

        rootStage = new FrameLayout(this);
        rootStage.setBackgroundColor(0xFF0B0D14);

        // 1. Hardware Camera2 TextureView (for Host real camera feed)
        if (isHost) {
            cameraTexture = new TextureView(this);
            cameraTexture.setSurfaceTextureListener(surfaceListener);
            rootStage.addView(cameraTexture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        }

        // 2. Fallback / Viewer Studio Stage (shown for viewers or before camera permission is granted)
        fallbackStage = buildFallbackStage();
        rootStage.addView(fallbackStage, new FrameLayout.LayoutParams(-1, -1));
        if (isHost) fallbackStage.setVisibility(View.GONE);

        // 3. Real-time Studio Color Filter Tint Layer
        filterOverlay = new View(this);
        filterOverlay.setBackgroundColor(FILTER_TINTS[0]);
        rootStage.addView(filterOverlay, new FrameLayout.LayoutParams(-1, -1));

        // 4. Broadcast Live HUD Layer
        broadcastLayer = buildBroadcastHud();
        rootStage.addView(broadcastLayer, new FrameLayout.LayoutParams(-1, -1));

        // 5. Pre-Live Setup Layer (Host configures title, category, camera & settings before going live!)
        if (isHost && (liveId == null || liveId.isEmpty())) {
            broadcastLayer.setVisibility(View.GONE);
            preLiveLayer = buildPreLiveStudio(passedTitle);
            rootStage.addView(preLiveLayer, new FrameLayout.LayoutParams(-1, -1));
        } else {
            isLiveStarted = true;
        }

        // 6. Giant 3-2-1 Countdown Label
        countdownTv = Ui.tvBold(this, "", 74, Color.WHITE);
        countdownTv.setShadowLayer(12f, 0f, 4f, 0xCC000000);
        countdownTv.setVisibility(View.GONE);
        rootStage.addView(countdownTv, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        setContentView(rootStage);

        if (isHost) {
            hostId = Fb.uid() == null ? "" : Fb.uid();
            hostName = Fb.myName();
            hostPhoto = Fb.myPhoto();
            hostTick = Fb.me != null ? Fb.me.tickLevel : "none";
            ensureCameraAndAudioPermissions();
            if (isLiveStarted) {
                attachRoomListeners();
                uiHandler.post(timerTick);
            }
        } else {
            joinAsViewer();
            attachRoomListeners();
            uiHandler.post(timerTick);
        }
    }

    // ==================== CAMERA2 REAL HARDWARE PREVIEW ====================

    private void ensureCameraAndAudioPermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_CAMERA_AUDIO);
        } else {
            startCameraThread();
            if (cameraTexture != null && cameraTexture.isAvailable()) openCameraHardware();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_AUDIO) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                if (fallbackStage != null) fallbackStage.setVisibility(View.GONE);
                startCameraThread();
                if (cameraTexture != null && cameraTexture.isAvailable()) openCameraHardware();
            } else {
                if (fallbackStage != null) fallbackStage.setVisibility(View.VISIBLE);
                Ui.avatarLarge(this, stageAvatar, Fb.myPhoto());
                stageStatusLabel.setText("📷 Audio-Only Studio Mode (Tap Flip to enable camera)");
            }
        }
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCameraHardware();
        }
        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return true; }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
    };

    private void startCameraThread() {
        if (camThread != null) return;
        camThread = new HandlerThread("FriendbookLiveCam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
    }

    private void stopCameraThread() {
        closeCameraHardware();
        if (camThread != null) {
            camThread.quitSafely();
            try { camThread.join(300); } catch (InterruptedException ignored) {}
            camThread = null;
            camHandler = null;
        }
    }

    private void closeCameraHardware() {
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
        } catch (Throwable ignored) {}
        try {
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        } catch (Throwable ignored) {}
    }

    private void openCameraHardware() {
        if (!isHost || cameraTexture == null || !cameraTexture.isAvailable()) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (fallbackStage != null) fallbackStage.setVisibility(View.VISIBLE);
            return;
        }
        startCameraThread();
        closeCameraHardware();
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;
            String chosenId = null;
            int targetLens = useFrontCamera
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(cid);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == targetLens) {
                    chosenId = cid;
                    StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                        if (sizes != null && sizes.length > 0) previewSize = pickOptimalSize(sizes);
                    }
                    break;
                }
            }
            if (chosenId == null && cm.getCameraIdList().length > 0) {
                chosenId = cm.getCameraIdList()[0];
            }
            if (chosenId == null) {
                if (fallbackStage != null) fallbackStage.setVisibility(View.VISIBLE);
                return;
            }
            cm.openCamera(chosenId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startPreviewSession();
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    runOnUiThread(() -> {
                        if (fallbackStage != null) fallbackStage.setVisibility(View.VISIBLE);
                    });
                }
            }, camHandler);
        } catch (Throwable t) {
            if (fallbackStage != null) fallbackStage.setVisibility(View.VISIBLE);
        }
    }

    private Size pickOptimalSize(Size[] sizes) {
        Size best = sizes[0];
        int bestDiff = Integer.MAX_VALUE;
        for (Size s : sizes) {
            int diff = Math.abs(s.getWidth() - 1280) + Math.abs(s.getHeight() - 720);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    private void startPreviewSession() {
        try {
            if (cameraDevice == null || cameraTexture == null || !cameraTexture.isAvailable()) return;
            SurfaceTexture st = cameraTexture.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(st);
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(surface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            if (!useFrontCamera && torchOn) {
                previewBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            }
            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(previewBuilder.build(), null, camHandler);
                            } catch (Throwable ignored) {}
                        }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {}
                    }, camHandler);
        } catch (Throwable ignored) {}
    }

    private void flipCamera() {
        if (!isHost) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ensureCameraAndAudioPermissions();
            return;
        }
        useFrontCamera = !useFrontCamera;
        if (useFrontCamera) torchOn = false;
        updateRailButtonLabels();
        openCameraHardware();
        Ui.toast(this, useFrontCamera ? "🤳 Front camera" : "📷 Rear camera");
    }

    private void toggleTorch() {
        if (!isHost) return;
        if (useFrontCamera) {
            Ui.toast(this, "Switch to Rear camera to use Flash 🔦");
            return;
        }
        torchOn = !torchOn;
        updateRailButtonLabels();
        try {
            if (previewBuilder != null && captureSession != null) {
                previewBuilder.set(CaptureRequest.FLASH_MODE,
                        torchOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                captureSession.setRepeatingRequest(previewBuilder.build(), null, camHandler);
            }
        } catch (Throwable ignored) {}
    }

    private void cycleStudioFilter() {
        filterIndex = (filterIndex + 1) % FILTER_TINTS.length;
        if (filterOverlay != null) filterOverlay.setBackgroundColor(FILTER_TINTS[filterIndex]);
        updateRailButtonLabels();
        if (isHost && isLiveStarted && liveId != null) {
            Fb.db().collection("lives").document(liveId)
                    .set(Fb.map("filterIndex", filterIndex), SetOptions.merge());
        }
        Ui.toast(this, "Filter: " + FILTER_NAMES[filterIndex]);
    }

    // ==================== PRE-LIVE STUDIO OVERLAY ====================

    private FrameLayout buildPreLiveStudio(String initialTitle) {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0x55000000);

        // Top bar: Close + Pre-live quick camera switches
        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(top, 14, 34, 14, 10);
        TextView close = Ui.roundChip(this, "✕ Close", 0x88000000, Color.WHITE, 13);
        Ui.pad(close, 12, 6, 12, 6);
        close.setOnClickListener(v -> finish());
        top.addView(close);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        TextView flipTop = Ui.roundChip(this, "🔄 Flip", 0x88000000, Color.WHITE, 13);
        Ui.pad(flipTop, 12, 6, 12, 6);
        flipTop.setOnClickListener(v -> flipCamera());
        top.addView(flipTop);

        TextView filterTop = Ui.roundChip(this, "✨ Filter", 0x88000000, Color.WHITE, 13);
        Ui.pad(filterTop, 12, 6, 12, 6);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
        flp.leftMargin = Ui.dp(this, 8);
        filterTop.setOnClickListener(v -> cycleStudioFilter());
        top.addView(filterTop, flp);
        layer.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Bottom Setup Card
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(this, 0xDD14161F, 22));
        Ui.pad(card, 18, 18, 18, 22);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        clp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 18));
        card.setLayoutParams(clp);

        LinearLayout meRow = Ui.row(this);
        meRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView myAv = new ImageView(this);
        Ui.avatar(this, myAv, Fb.myPhoto());
        int as = Ui.dp(this, 44);
        meRow.addView(myAv, new LinearLayout.LayoutParams(as, as));
        LinearLayout meCol = Ui.col(this);
        meCol.setPadding(Ui.dp(this, 10), 0, 0, 0);
        meCol.addView(Ui.tvBold(this, Fb.myName(), 15, Color.WHITE));
        final TextView audChip = Ui.tvBold(this, "🌍 Public · Tap to change", 12, 0xFF64B5F6);
        audChip.setOnClickListener(v -> {
            livePrivacy = "Public".equals(livePrivacy) ? "Friends" : "Public";
            audChip.setText(("Public".equals(livePrivacy) ? "🌍 Public" : "👥 Friends Only") + " · Tap to change");
        });
        meCol.addView(audChip);
        meRow.addView(meCol, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(meRow);

        final EditText titleIn = Ui.input(this, "Add a catchy Live title (e.g. Q&A with friends 🔥)…");
        titleIn.setTextColor(Color.WHITE);
        titleIn.setHintTextColor(0x99FFFFFF);
        titleIn.setBackground(Ui.roundStroke(this, 0x33FFFFFF, 14, 0x44FFFFFF, 1));
        if (initialTitle != null && !initialTitle.isEmpty()) titleIn.setText(initialTitle);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Ui.dp(this, 12);
        card.addView(titleIn, tlp);

        // Category Horizontal Chips
        HorizontalScrollView catSv = new HorizontalScrollView(this);
        catSv.setHorizontalScrollBarEnabled(false);
        final LinearLayout catRow = Ui.row(this);
        catRow.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        final List<TextView> catChips = new ArrayList<>();
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int idx = i;
            TextView ch = Ui.roundChip(this, CATEGORIES[i],
                    i == 0 ? Ui.BLUE : 0x33FFFFFF, Color.WHITE, 12);
            Ui.pad(ch, 12, 6, 12, 6);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.rightMargin = Ui.dp(this, 6);
            ch.setOnClickListener(v -> {
                liveCategory = CATEGORIES[idx];
                for (int j = 0; j < catChips.size(); j++) {
                    catChips.get(j).setBackground(Ui.round(this, j == idx ? Ui.BLUE : 0x33FFFFFF, 18));
                }
            });
            catChips.add(ch);
            catRow.addView(ch, cp);
        }
        catSv.addView(catRow);
        card.addView(catSv);

        // Pre-live toggles row
        LinearLayout optsRow = Ui.row(this);
        optsRow.setGravity(Gravity.CENTER_VERTICAL);
        optsRow.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        final TextView cmOpt = Ui.roundChip(this, "💬 Comments: ON", 0x33FFFFFF, Color.WHITE, 11);
        Ui.pad(cmOpt, 10, 5, 10, 5);
        cmOpt.setOnClickListener(v -> {
            allowComments = !allowComments;
            cmOpt.setText(allowComments ? "💬 Comments: ON" : "🔇 Comments: OFF");
        });
        final TextView smOpt = Ui.roundChip(this, "🐢 Slow Mode: OFF", 0x33FFFFFF, Color.WHITE, 11);
        Ui.pad(smOpt, 10, 5, 10, 5);
        LinearLayout.LayoutParams smp = new LinearLayout.LayoutParams(-2, -2);
        smp.leftMargin = Ui.dp(this, 6);
        smOpt.setOnClickListener(v -> {
            slowMode = !slowMode;
            smOpt.setText(slowMode ? "🐢 Slow Mode: 5s" : "🐢 Slow Mode: OFF");
        });
        final TextView stOpt = Ui.roundChip(this, "⭐ Gifts: ON", 0x33FFFFFF, Color.WHITE, 11);
        Ui.pad(stOpt, 10, 5, 10, 5);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-2, -2);
        stp.leftMargin = Ui.dp(this, 6);
        stOpt.setOnClickListener(v -> {
            allowGifts = !allowGifts;
            stOpt.setText(allowGifts ? "⭐ Gifts: ON" : "⭐ Gifts: OFF");
        });
        optsRow.addView(cmOpt);
        optsRow.addView(smOpt, smp);
        optsRow.addView(stOpt, stp);
        card.addView(optsRow);

        TextView goBtn = Ui.roundChip(this, "🔴  Go Live Now", Ui.RED, Color.WHITE, 16);
        goBtn.setGravity(Gravity.CENTER);
        Ui.pad(goBtn, 20, 13, 20, 13);
        goBtn.setOnClickListener(v -> {
            String t = titleIn.getText().toString().trim();
            liveTitle = t.isEmpty() ? (Fb.myName() + " is Live! " + liveCategory) : t;
            goBtn.setEnabled(false);
            runCountdownAndStartLive();
        });
        card.addView(goBtn, new LinearLayout.LayoutParams(-1, -2));

        layer.addView(card);
        return layer;
    }

    private void runCountdownAndStartLive() {
        if (preLiveLayer != null) preLiveLayer.setVisibility(View.GONE);
        countdownTv.setVisibility(View.VISIBLE);
        final int[] step = {3};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                if (step[0] > 0) {
                    countdownTv.setText(String.valueOf(step[0]));
                    countdownTv.setScaleX(0.6f);
                    countdownTv.setScaleY(0.6f);
                    countdownTv.animate().scaleX(1.25f).scaleY(1.25f).setDuration(420).start();
                    step[0]--;
                    uiHandler.postDelayed(this, 850L);
                } else {
                    countdownTv.setVisibility(View.GONE);
                    publishLiveRoomToFirestore();
                }
            }
        };
        tick.run();
    }

    private void publishLiveRoomToFirestore() {
        String me = Fb.uid();
        if (me == null) { finish(); return; }
        liveStartMs = System.currentTimeMillis();
        isLiveStarted = true;
        broadcastLayer.setVisibility(View.VISIBLE);

        DocumentReference ref = Fb.db().collection("lives").document();
        liveId = ref.getId();
        Map<String, Object> doc = new HashMap<>();
        doc.put("hostId", me);
        doc.put("hostName", Fb.myName());
        doc.put("hostPhoto", Fb.myPhoto());
        doc.put("hostTick", Fb.me != null ? Fb.me.tickLevel : "none");
        doc.put("title", liveTitle);
        doc.put("category", liveCategory);
        doc.put("privacy", livePrivacy);
        doc.put("status", "live");
        doc.put("allowComments", allowComments);
        doc.put("slowMode", slowMode);
        doc.put("allowGifts", allowGifts);
        doc.put("micMuted", micMuted);
        doc.put("filterIndex", filterIndex);
        doc.put("pinnedComment", "");
        doc.put("viewerCount", 1);
        doc.put("peakViewers", 1);
        doc.put("likesCount", 0);
        doc.put("totalStars", 0);
        doc.put("viewerIds", Collections.singletonList(me));
        doc.put("mutedUserIds", new ArrayList<String>());
        doc.put("startedAtMs", liveStartMs);
        doc.put("createdAt", FieldValue.serverTimestamp());

        ref.set(doc).addOnSuccessListener(x -> {
            Ui.toast(this, "🔴 You are LIVE!");
            attachRoomListeners();
            uiHandler.removeCallbacks(timerTick);
            uiHandler.post(timerTick);
        }).addOnFailureListener(e -> {
            Ui.toast(this, "Couldn't start live: " + e.getMessage());
            finish();
        });
    }

    // ==================== FALLBACK / VIEWER STAGE ====================

    private FrameLayout buildFallbackStage() {
        FrameLayout st = new FrameLayout(this);
        st.setBackgroundColor(0xFF101322);

        LinearLayout center = Ui.col(this);
        center.setGravity(Gravity.CENTER);
        FrameLayout pulseRing = new FrameLayout(this);
        pulseRing.setBackground(Ui.roundStroke(this, 0x22E41E3F, 72, Ui.RED, 3));
        int ringSz = Ui.dp(this, 138);
        stageAvatar = new ImageView(this);
        int avSz = Ui.dp(this, 118);
        pulseRing.addView(stageAvatar, new FrameLayout.LayoutParams(avSz, avSz, Gravity.CENTER));
        center.addView(pulseRing, new LinearLayout.LayoutParams(ringSz, ringSz));

        stageStatusLabel = Ui.tvBold(this, "🔴 Connecting to Live Stream…", 15, Color.WHITE);
        stageStatusLabel.setGravity(Gravity.CENTER);
        stageStatusLabel.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), 0);
        center.addView(stageStatusLabel);
        st.addView(center, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        return st;
    }

    // ==================== FULL BROADCAST HUD ====================

    private FrameLayout buildBroadcastHud() {
        FrameLayout hud = new FrameLayout(this);

        // Double-tap anywhere on stage to send floating ❤️ reaction!
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                sendReaction("❤️");
                return true;
            }
        });
        hud.setOnTouchListener((v, ev) -> {
            gd.onTouchEvent(ev);
            return false;
        });

        // ---- TOP BAR + PINNED BANNER ----
        LinearLayout topWrap = Ui.col(this);
        Ui.pad(topWrap, 12, 32, 12, 8);

        LinearLayout topRow = Ui.row(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // Host capsule
        LinearLayout hostCap = Ui.row(this);
        hostCap.setGravity(Gravity.CENTER_VERTICAL);
        hostCap.setBackground(Ui.round(this, 0x88000000, 24));
        Ui.pad(hostCap, 6, 5, 10, 5);
        hostAvIv = new ImageView(this);
        int hs = Ui.dp(this, 34);
        hostCap.addView(hostAvIv, new LinearLayout.LayoutParams(hs, hs));

        LinearLayout hInfo = Ui.col(this);
        hInfo.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 6), 0);
        LinearLayout hNameRow = Ui.row(this);
        hNameRow.setGravity(Gravity.CENTER_VERTICAL);
        hostNameTv = Ui.tvBold(this, "Live Host", 13, Color.WHITE);
        hostNameTv.setMaxWidth(Ui.dp(this, 105));
        hostNameTv.setSingleLine(true);
        hNameRow.addView(hostNameTv);
        hostTickIv = new ImageView(this);
        int ts = Ui.dp(this, 13);
        LinearLayout.LayoutParams tkLp = new LinearLayout.LayoutParams(ts, ts);
        tkLp.leftMargin = Ui.dp(this, 4);
        hNameRow.addView(hostTickIv, tkLp);
        hInfo.addView(hNameRow);
        hostSubTv = Ui.tv(this, CATEGORIES[0], 10.5f, 0xCCFFFFFF);
        hostSubTv.setMaxWidth(Ui.dp(this, 125));
        hostSubTv.setSingleLine(true);
        hInfo.addView(hostSubTv);
        hostCap.addView(hInfo);

        followBtnTv = Ui.roundChip(this, "Follow", Ui.BLUE, Color.WHITE, 11);
        Ui.pad(followBtnTv, 9, 4, 9, 4);
        followBtnTv.setVisibility(isHost ? View.GONE : View.VISIBLE);
        followBtnTv.setOnClickListener(v -> toggleFollowHost());
        hostCap.addView(followBtnTv);
        hostCap.setOnClickListener(v -> {
            if (!hostId.isEmpty()) {
                startActivity(new Intent(this, ProfileActivity.class).putExtra("uid", hostId));
            }
        });
        topRow.addView(hostCap);

        topRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        // LIVE timer + Viewers + End/Leave
        liveBadgeTv = Ui.roundChip(this, "🔴 LIVE 00:00", Ui.RED, Color.WHITE, 11);
        Ui.pad(liveBadgeTv, 9, 5, 9, 5);
        topRow.addView(liveBadgeTv);

        viewersPillTv = Ui.roundChip(this, "👁 1", 0x88000000, Color.WHITE, 11);
        Ui.pad(viewersPillTv, 9, 5, 9, 5);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
        vlp.leftMargin = Ui.dp(this, 5);
        viewersPillTv.setOnClickListener(v -> showViewersDialog());
        topRow.addView(viewersPillTv, vlp);

        endOrLeaveBtn = Ui.roundChip(this, isHost ? "End" : "✕", isHost ? 0xCC000000 : 0x88000000, Color.WHITE, 12);
        Ui.pad(endOrLeaveBtn, 11, 5, 11, 5);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.leftMargin = Ui.dp(this, 6);
        endOrLeaveBtn.setOnClickListener(v -> onExitOrEndPressed());
        topRow.addView(endOrLeaveBtn, elp);
        topWrap.addView(topRow);

        // Stars +Likes mini strip
        LinearLayout statsStrip = Ui.row(this);
        statsStrip.setPadding(0, Ui.dp(this, 6), 0, 0);
        starsPillTv = Ui.roundChip(this, "⭐ 0 Stars · ❤️ 0", 0x77000000, 0xFFFFD54F, 11);
        Ui.pad(starsPillTv, 10, 4, 10, 4);
        statsStrip.addView(starsPillTv);
        topWrap.addView(statsStrip);

        // Pinned announcement banner
        pinnedBox = Ui.row(this);
        pinnedBox.setGravity(Gravity.CENTER_VERTICAL);
        pinnedBox.setBackground(Ui.roundStroke(this, 0xAA1877F2, 12, 0x66FFFFFF, 1));
        Ui.pad(pinnedBox, 10, 6, 10, 6);
        pinnedBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(-1, -2);
        pbp.topMargin = Ui.dp(this, 8);
        pinnedTextTv = Ui.tvBold(this, "", 12, Color.WHITE);
        pinnedBox.addView(pinnedTextTv, new LinearLayout.LayoutParams(0, -2, 1));
        if (isHost) {
            TextView unpin = Ui.tvBold(this, "✕", 12, Color.WHITE);
            unpin.setPadding(Ui.dp(this, 8), 0, 0, 0);
            unpin.setOnClickListener(v -> updatePinnedComment(""));
            pinnedBox.addView(unpin);
        }
        topWrap.addView(pinnedBox, pbp);
        hud.addView(topWrap, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ---- GIFT TOAST BANNER ----
        giftBannerTv = Ui.roundChip(this, "", 0xDDFF9800, Color.WHITE, 13);
        Ui.pad(giftBannerTv, 16, 8, 16, 8);
        giftBannerTv.setVisibility(View.GONE);
        FrameLayout.LayoutParams gbp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        gbp.topMargin = Ui.dp(this, 128);
        hud.addView(giftBannerTv, gbp);

        // ---- RIGHT-SIDE ACTION RAIL ----
        LinearLayout rail = Ui.col(this);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
        rlp.setMargins(0, 0, Ui.dp(this, 10), Ui.dp(this, 112));
        if (isHost) {
            rail.addView(railBtn("🔄", "Flip", this::flipCamera));
            micRailBtn = railBtn("🎙️", "Mic", this::toggleHostMic);
            rail.addView(micRailBtn);
            flashRailBtn = railBtn("🔦", "Flash", this::toggleTorch);
            rail.addView(flashRailBtn);
            filterRailBtn = railBtn("✨", "Filter", this::cycleStudioFilter);
            rail.addView(filterRailBtn);
            rail.addView(railBtn("📌", "Pin", this::promptPinTopic));
            rail.addView(railBtn("⚙️", "Settings", this::showHostLiveSettingsSheet));
            rail.addView(railBtn("📤", "Share", this::shareLiveStream));
        } else {
            rail.addView(railBtn("❤️", "Like", () -> sendReaction("❤️")));
            rail.addView(railBtn("🔥", "React", this::showReactionPicker));
            rail.addView(railBtn("⭐", "Gift", this::giftStarsDialog));
            final TextView muteBtn = railBtn("🔊", "Sound", null);
            muteBtn.setOnClickListener(v -> {
                viewerAudioMuted = !viewerAudioMuted;
                muteBtn.setText(viewerAudioMuted ? "🔇\nMuted" : "🔊\nSound");
                Ui.toast(this, viewerAudioMuted ? "Live audio muted" : "Live audio ON");
            });
            rail.addView(muteBtn);
            rail.addView(railBtn("📤", "Share", this::shareLiveStream));
            rail.addView(railBtn("🚩", "Report", this::reportLiveStream));
        }
        hud.addView(rail, rlp);

        // ---- BOTTOM LIVE CHAT + COMPOSER + QUICK REACTIONS ----
        LinearLayout bottom = Ui.col(this);
        Ui.pad(bottom, 12, 8, 68, 12);

        chatRv = new RecyclerView(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        chatRv.setLayoutManager(lm);
        chatAdapter = new ChatAdapter();
        chatRv.setAdapter(chatAdapter);
        bottom.addView(chatRv, new LinearLayout.LayoutParams(-1, Ui.dp(this, 210)));

        // Quick 1-tap reaction emojis strip
        LinearLayout reactRow = Ui.row(this);
        reactRow.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        for (final String em : REACTIONS) {
            TextView rb = Ui.tv(this, em, 19, Color.WHITE);
            rb.setBackground(Ui.round(this, 0x55000000, 18));
            Ui.pad(rb, 9, 4, 9, 4);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2);
            ep.rightMargin = Ui.dp(this, 6);
            rb.setOnClickListener(v -> sendReaction(em));
            reactRow.addView(rb, ep);
        }
        bottom.addView(reactRow);

        // Comment composer row
        LinearLayout inputRow = Ui.row(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        chatInput = new EditText(this);
        chatInput.setHint("Say something nice…");
        chatInput.setHintTextColor(0xAAFFFFFF);
        chatInput.setTextColor(Color.WHITE);
        chatInput.setTextSize(14);
        chatInput.setSingleLine(true);
        chatInput.setBackground(Ui.roundStroke(this, 0x77000000, 22, 0x44FFFFFF, 1));
        Ui.pad(chatInput, 14, 9, 14, 9);
        inputRow.addView(chatInput, new LinearLayout.LayoutParams(0, -2, 1));

        sendBtn = Ui.roundChip(this, "➤", Ui.BLUE, Color.WHITE, 14);
        Ui.pad(sendBtn, 13, 9, 13, 9);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = Ui.dp(this, 6);
        sendBtn.setOnClickListener(v -> sendLiveComment());
        inputRow.addView(sendBtn, slp);

        if (!isHost) {
            giftBtn = Ui.roundChip(this, "⭐", Ui.ORANGE, Color.WHITE, 14);
            Ui.pad(giftBtn, 12, 9, 12, 9);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
            glp.leftMargin = Ui.dp(this, 6);
            giftBtn.setOnClickListener(v -> giftStarsDialog());
            inputRow.addView(giftBtn, glp);
        }
        bottom.addView(inputRow);

        hud.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return hud;
    }

    private TextView railBtn(String emoji, String label, Runnable click) {
        TextView tv = Ui.tvBold(this, emoji + "\n" + label, 10.5f, Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(Ui.roundStroke(this, 0x77000000, 18, 0x33FFFFFF, 1));
        Ui.pad(tv, 8, 7, 8, 7);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 50), -2);
        lp.bottomMargin = Ui.dp(this, 8);
        tv.setLayoutParams(lp);
        if (click != null) tv.setOnClickListener(v -> click.run());
        return tv;
    }

    private void updateRailButtonLabels() {
        if (micRailBtn != null) micRailBtn.setText((micMuted ? "🔇\nMuted" : "🎙️\nMic"));
        if (flashRailBtn != null) flashRailBtn.setText((torchOn ? "⚡\nOn" : "🔦\nFlash"));
        if (filterRailBtn != null) filterRailBtn.setText("✨\nFilter");
    }

    // ==================== FIRESTORE ROOM SYNC ====================

    private void joinAsViewer() {
        String me = Fb.uid();
        if (liveId == null || me == null) return;
        joinedCounted = true;
        Map<String, Object> up = new HashMap<>();
        up.put("viewerCount", FieldValue.increment(1));
        up.put("viewerIds", FieldValue.arrayUnion(me));
        Fb.db().collection("lives").document(liveId).update(up);
    }

    private void attachRoomListeners() {
        if (liveId == null || liveId.isEmpty()) return;
        if (liveReg != null) liveReg.remove();
        if (chatReg != null) chatReg.remove();

        liveReg = Fb.db().collection("lives").document(liveId)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    Map<String, Object> m = d.getData();
                    if ("ended".equals(Format.str(m, "status")) && !isHost) {
                        Ui.toast(this, "🔴 Live broadcast has ended");
                        finish();
                        return;
                    }
                    hostId = Format.str(m, "hostId");
                    hostName = Format.strOr(m, "hostName", "Creator");
                    hostPhoto = Format.str(m, "hostPhoto");
                    hostTick = Format.strOr(m, "hostTick", "none");
                    liveTitle = Format.strOr(m, "title", "Live Broadcast");
                    liveCategory = Format.strOr(m, "category", CATEGORIES[0]);
                    livePrivacy = Format.strOr(m, "privacy", "Public");
                    pinnedComment = Format.str(m, "pinnedComment");
                    allowComments = m == null || !m.containsKey("allowComments") || Format.boolOf(m, "allowComments");
                    slowMode = Format.boolOf(m, "slowMode");
                    allowGifts = m == null || !m.containsKey("allowGifts") || Format.boolOf(m, "allowGifts");
                    micMuted = Format.boolOf(m, "micMuted");

                    long started = Format.ts(m == null ? null : m.get("startedAtMs"));
                    if (started == 0L) started = Format.ts(m == null ? null : m.get("createdAt"));
                    if (started > 0L) liveStartMs = started;

                    currentViewers = Math.max(0, Format.intOf(m, "viewerCount"));
                    int dbPeak = Format.intOf(m, "peakViewers");
                    if (currentViewers > peakViewers) peakViewers = currentViewers;
                    if (dbPeak > peakViewers) peakViewers = dbPeak;
                    if (isHost && currentViewers > dbPeak) {
                        Fb.db().collection("lives").document(liveId).update("peakViewers", currentViewers);
                    }

                    int newLikes = Math.max(0, Format.intOf(m, "likesCount"));
                    if (lastSyncedLikes >= 0 && newLikes > lastSyncedLikes) {
                        int burst = Math.min(4, newLikes - lastSyncedLikes);
                        for (int i = 0; i < burst; i++) {
                            uiHandler.postDelayed(() -> spawnFloatingEmoji(
                                    REACTIONS[rng.nextInt(REACTIONS.length)]), i * 140L);
                        }
                    }
                    lastSyncedLikes = newLikes;
                    totalLikes = newLikes;
                    totalStars = Math.max(0, Format.intOf(m, "totalStars"));

                    viewerIds.clear();
                    viewerIds.addAll(Format.listOf(m, "viewerIds"));
                    mutedUserIds.clear();
                    mutedUserIds.addAll(Format.listOf(m, "mutedUserIds"));

                    if (!isHost) {
                        int fIdx = Math.max(0, Math.min(FILTER_TINTS.length - 1, Format.intOf(m, "filterIndex")));
                        filterOverlay.setBackgroundColor(FILTER_TINTS[fIdx]);
                    }
                    refreshHudViews();
                });

        chatReg = Fb.db().collection("lives").document(liveId).collection("comments")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limitToLast(80)
                .addSnapshotListener((snap, err) -> {
                    comments.clear();
                    if (snap != null) {
                        totalCommentsCount = Math.max(totalCommentsCount, snap.size());
                        for (DocumentSnapshot dc : snap.getDocuments()) {
                            Map<String, Object> row = new HashMap<>();
                            if (dc.getData() != null) row.putAll(dc.getData());
                            row.put("_id", dc.getId());
                            comments.add(row);
                            if ("gift".equals(Format.str(row, "type")) && snap.getDocumentChanges().size() == 1) {
                                flashGiftBanner(Format.str(row, "userName") + " " + Format.str(row, "text"));
                            }
                        }
                    }
                    chatAdapter.notifyDataSetChanged();
                    if (!comments.isEmpty()) chatRv.scrollToPosition(comments.size() - 1);
                });
    }

    private void refreshHudViews() {
        Ui.avatar(this, hostAvIv, hostPhoto);
        hostNameTv.setText(hostName);
        Icons.setTickBadge(hostTickIv, hostTick, false);
        hostSubTv.setText(liveTitle + " · " + liveCategory);
        viewersPillTv.setText("👁 " + Format.count(Math.max(1, currentViewers)));
        starsPillTv.setText("⭐ " + Format.count(totalStars) + " Stars · ❤️ " + Format.count(totalLikes));

        if (!isHost) {
            Ui.avatarLarge(this, stageAvatar, hostPhoto);
            stageStatusLabel.setText("🔴 " + hostName + " is LIVE\n" + liveTitle
                    + (micMuted ? "\n🔇 Host muted microphone" : ""));
            boolean following = Fb.me != null && Fb.me.following != null && Fb.me.following.contains(hostId);
            followBtnTv.setText(following ? "Following" : "Follow");
            followBtnTv.setVisibility(hostId.equals(Fb.uid()) ? View.GONE : View.VISIBLE);
            if (giftBtn != null) giftBtn.setVisibility(allowGifts ? View.VISIBLE : View.GONE);
            // Admins can also force-end a violating live stream
            if (Admin.isAdmin()) endOrLeaveBtn.setText("🛡 End");
        }

        if (pinnedComment != null && !pinnedComment.isEmpty()) {
            pinnedBox.setVisibility(View.VISIBLE);
            pinnedTextTv.setText("📌 " + pinnedComment);
        } else {
            pinnedBox.setVisibility(View.GONE);
        }

        boolean amMuted = Fb.uid() != null && mutedUserIds.contains(Fb.uid());
        boolean canChat = isHost || (allowComments && !amMuted);
        chatInput.setEnabled(canChat);
        sendBtn.setAlpha(canChat ? 1f : 0.4f);
        if (!allowComments && !isHost) {
            chatInput.setHint("🔒 Host turned off comments");
        } else if (amMuted) {
            chatInput.setHint("🔇 You are muted in this Live");
        } else if (slowMode && !isHost) {
            chatInput.setHint("🐢 Slow mode (5s) · Say something…");
        } else {
            chatInput.setHint("Say something nice…");
        }
    }

    // ==================== INTERACTIVE ACTIONS & SETTINGS ====================

    private void sendReaction(String emoji) {
        spawnFloatingEmoji(emoji);
        Ui.haptic(rootStage);
        if (liveId != null && !liveId.isEmpty()) {
            lastSyncedLikes = Math.max(0, totalLikes) + 1;
            totalLikes = lastSyncedLikes;
            starsPillTv.setText("⭐ " + Format.count(totalStars) + " Stars · ❤️ " + Format.count(totalLikes));
            Fb.db().collection("lives").document(liveId).update("likesCount", FieldValue.increment(1));
        }
    }

    private void spawnFloatingEmoji(String emoji) {
        if (rootStage == null || isFinishing()) return;
        TextView tv = Ui.tv(this, emoji, 28, Color.WHITE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
        int rightOffset = Ui.dp(this, 18 + rng.nextInt(44));
        lp.setMargins(0, 0, rightOffset, Ui.dp(this, 120));
        rootStage.addView(tv, lp);

        float driftX = Ui.dp(this, rng.nextInt(50) - 25);
        float riseY = -Ui.dp(this, 260 + rng.nextInt(140));
        tv.setScaleX(0.5f);
        tv.setScaleY(0.5f);
        tv.animate()
                .translationX(driftX)
                .translationY(riseY)
                .scaleX(1.25f)
                .scaleY(1.25f)
                .alpha(0f)
                .setDuration(1850L)
                .withEndAction(() -> {
                    try { rootStage.removeView(tv); } catch (Throwable ignored) {}
                })
                .start();
    }

    private void showReactionPicker() {
        Ui.choose(this, "Send Live Reaction",
                new String[]{"❤️ Love", "🔥 Fire", "😂 Haha", "👏 Applause", "😍 Amazing", "🎉 Party"},
                w -> sendReaction(REACTIONS[w]));
    }

    private void sendLiveComment() {
        if (liveId == null || liveId.isEmpty()) return;
        String me = Fb.uid();
        if (me == null) return;
        if (!isHost && !allowComments) {
            Ui.toast(this, "Comments are turned off by the host");
            return;
        }
        if (!isHost && mutedUserIds.contains(me)) {
            Ui.toast(this, "You are muted in this live stream");
            return;
        }
        long now = System.currentTimeMillis();
        if (!isHost && slowMode && (now - lastCommentSentAt) < 5000L) {
            long wait = 5L - ((now - lastCommentSentAt) / 1000L);
            Ui.toast(this, "🐢 Slow mode: wait " + Math.max(1L, wait) + "s");
            return;
        }
        String text = chatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        chatInput.setText("");
        lastCommentSentAt = now;

        Map<String, Object> c = new HashMap<>();
        c.put("userId", me);
        c.put("userName", Fb.myName());
        c.put("userPhoto", Fb.myPhoto());
        c.put("userTick", Fb.me != null ? Fb.me.tickLevel : "none");
        c.put("isHostMsg", isHost);
        c.put("text", text);
        c.put("type", "chat");
        c.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("lives").document(liveId).collection("comments").add(c);
    }

    private void toggleHostMic() {
        micMuted = !micMuted;
        updateRailButtonLabels();
        if (liveId != null && !liveId.isEmpty()) {
            Fb.db().collection("lives").document(liveId).update("micMuted", micMuted);
        }
        Ui.toast(this, micMuted ? "🔇 Microphone muted" : "🎙️ Microphone live");
    }

    private void promptPinTopic() {
        EditText in = Ui.input(this, "Pinned message at top of Live…");
        in.setText(pinnedComment);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 10, 18, 0);
        box.addView(in);
        new AlertDialog.Builder(this)
                .setTitle("📌 Pin Live Announcement")
                .setView(box)
                .setPositiveButton("Pin", (d, w) -> updatePinnedComment(in.getText().toString().trim()))
                .setNeutralButton("Clear Pin", (d, w) -> updatePinnedComment(""))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updatePinnedComment(String text) {
        pinnedComment = text == null ? "" : text;
        if (liveId != null && !liveId.isEmpty()) {
            Fb.db().collection("lives").document(liveId).update("pinnedComment", pinnedComment);
        }
        refreshHudViews();
    }

    private void showHostLiveSettingsSheet() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 14, 18, 10);
        sv.addView(box);

        EditText titleEdit = Ui.input(this, "Live Stream Title");
        titleEdit.setText(liveTitle);
        box.addView(Ui.tvBold(this, " Broadcast Title", 13, Ui.sub(this)));
        box.addView(titleEdit);

        Switch swComments = settingSwitch(box, "💬 Allow Live Comments", allowComments);
        Switch swSlow = settingSwitch(box, "🐢 Slow Mode (1 message / 5s)", slowMode);
        Switch swGifts = settingSwitch(box, "⭐ Allow Star Gifts", allowGifts);
        Switch swMic = settingSwitch(box, "🔇 Mute My Microphone", micMuted);

        TextView clearChat = Ui.btnOutline(this, "🧹 Clear All Live Chat Messages", Ui.RED);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Ui.dp(this, 10);
        clearChat.setOnClickListener(v -> Fb.db().collection("lives").document(liveId)
                .collection("comments").limit(100).get().addOnSuccessListener(s -> {
                    for (DocumentSnapshot d : s.getDocuments()) d.getReference().delete();
                    Ui.toast(this, "Live chat cleared ✓");
                }));
        box.addView(clearChat, clp);

        new AlertDialog.Builder(this)
                .setTitle("⚙️ Live Broadcast Settings")
                .setView(sv)
                .setPositiveButton("Save Settings", (d, w) -> {
                    liveTitle = titleEdit.getText().toString().trim();
                    allowComments = swComments.isChecked();
                    slowMode = swSlow.isChecked();
                    allowGifts = swGifts.isChecked();
                    micMuted = swMic.isChecked();
                    updateRailButtonLabels();
                    if (liveId != null && !liveId.isEmpty()) {
                        Fb.db().collection("lives").document(liveId).set(Fb.map(
                                "title", liveTitle,
                                "allowComments", allowComments,
                                "slowMode", slowMode,
                                "allowGifts", allowGifts,
                                "micMuted", micMuted), SetOptions.merge());
                    }
                    refreshHudViews();
                    Ui.toast(this, "Live settings updated ✓");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private Switch settingSwitch(LinearLayout parent, String label, boolean initial) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(r, 2, 10, 2, 6);
        r.addView(Ui.tvBold(this, label, 14, Ui.txt(this)), new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(initial);
        r.addView(sw);
        parent.addView(r);
        return sw;
    }

    private void showViewersDialog() {
        if (viewerIds.isEmpty()) {
            Ui.info(this, "👁 Active Viewers", "Currently watching: " + Math.max(1, currentViewers));
            return;
        }
        LinearLayout list = Ui.col(this);
        Ui.pad(list, 16, 10, 16, 10);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("👁 Live Viewers (" + viewerIds.size() + ")")
                .setView(sv)
                .setNegativeButton("Close", null)
                .show();

        for (String vUid : viewerIds) {
            Fb.getUser(vUid).addOnSuccessListener(u -> {
                if (u == null || isFinishing()) return;
                LinearLayout row = Ui.row(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                Ui.pad(row, 4, 8, 4, 8);
                ImageView av = new ImageView(this);
                Ui.avatar(this, av, u.photoUrl);
                int s = Ui.dp(this, 36);
                row.addView(av, new LinearLayout.LayoutParams(s, s));
                TextView nm = Ui.tvBold(this, u.displayName + (vUid.equals(hostId) ? " (Host)" : ""), 14, Ui.txt(this));
                nm.setPadding(Ui.dp(this, 10), 0, 0, 0);
                row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
                row.setOnClickListener(v -> {
                    dlg.dismiss();
                    startActivity(new Intent(this, ProfileActivity.class).putExtra("uid", u.uid));
                });
                list.addView(row);
            });
        }
    }

    private void toggleFollowHost() {
        String me = Fb.uid();
        if (me == null || hostId.isEmpty() || me.equals(hostId)) return;
        boolean already = Fb.me != null && Fb.me.following != null && Fb.me.following.contains(hostId);
        Fb.db().collection("users").document(me)
                .update("following", already ? FieldValue.arrayRemove(hostId) : FieldValue.arrayUnion(hostId));
        Fb.db().collection("users").document(hostId)
                .update("followers", already ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
        if (Fb.me != null && Fb.me.following != null) {
            if (already) Fb.me.following.remove(hostId);
            else Fb.me.following.add(hostId);
        }
        if (!already) Notify.send(hostId, "follow", "started following you from your Live 🔴", null);
        refreshHudViews();
    }

    private void shareLiveStream() {
        String link = AdsConfig.OFFICIAL_WEBSITE_URL + "/?live=" + (liveId == null ? "" : liveId);
        Ui.choose(this, "📤 Share Live Broadcast",
                new String[]{"📝 Share Live invite to News Feed", "📤 Share to WhatsApp / other apps", "🔗 Copy Live link"},
                w -> {
                    if (w == 0) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("authorId", Fb.uid());
                        p.put("authorName", Fb.myName());
                        p.put("authorPhotoUrl", Fb.myPhoto());
                        p.put("content", "🔴 Watching " + hostName + " LIVE right now: \"" + liveTitle + "\"! Join in on friendbook 🔥");
                        p.put("mediaType", "none");
                        p.put("privacy", "Public");
                        p.put("likes", 0);
                        p.put("comments", 0);
                        p.put("shares", 0);
                        p.put("createdAt", FieldValue.serverTimestamp());
                        Fb.db().collection("posts").add(p)
                                .addOnSuccessListener(x -> Ui.toast(this, "Shared Live invite to Feed ✓"));
                    } else if (w == 1) {
                        Intent s = new Intent(Intent.ACTION_SEND);
                        s.setType("text/plain");
                        s.putExtra(Intent.EXTRA_TEXT, "🔴 Watch " + hostName + " LIVE on friendbook: " + liveTitle + "\n" + link);
                        startActivity(Intent.createChooser(s, "Share Live"));
                    } else {
                        Ui.copy(this, link);
                    }
                });
    }

    private void reportLiveStream() {
        Ui.choose(this, "🚩 Report Live Stream",
                new String[]{"🔞 Nudity or inappropriate content", "🚨 Child safety violation", "🩸 Violence or harassment", "🎭 Spam / fake stream"},
                w -> {
                    Fb.db().collection("reports").add(Fb.map(
                            "type", "live",
                            "liveId", liveId,
                            "authorId", hostId,
                            "reportedBy", Fb.uid(),
                            "createdAt", FieldValue.serverTimestamp()));
                    Ui.toast(this, "Report submitted to Moderators ✓");
                });
    }

    private void giftStarsDialog() {
        if (!allowGifts) {
            Ui.toast(this, "Host turned off gifts for this Live");
            return;
        }
        String[] opts = {"⭐ 10 Stars · Cheer!", "🌟 50 Stars · Super Fan!", "💫 100 Stars · Mega Star!", "👑 500 Stars · Crown Legend!"};
        int[] amounts = {10, 50, 100, 500};
        Ui.choose(this, "⭐ Send Community Stars to " + hostName, opts, i -> sendStars(amounts[i]));
    }

    private void sendStars(int stars) {
        if (hostId.isEmpty() || liveId == null) return;
        Fb.db().collection("gifts").add(Fb.map(
                "liveId", liveId, "fromUid", Fb.uid(), "fromName", Fb.myName(),
                "toUid", hostId, "stars", stars, "createdAt", FieldValue.serverTimestamp()));
        Fb.db().collection("lives").document(liveId).update("totalStars", FieldValue.increment(stars));
        Fb.db().collection("users").document(hostId).update("stars", FieldValue.increment(stars));
        Fb.db().collection("lives").document(liveId).collection("comments").add(Fb.map(
                "userId", Fb.uid(), "userName", Fb.myName(), "userPhoto", Fb.myPhoto(),
                "type", "gift", "text", "sent ⭐ " + stars + " Stars! 🎉",
                "createdAt", FieldValue.serverTimestamp()));
        spawnFloatingEmoji("⭐");
        Notify.send(hostId, "gift", "⭐ sent you " + stars + " Stars on your Live!", null);
        Ui.toast(this, "Sent ⭐ " + stars + " Stars!");
    }

    private void flashGiftBanner(String text) {
        giftBannerTv.setText("🎁 " + text);
        giftBannerTv.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideGiftBanner);
        uiHandler.postDelayed(hideGiftBanner, 3200L);
    }

    private final Runnable hideGiftBanner = () -> {
        if (giftBannerTv != null) giftBannerTv.setVisibility(View.GONE);
    };

    // ==================== END LIVE & SUMMARY RECAP ====================

    private void onExitOrEndPressed() {
        if (isHost || Admin.isAdmin()) {
            Ui.confirm(this, "🔴 End Live Broadcast?",
                    "End this live stream for all viewers?",
                    "End Live", this::endBroadcastAndShowSummary);
        } else {
            finish();
        }
    }

    private void endBroadcastAndShowSummary() {
        if (endingShown) { finish(); return; }
        endingShown = true;
        if (liveId != null && !liveId.isEmpty()) {
            Fb.db().collection("lives").document(liveId).update(
                    "status", "ended", "endedAt", FieldValue.serverTimestamp());
        }
        stopCameraThread();
        if (!isHost) { finish(); return; }

        long durSec = liveStartMs > 0 ? Math.max(1L, (System.currentTimeMillis() - liveStartMs) / 1000L) : 0L;
        String durStr = String.format(Locale.US, "%02d:%02d", durSec / 60L, durSec % 60L);
        String summary = "⏱ Duration: " + durStr
                + "\n👁 Peak Viewers: " + Math.max(1, peakViewers)
                + "\n❤️ Total Reactions: " + totalLikes
                + "\n💬 Total Comments: " + totalCommentsCount
                + "\n⭐ Stars Earned: " + totalStars;

        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle("🎬 Live Broadcast Ended")
                .setMessage(summary)
                .setPositiveButton("Share Recap to Feed", (d, w) -> {
                    Fb.db().collection("posts").add(Fb.map(
                            "authorId", Fb.uid(),
                            "authorName", Fb.myName(),
                            "authorPhotoUrl", Fb.myPhoto(),
                            "content", "🎬 Just wrapped up my Live stream: \"" + liveTitle + "\"!\n"
                                    + "👁 " + Math.max(1, peakViewers) + " viewers · ❤️ " + totalLikes + " likes · ⭐ " + totalStars + " stars. Thanks for tuning in! 💙",
                            "mediaType", "none",
                            "privacy", livePrivacy,
                            "likes", 0, "comments", 0, "shares", 0,
                            "createdAt", FieldValue.serverTimestamp()));
                    Ui.toast(this, "Live recap shared to Feed ✓");
                    finish();
                })
                .setNegativeButton("Done", (d, w) -> finish())
                .show();
    }

    @Override
    public void onBackPressed() {
        if (isHost && isLiveStarted && !endingShown) {
            onExitOrEndPressed();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        if (liveReg != null) liveReg.remove();
        if (chatReg != null) chatReg.remove();
        stopCameraThread();
        if (liveId != null && !liveId.isEmpty()) {
            if (isHost && isLiveStarted && !endingShown) {
                Fb.db().collection("lives").document(liveId).update(
                        "status", "ended", "endedAt", FieldValue.serverTimestamp());
            } else if (joinedCounted) {
                String me = Fb.uid();
                Map<String, Object> up = new HashMap<>();
                up.put("viewerCount", FieldValue.increment(-1));
                if (me != null) up.put("viewerIds", FieldValue.arrayRemove(me));
                Fb.db().collection("lives").document(liveId).update(up);
            }
        }
    }

    // ==================== LIVE CHAT ADAPTER WITH MODERATION ====================

    private class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int v) {
            LinearLayout row = Ui.row(LiveActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(LiveActivity.this, 0x66000000, 14));
            Ui.pad(row, 10, 5, 10, 5);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-2, -2);
            lp.bottomMargin = Ui.dp(LiveActivity.this, 4);
            row.setLayoutParams(lp);
            TextView tv = Ui.tv(LiveActivity.this, "", 13, Color.WHITE);
            row.addView(tv);
            return new VH(row, tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Map<String, Object> m = comments.get(pos);
            String cid = Format.str(m, "_id");
            String uid = Format.str(m, "userId");
            String who = Format.strOr(m, "userName", "Viewer");
            String text = Format.str(m, "text");
            boolean gift = "gift".equals(Format.str(m, "type"));
            boolean hostMsg = Format.boolOf(m, "isHostMsg") || uid.equals(hostId);

            String prefix = hostMsg ? "👑 " : "";
            h.tv.setText(prefix + who + ": " + text);
            h.tv.setTextColor(gift ? 0xFFFFD54F : (hostMsg ? 0xFF82B1FF : Color.WHITE));
            h.itemView.setBackground(Ui.round(LiveActivity.this, gift ? 0x88FF6F00 : 0x66000000, 14));

            h.itemView.setOnClickListener(v -> {
                if (!isHost && !Admin.canModerate()) {
                    if (!uid.isEmpty()) {
                        startActivity(new Intent(LiveActivity.this, ProfileActivity.class).putExtra("uid", uid));
                    }
                    return;
                }
                PopupMenu pm = new PopupMenu(LiveActivity.this, v);
                if (isHost) pm.getMenu().add(0, 1, 0, "📌 Pin this message");
                pm.getMenu().add(0, 2, 0, "🗑 Delete comment");
                if (isHost && !uid.isEmpty() && !uid.equals(hostId)) {
                    boolean muted = mutedUserIds.contains(uid);
                    pm.getMenu().add(0, 3, 0, muted ? "🔊 Unmute " + who : "🔇 Mute " + who + " in Live");
                }
                pm.getMenu().add(0, 4, 0, "👤 View " + who + "'s profile");
                pm.setOnMenuItemClickListener(it -> {
                    int id = it.getItemId();
                    if (id == 1) updatePinnedComment(who + ": " + text);
                    else if (id == 2 && !cid.isEmpty()) {
                        Fb.db().collection("lives").document(liveId)
                                .collection("comments").document(cid).delete();
                    } else if (id == 3) {
                        boolean muted = mutedUserIds.contains(uid);
                        Fb.db().collection("lives").document(liveId).update("mutedUserIds",
                                muted ? FieldValue.arrayRemove(uid) : FieldValue.arrayUnion(uid));
                        Ui.toast(LiveActivity.this, muted ? "Unmuted " + who : "Muted " + who);
                    } else if (id == 4 && !uid.isEmpty()) {
                        startActivity(new Intent(LiveActivity.this, ProfileActivity.class).putExtra("uid", uid));
                    }
                    return true;
                });
                pm.show();
            });
        }

        @Override public int getItemCount() { return comments.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView tv;
            VH(View root, TextView t) { super(root); tv = t; }
        }
    }
}
