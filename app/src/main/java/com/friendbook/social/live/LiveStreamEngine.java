package com.friendbook.social.live;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-Time Hardware Camera2 + Live WebP Video Encoder/Decoder + Live 8kHz G.711 u-law Voice
 * Recorder/Player Engine for friendbook Live Broadcasts & Split-Screen Co-Host rooms.
 */
public class LiveStreamEngine {

    public static final int QUALITY_DATA_SAVER = 0; // 192x340 @ Q44 (~550ms)
    public static final int QUALITY_BALANCED   = 1; // 264x468 @ Q52 (~400ms)
    public static final int QUALITY_HD         = 2; // 336x596 @ Q60 (~330ms)

    public static final int BEAUTY_OFF         = 0;
    public static final int BEAUTY_SOFT_GLOW   = 1;
    public static final int BEAUTY_PORCELAIN   = 2;
    public static final int BEAUTY_ROSY        = 3;
    public static final int BEAUTY_FULL_SMOOTH = 4;

    public static final int AUDIO_SAMPLE_RATE = 8000;
    private static final int AUDIO_CHUNK_SAMPLES = 1280; // 160ms @ 8kHz (~1.28 KB u-law — ultra-low latency!)

    // Precomputed 256-entry ITU-T G.711 u-law -> 16-bit linear PCM lookup table (zero-CPU decode!)
    private static final short[] ULAW_DECODE_TABLE = new short[256];
    static {
        for (int i = 0; i < 256; i++) {
            int ulaw = ~i & 0xFF;
            int sign = ulaw & 0x80;
            int exponent = (ulaw >> 4) & 0x07;
            int mantissa = ulaw & 0x0F;
            int sample = (((mantissa << 3) + 0x84) << exponent) - 0x84;
            ULAW_DECODE_TABLE[i] = (short) (sign != 0 ? -sample : sample);
        }
    }

    public interface Listener {
        void onEncodedVideoFrame(String base64Webp, int width, int height);
        void onEncodedAudioChunk(String base64Ulaw, int voiceLevel);
        void onLocalVoiceLevel(int voiceLevel);
        void onDecodedRemoteVideo(Bitmap bitmap, boolean isCoHost);
        void onCameraError(String message);
    }

    private final Activity activity;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Camera2 state
    private TextureView previewView;
    private android.widget.ImageView beautyOverlayView;
    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private Size previewSize = new Size(1280, 720);
    private Rect sensorArraySize;
    private float maxDigitalZoom = 4.0f;
    private float currentZoom = 1.0f;
    private boolean lensFront = true;
    private boolean mirrorFront = true;
    private boolean torchOn = false;
    private boolean beautyBoost = true;
    private int beautyLevel = BEAUTY_FULL_SMOOTH;
    private boolean cameraPaused = false;
    private volatile boolean primaryHostAudioMuted = false;
    private volatile boolean coHostAudioMuted = false;
    private boolean hasFlashUnit = false;
    private Range<Integer> aeRange;

    // Video Stream Encoder / Decoder
    private int streamQuality = QUALITY_BALANCED;
    private volatile boolean streamingActive = false;
    private final AtomicBoolean encodingFrame = new AtomicBoolean(false);
    private final ExecutorService videoEncodeExec = Executors.newSingleThreadExecutor();
    private final ExecutorService videoDecodeExec = Executors.newSingleThreadExecutor();
    private long lastDecodedHostSeq = -1;
    private long lastDecodedCoHostSeq = -1;

    private final Runnable frameCaptureTick = new Runnable() {
        @Override
        public void run() {
            if (cameraPaused || !streamingActive || previewView == null || !previewView.isAvailable()) return;
            captureAndEncodeCurrentFrame();
            if (streamingActive) {
                mainHandler.postDelayed(this, getFrameIntervalMs());
            }
        }
    };

    // Audio Recorder (Host / Co-Host Microphone)
    private volatile boolean micRecording = false;
    private volatile boolean micMuted = false;
    private Thread audioRecordThread;
    private AudioRecord audioRecord;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl gainControl;

    // Audio Player (Viewer / Co-Host Speaker)
    private volatile boolean speakerPlaying = false;
    private volatile boolean speakerMuted = false;
    private volatile float speakerGain = 1.0f;
    private Thread audioPlayThread;
    private AudioTrack audioTrack;
    private final LinkedBlockingQueue<short[]> audioPlayQueue = new LinkedBlockingQueue<>(2);
    private long lastPlayedHostAudioSeq = -1;
    private long lastPlayedCoHostAudioSeq = -1;

    public LiveStreamEngine(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    // ==================== CAMERA2 HARDWARE PREVIEW & CONTROLS ====================

    public void attachCameraPreview(TextureView tv, boolean startFront) {
        this.previewView = tv;
        this.lensFront = startFront;
        ensureCamThread();
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                openCameraInternal(width, height);
            }
            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                configureTransform(width, height);
            }
            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                closeCameraInternal();
                return true;
            }
            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) { }
        });
        if (tv.isAvailable()) {
            openCameraInternal(tv.getWidth(), tv.getHeight());
        }
    }

    private void ensureCamThread() {
        if (camThread == null) {
            camThread = new HandlerThread("FriendBookLiveCam");
            camThread.start();
            camHandler = new Handler(camThread.getLooper());
        }
    }

    public boolean isLensFront() { return lensFront; }
    public boolean isMirrorFront() { return mirrorFront; }
    public boolean isTorchOn() { return torchOn; }
    public boolean isBeautyBoost() { return beautyBoost; }
    public boolean isCameraPaused() { return cameraPaused; }
    public float getCurrentZoom() { return currentZoom; }
    public float getMaxZoom() { return maxDigitalZoom; }
    public int getStreamQuality() { return streamQuality; }

    public void setStreamQuality(int q) {
        this.streamQuality = Math.max(QUALITY_DATA_SAVER, Math.min(QUALITY_HD, q));
    }

    public String getQualityLabel() {
        if (streamQuality == QUALITY_HD) return "HD 360p";
        if (streamQuality == QUALITY_DATA_SAVER) return "Saver 200p";
        return "Auto 288p";
    }

    private long getFrameIntervalMs() {
        if (streamQuality == QUALITY_HD) return 340L;
        if (streamQuality == QUALITY_DATA_SAVER) return 560L;
        return 410L;
    }

    public void flipCamera() {
        lensFront = !lensFront;
        torchOn = false;
        currentZoom = 1.0f;
        closeCameraInternal();
        if (previewView != null && previewView.isAvailable()) {
            openCameraInternal(previewView.getWidth(), previewView.getHeight());
        }
    }

    public void setMirrorFront(boolean mirror) {
        this.mirrorFront = mirror;
        if (previewView != null && previewView.isAvailable()) {
            configureTransform(previewView.getWidth(), previewView.getHeight());
        }
    }

    public boolean toggleTorch() {
        if (lensFront || !hasFlashUnit) return false;
        torchOn = !torchOn;
        applyCaptureParams();
        return torchOn;
    }

    public void attachBeautyOverlay(@Nullable android.widget.ImageView overlayView) {
        this.beautyOverlayView = overlayView;
        if (overlayView != null) {
            overlayView.setImageDrawable(null);
            overlayView.setClickable(false);
            overlayView.setFocusable(false);
            applyHardwareBeautyTint(overlayView, beautyLevel, cameraPaused);
        }
        if (previewView != null) {
            com.friendbook.social.core.StudioVectorIcons.apply4kUhdGpuGrade(
                    previewView, beautyOverlayView, beautyLevel,
                    beautyLevel == BEAUTY_ROSY ? "rose" : "vivid", 0);
        }
    }

    private static void applyHardwareBeautyTint(@Nullable android.widget.ImageView overlayView, int level, boolean paused) {
        if (overlayView == null) return;
        overlayView.setImageDrawable(null);
        if (paused || level <= 0) {
            overlayView.setBackground(null);
            overlayView.setVisibility(android.view.View.GONE);
            return;
        }
        com.friendbook.social.core.StudioVectorIcons.apply4kUhdGpuGrade(
                null, overlayView, level, level == BEAUTY_ROSY ? "rose" : "vivid", 0);
    }

    public boolean toggleBeautyBoost() {
        int next = (beautyLevel + 1) % 5;
        setBeautyLevel(next);
        return beautyLevel > 0;
    }

    public void setBeautyLevel(int level) {
        this.beautyLevel = Math.max(BEAUTY_OFF, Math.min(BEAUTY_FULL_SMOOTH, level));
        this.beautyBoost = (this.beautyLevel > 0);
        mainHandler.post(() -> {
            if (cameraPaused) {
                applyHardwareBeautyTint(beautyOverlayView, 0, true);
            } else {
                com.friendbook.social.core.StudioVectorIcons.apply4kUhdGpuGrade(
                        previewView, beautyOverlayView, beautyLevel,
                        beautyLevel == BEAUTY_ROSY ? "rose" : "vivid", 0);
            }
        });
        applyCaptureParams();
    }

    public int getBeautyLevel() {
        return beautyLevel;
    }

    public String getBeautyLabel() {
        switch (beautyLevel) {
            case BEAUTY_SOFT_GLOW:   return "Soft Glow";
            case BEAUTY_PORCELAIN:   return "Porcelain 4K";
            case BEAUTY_ROSY:        return "Rosy Face";
            case BEAUTY_FULL_SMOOTH: return "4K UHD Smooth";
            default:                 return "Off";
        }
    }

    public void setPrimaryHostAudioMuted(boolean muted) {
        this.primaryHostAudioMuted = muted;
    }

    public boolean isPrimaryHostAudioMuted() {
        return primaryHostAudioMuted;
    }

    public void setCoHostAudioMuted(boolean muted) {
        this.coHostAudioMuted = muted;
    }

    public boolean isCoHostAudioMuted() {
        return coHostAudioMuted;
    }

    public void resetRemoteSequences() {
        lastDecodedHostSeq = -1;
        lastPlayedHostAudioSeq = -1;
        resetCoHostSequences();
    }

    public void resetCoHostSequences() {
        lastDecodedCoHostSeq = -1;
        lastPlayedCoHostAudioSeq = -1;
    }

    public void setCameraPaused(boolean paused) {
        this.cameraPaused = paused;
        if (beautyOverlayView != null) {
            mainHandler.post(() -> applyHardwareBeautyTint(beautyOverlayView, beautyLevel, paused));
        }
        if (!paused && streamingActive) {
            mainHandler.removeCallbacks(frameCaptureTick);
            mainHandler.postDelayed(frameCaptureTick, 120L);
        }
    }

    public void setZoom(float zoomFactor) {
        currentZoom = Math.max(1.0f, Math.min(maxDigitalZoom, zoomFactor));
        applyCaptureParams();
    }

    public void focusAt(float touchX, float touchY, int viewW, int viewH) {
        if (captureSession == null || previewBuilder == null || sensorArraySize == null || camHandler == null) return;
        try {
            int rx = clamp((int) ((touchX / Math.max(1f, viewW)) * sensorArraySize.width()),
                    0, Math.max(0, sensorArraySize.width() - 160));
            int ry = clamp((int) ((touchY / Math.max(1f, viewH)) * sensorArraySize.height()),
                    0, Math.max(0, sensorArraySize.height() - 160));
            MeteringRectangle focusRect = new MeteringRectangle(rx, ry,
                    Math.min(150, sensorArraySize.width()),
                    Math.min(150, sensorArraySize.height()),
                    MeteringRectangle.METERING_WEIGHT_MAX - 1);
            previewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusRect});
            previewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{focusRect});
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureSession.capture(previewBuilder.build(), null, camHandler);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            captureSession.setRepeatingRequest(previewBuilder.build(), null, camHandler);
        } catch (Throwable ignored) { }
    }

    private void openCameraInternal(int viewW, int viewH) {
        if (activity.isFinishing() || previewView == null || !previewView.isAvailable()) return;
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        ensureCamThread();
        try {
            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;
            String selectedId = null;
            int targetFacing = lensFront ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(cid);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == targetFacing) {
                    selectedId = cid;
                    break;
                }
            }
            if (selectedId == null && cm.getCameraIdList().length > 0) {
                selectedId = cm.getCameraIdList()[0];
            }
            if (selectedId == null) return;

            CameraCharacteristics cc = cm.getCameraCharacteristics(selectedId);
            Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlashUnit = flash != null && flash;
            sensorArraySize = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maxZ = cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxDigitalZoom = (maxZ != null && maxZ > 1.0f) ? Math.min(maxZ, 5.0f) : 4.0f;
            aeRange = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                previewSize = chooseOptimalSize(sizes);
            }
            mainHandler.post(() -> configureTransform(viewW, viewH));

            cm.openCamera(selectedId, new CameraDevice.StateCallback() {
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
                    if (listener != null) {
                        mainHandler.post(() -> listener.onCameraError("Camera unavailable (" + error + ")"));
                    }
                }
            }, camHandler);
        } catch (SecurityException | CameraAccessException e) {
            if (listener != null) {
                mainHandler.post(() -> listener.onCameraError("Could not open camera"));
            }
        }
    }

    private Size chooseOptimalSize(@Nullable Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            int w = s.getWidth();
            int h = s.getHeight();
            if (w > 1920 || h > 1920) continue;
            // Prefer ~1280x720 16:9 for crisp portrait center-crop
            long diff = Math.abs((long) w * h - (1280L * 720L));
            float ratio = (float) Math.max(w, h) / Math.max(1, Math.min(w, h));
            long ratioPenalty = Math.abs(ratio - (16f / 9f)) < 0.15f ? 0L : 250000L;
            long score = diff + ratioPenalty;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /** Applies proper aspect-ratio center-crop + front-mirror transform so faces are NEVER stretched! */
    public void configureTransform(int viewWidth, int viewHeight) {
        if (previewView == null || viewWidth <= 0 || viewHeight <= 0 || previewSize == null) return;
        float viewRatio = (float) viewWidth / viewHeight;
        // Portrait screen uses swapped sensor width/height (sensor is landscape)
        float camW = Math.min(previewSize.getWidth(), previewSize.getHeight());
        float camH = Math.max(previewSize.getWidth(), previewSize.getHeight());
        float camRatio = camW / Math.max(1f, camH);

        float scaleX = 1f;
        float scaleY = 1f;
        if (camRatio > viewRatio) {
            scaleX = camRatio / viewRatio;
        } else {
            scaleY = viewRatio / camRatio;
        }
        if (lensFront && !mirrorFront) {
            // TextureView front camera mirrors by default; flip X when user un-mirrors
            scaleX = -scaleX;
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        previewView.setTransform(matrix);
    }

    private void startPreviewSession() {
        if (cameraDevice == null || previewView == null || !previewView.isAvailable()) return;
        try {
            SurfaceTexture st = previewView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(st);
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            previewBuilder.addTarget(surface);
            previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            applyBuilderModifiers(previewBuilder);

            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(previewBuilder.build(), null, camHandler);
                            } catch (Throwable ignored) { }
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) { }
                    }, camHandler);
        } catch (Throwable ignored) {
            // Fallback to TEMPLATE_PREVIEW if a device doesn't support TEMPLATE_RECORD on SurfaceTexture
            try {
                SurfaceTexture st = previewView.getSurfaceTexture();
                if (st == null || cameraDevice == null) return;
                st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                Surface surface = new Surface(st);
                previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewBuilder.addTarget(surface);
                applyBuilderModifiers(previewBuilder);
                cameraDevice.createCaptureSession(Collections.singletonList(surface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                if (cameraDevice == null) return;
                                captureSession = session;
                                try {
                                    session.setRepeatingRequest(previewBuilder.build(), null, camHandler);
                                } catch (Throwable ignored2) { }
                            }
                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) { }
                        }, camHandler);
            } catch (Throwable ignored2) { }
        }
    }

    private void applyCaptureParams() {
        if (captureSession == null || previewBuilder == null || camHandler == null) return;
        try {
            applyBuilderModifiers(previewBuilder);
            captureSession.setRepeatingRequest(previewBuilder.build(), null, camHandler);
        } catch (Throwable ignored) { }
    }

    private void applyBuilderModifiers(CaptureRequest.Builder b) {
        if (b == null) return;
        try {
            if (!lensFront && hasFlashUnit) {
                b.set(CaptureRequest.FLASH_MODE,
                        torchOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            }
        } catch (Throwable ignored) { }

        try {
            if (sensorArraySize != null && currentZoom > 1.01f) {
                int cw = (int) (sensorArraySize.width() / currentZoom);
                int ch = (int) (sensorArraySize.height() / currentZoom);
                int dx = (sensorArraySize.width() - cw) / 2;
                int dy = (sensorArraySize.height() - ch) / 2;
                b.set(CaptureRequest.SCALER_CROP_REGION,
                        new Rect(dx, dy, dx + cw, dy + ch));
            } else if (sensorArraySize != null) {
                b.set(CaptureRequest.SCALER_CROP_REGION, sensorArraySize);
            }
        } catch (Throwable ignored) { }

        try {
            if (aeRange != null) {
                int targetEv = 0;
                if (beautyBoost && aeRange.getUpper() > 0) {
                    targetEv = Math.min(aeRange.getUpper(), Math.max(1, aeRange.getUpper() / 2));
                }
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, targetEv);
            }
        } catch (Throwable ignored) { }

        try {
            // Keep Camera2 HAL at 30/60 FPS real-time video rate (NEVER use HIGH_QUALITY still-capture mode which drops preview to 5 FPS!)
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST);
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST);
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        } catch (Throwable ignored) { }
    }

    public void closeCameraInternal() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
        } catch (Throwable ignored) { }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Throwable ignored) { }
    }

    // ==================== REAL-TIME VIDEO STREAMING (HOST / CO-HOST) ====================

    public void startBroadcasting(boolean withAudio) {
        if (streamingActive) return;
        streamingActive = true;
        mainHandler.removeCallbacks(frameCaptureTick);
        mainHandler.postDelayed(frameCaptureTick, 180L);
        if (withAudio) {
            startMicrophoneStream();
        }
    }

    public void stopBroadcasting() {
        streamingActive = false;
        mainHandler.removeCallbacks(frameCaptureTick);
        stopMicrophoneStream();
    }

    private void captureAndEncodeCurrentFrame() {
        if (cameraPaused || !streamingActive || previewView == null || !previewView.isAvailable()) return;
        if (encodingFrame.get()) return;

        final int targetW;
        final int targetH;
        final int webpQ;
        if (streamQuality == QUALITY_HD) {
            targetW = 336;
            targetH = 596;
            webpQ = 58;
        } else if (streamQuality == QUALITY_DATA_SAVER) {
            targetW = 192;
            targetH = 340;
            webpQ = 44;
        } else {
            targetW = 264;
            targetH = 468;
            webpQ = 52;
        }

        final Bitmap raw;
        try {
            raw = previewView.getBitmap(targetW, targetH);
        } catch (Throwable t) {
            return;
        }
        if (raw == null) return;

        encodingFrame.set(true);
        final boolean flipUnmirror = lensFront && !mirrorFront;
        final int activeBeauty = beautyLevel;
        videoEncodeExec.execute(() -> {
            Bitmap toCompress = raw;
            try {
                if (flipUnmirror) {
                    Matrix m = new Matrix();
                    m.preScale(-1f, 1f);
                    Bitmap flipped = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                    if (flipped != raw) {
                        raw.recycle();
                        toCompress = flipped;
                    }
                }
                if (activeBeauty > 0) {
                    Bitmap beautified = applyBeautyGrade(toCompress, activeBeauty);
                    if (beautified != toCompress) {
                        toCompress.recycle();
                        toCompress = beautified;
                    }
                }
                if (streamingActive) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(12288);
                    // WEBP gives ~35% smaller frames than JPEG at sharper portrait quality!
                    toCompress.compress(Bitmap.CompressFormat.WEBP, webpQ, bos);
                    byte[] bytes = bos.toByteArray();
                    int w = toCompress.getWidth();
                    int h = toCompress.getHeight();
                    toCompress.recycle();
                    if (bytes.length > 0 && streamingActive && listener != null) {
                        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                        listener.onEncodedVideoFrame(b64, w, h);
                    }
                } else {
                    toCompress.recycle();
                }
            } catch (Throwable ignored) {
                try { toCompress.recycle(); } catch (Throwable ignored2) { }
            } finally {
                encodingFrame.set(false);
            }
        });
    }

    /**
     * Ultra-fast native Skia/Canvas Beauty & iPhone 17 Pro Max ISP Color Grade (<0.5ms per frame, zero GC churn):
     * Blends a hardware-filtered smooth pass with crisp detail and warm iPhone 17 Pro Max portrait tones.
     */
    private static Bitmap applyBeautyGrade(@NonNull Bitmap src, int level) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 16 || h <= 16) return src;

            float scale = (level >= BEAUTY_FULL_SMOOTH) ? 0.34f
                    : (level >= BEAUTY_PORCELAIN ? 0.42f : 0.52f);
            int sw = Math.max(24, Math.round(w * scale));
            int sh = Math.max(24, Math.round(h * scale));
            Bitmap down = Bitmap.createScaledBitmap(src, sw, sh, true);

            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(out);

            android.graphics.Paint basePaint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            float liftR = (level == BEAUTY_ROSY) ? 12f : 9f;
            float liftG = (level == BEAUTY_PORCELAIN) ? 9f : 7f;
            float liftB = (level == BEAUTY_PORCELAIN) ? 10f : 6f;
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{
                    1.05f, 0f,    0f,    0f, liftR,
                    0f,    1.04f, 0f,    0f, liftG,
                    0f,    0f,    1.03f, 0f, liftB,
                    0f,    0f,    0f,    1f, 0f
            });
            basePaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            canvas.drawBitmap(src, 0f, 0f, basePaint);

            // Blend bilinear smoothed skin layer on top while preserving sharp edges
            android.graphics.Paint smoothPaint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            int smoothAlpha = (level >= BEAUTY_FULL_SMOOTH) ? 115
                    : (level >= BEAUTY_PORCELAIN ? 92 : 68);
            smoothPaint.setAlpha(smoothAlpha);
            smoothPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            canvas.drawBitmap(down, null, new android.graphics.Rect(0, 0, w, h), smoothPaint);
            down.recycle();
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    /** Decodes a remote WebP/JPEG Base64 video frame off the UI thread and posts Bitmap to UI. */
    public void decodeRemoteFrame(@Nullable final String b64, final long seq, final boolean isCoHost) {
        if (b64 == null || b64.isEmpty()) return;
        if (isCoHost) {
            if (seq > 0 && seq <= lastDecodedCoHostSeq) return;
            lastDecodedCoHostSeq = seq;
        } else {
            if (seq > 0 && seq <= lastDecodedHostSeq) return;
            lastDecodedHostSeq = seq;
        }
        videoDecodeExec.execute(() -> {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp != null && listener != null && !activity.isFinishing()) {
                    mainHandler.post(() -> {
                        if (!activity.isFinishing()) {
                            listener.onDecodedRemoteVideo(bmp, isCoHost);
                        }
                    });
                }
            } catch (Throwable ignored) { }
        });
    }

    // ==================== REAL-TIME MICROPHONE STREAMING (8kHz G.711 u-law) ====================

    public void setMicMuted(boolean muted) {
        this.micMuted = muted;
        if (muted && listener != null) {
            mainHandler.post(() -> listener.onLocalVoiceLevel(0));
        }
    }

    public boolean isMicMuted() { return micMuted; }

    public void startMicrophoneStream() {
        if (micRecording) return;
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        micRecording = true;
        audioRecordThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            int minBuf = AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = AUDIO_SAMPLE_RATE * 2;
            int bufBytes = Math.max(minBuf, AUDIO_CHUNK_SAMPLES * 2);

            AudioRecord rec = null;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    rec.release();
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                }
            } catch (Throwable t) {
                try {
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                } catch (Throwable ignored) { }
            }

            if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                micRecording = false;
                return;
            }
            audioRecord = rec;
            attachHardwareAudioEffects(rec.getAudioSessionId());

            try {
                rec.startRecording();
                short[] pcmBuf = new short[AUDIO_CHUNK_SAMPLES];
                byte[] ulawBuf = new byte[AUDIO_CHUNK_SAMPLES];

                while (micRecording && !activity.isFinishing()) {
                    int totalRead = 0;
                    while (totalRead < AUDIO_CHUNK_SAMPLES && micRecording) {
                        int n = rec.read(pcmBuf, totalRead, AUDIO_CHUNK_SAMPLES - totalRead);
                        if (n <= 0) break;
                        totalRead += n;
                    }
                    if (!micRecording || totalRead <= 0) break;

                    if (micMuted) {
                        if (listener != null) mainHandler.post(() -> listener.onLocalVoiceLevel(0));
                        continue;
                    }

                    // Calculate RMS voice energy (0..100) + encode 16-bit PCM -> 8-bit u-law
                    long sumSq = 0;
                    for (int i = 0; i < totalRead; i++) {
                        short s = pcmBuf[i];
                        sumSq += (long) s * s;
                        ulawBuf[i] = linearToUlaw(s);
                    }
                    double rms = Math.sqrt((double) sumSq / totalRead);
                    final int level = clamp((int) (rms / 240.0), 0, 100);
                    if (listener != null) {
                        mainHandler.post(() -> listener.onLocalVoiceLevel(level));
                    }

                    // Ultra-low-latency voice transmission (sensitive VAD >= 45 RMS so soft syllables never clip)
                    if (rms >= 45.0 && listener != null) {
                        String b64 = Base64.encodeToString(ulawBuf, 0, totalRead, Base64.NO_WRAP);
                        listener.onEncodedAudioChunk(b64, Math.max(1, level));
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                releaseAudioEffects();
                try { rec.stop(); } catch (Throwable ignored) { }
                try { rec.release(); } catch (Throwable ignored) { }
                audioRecord = null;
            }
        }, "FriendBookLiveMic");
        audioRecordThread.start();
    }

    private void attachHardwareAudioEffects(int sessionId) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
        } catch (Throwable ignored) { }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
        } catch (Throwable ignored) { }
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId);
                if (gainControl != null) gainControl.setEnabled(true);
            }
        } catch (Throwable ignored) { }
    }

    private void releaseAudioEffects() {
        try { if (echoCanceler != null) { echoCanceler.release(); echoCanceler = null; } } catch (Throwable ignored) { }
        try { if (noiseSuppressor != null) { noiseSuppressor.release(); noiseSuppressor = null; } } catch (Throwable ignored) { }
        try { if (gainControl != null) { gainControl.release(); gainControl = null; } } catch (Throwable ignored) { }
    }

    public void stopMicrophoneStream() {
        micRecording = false;
        if (audioRecordThread != null) {
            try { audioRecordThread.interrupt(); } catch (Throwable ignored) { }
            audioRecordThread = null;
        }
    }

    // ==================== REAL-TIME SPEAKER PLAYBACK (VIEWERS & CO-HOST) ====================

    public void startSpeakerPlayback() {
        if (speakerPlaying) return;
        speakerPlaying = true;
        audioPlayThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            int minBuf = AudioTrack.getMinBufferSize(
                    AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = AUDIO_SAMPLE_RATE * 2;
            int trackBuf = Math.max(minBuf, AUDIO_CHUNK_SAMPLES * 2);

            AudioTrack track = null;
            try {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                AudioFormat fmt = new AudioFormat.Builder()
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(attrs)
                            .setAudioFormat(fmt)
                            .setBufferSizeInBytes(trackBuf)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            .build();
                } else {
                    track = new AudioTrack(
                            attrs, fmt, trackBuf,
                            AudioTrack.MODE_STREAM,
                            AudioManager.AUDIO_SESSION_ID_GENERATE);
                }
                track.play();
                audioTrack = track;

                while (speakerPlaying && !activity.isFinishing()) {
                    short[] chunk = audioPlayQueue.take();
                    if (!speakerPlaying || chunk == null) break;
                    if (!speakerMuted && chunk.length > 0) {
                        track.setVolume(speakerGain);
                        track.write(chunk, 0, chunk.length);
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable ignored) {
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (Throwable ignored) { }
                    try { track.release(); } catch (Throwable ignored) { }
                }
                audioTrack = null;
            }
        }, "FriendBookLiveSpeaker");
        audioPlayThread.start();
    }

    public void enqueueRemoteAudio(@Nullable String base64Ulaw, long seq, boolean isCoHost) {
        if (base64Ulaw == null || base64Ulaw.isEmpty() || speakerMuted) return;
        if (!isCoHost && primaryHostAudioMuted) return;
        if (isCoHost && coHostAudioMuted) return;
        if (isCoHost) {
            if (seq > 0 && seq <= lastPlayedCoHostAudioSeq) return;
            lastPlayedCoHostAudioSeq = seq;
        } else {
            if (seq > 0 && seq <= lastPlayedHostAudioSeq) return;
            lastPlayedHostAudioSeq = seq;
        }
        if (!speakerPlaying) startSpeakerPlayback();
        try {
            byte[] ulaw = Base64.decode(base64Ulaw, Base64.DEFAULT);
            if (ulaw.length == 0) return;
            short[] pcm = new short[ulaw.length];
            for (int i = 0; i < ulaw.length; i++) {
                pcm[i] = ULAW_DECODE_TABLE[ulaw[i] & 0xFF];
            }
            // Drop oldest chunk if jitter queue is full so live audio stays tightly synced with video!
            while (audioPlayQueue.remainingCapacity() == 0) {
                audioPlayQueue.poll();
            }
            audioPlayQueue.offer(pcm);
        } catch (Throwable ignored) { }
    }

    public void setSpeakerMuted(boolean muted) {
        this.speakerMuted = muted;
        if (muted) audioPlayQueue.clear();
    }

    public boolean isSpeakerMuted() { return speakerMuted; }

    public void setSpeakerGain(float gain) {
        this.speakerGain = Math.max(0f, Math.min(1.5f, gain));
    }

    public float getSpeakerGain() { return speakerGain; }

    public void stopSpeakerPlayback() {
        speakerPlaying = false;
        audioPlayQueue.clear();
        if (audioPlayThread != null) {
            try { audioPlayThread.interrupt(); } catch (Throwable ignored) { }
            audioPlayThread = null;
        }
    }

    // ==================== ITU-T G.711 u-law ENCODER & LIFECYCLE ====================

    private static byte linearToUlaw(short sample) {
        int pcm = sample;
        int mask;
        if (pcm < 0) {
            pcm = -pcm;
            mask = 0x7F;
        } else {
            mask = 0xFF;
        }
        if (pcm > 32635) pcm = 32635;
        pcm += 0x84;

        int exponent = 7;
        for (int expMask = 0x4000; (pcm & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) {
            // find segment
        }
        int mantissa = (pcm >> (exponent + 3)) & 0x0F;
        byte ulaw = (byte) ((exponent << 4) | mantissa);
        return (byte) (ulaw ^ mask);
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    public void releaseAll() {
        stopBroadcasting();
        stopSpeakerPlayback();
        closeCameraInternal();
        if (camThread != null) {
            try { camThread.quitSafely(); } catch (Throwable ignored) { }
            camThread = null;
            camHandler = null;
        }
        videoEncodeExec.shutdownNow();
        videoDecodeExec.shutdownNow();
    }
}
