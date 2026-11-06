package com.friendbook.social.reels;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.friendbook.social.core.Cloudinary;
import com.friendbook.social.core.ContentSafetyEngine;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.FieldValue;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 🚀 ReelUploadManager — Instant Background Reel Publisher + Live % Progress Bus:
 * - Copies the picked video to a fast temp cache file so upload survives even if AddReelActivity closes.
 * - Creates an instant optimistic local Reel so the creator can watch their Reel immediately in 0.0ms
 *   while cloud sync finishes in the background (with live % status banner on Reels & AddReelActivity).
 * - Uploads poster thumbnail in parallel + seeds ReelVideoCache so 0 bytes are re-downloaded after publish.
 */
public final class ReelUploadManager {

    public interface Listener {
        void onUploadStateChanged(boolean active, int percent, long sentBytes, long totalBytes,
                                  String message, boolean succeeded, String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile boolean uploading = false;
    private static volatile int currentPercent = 0;
    private static volatile long currentSent = 0L;
    private static volatile long currentTotal = 0L;
    private static volatile String currentStatus = "";
    private static volatile Models.Reel optimisticReel = null;

    private ReelUploadManager() {}

    public static boolean isUploading() { return uploading; }
    public static int getCurrentPercent() { return currentPercent; }
    public static String getCurrentStatus() { return currentStatus; }
    public static Models.Reel getOptimisticReel() { return optimisticReel; }

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) {
            LISTENERS.add(l);
            if (uploading) {
                l.onUploadStateChanged(true, currentPercent, currentSent, currentTotal,
                        currentStatus, false, null);
            }
        }
    }

    public static void removeListener(Listener l) {
        if (l != null) LISTENERS.remove(l);
    }

    private static void emit(final boolean active, final int pct, final long sent, final long total,
                             final String msg, final boolean ok, final String err) {
        uploading = active;
        currentPercent = pct;
        currentSent = sent;
        currentTotal = total;
        currentStatus = msg != null ? msg : "";
        MAIN.post(() -> {
            for (Listener l : LISTENERS) {
                try {
                    l.onUploadStateChanged(active, pct, sent, total, currentStatus, ok, err);
                } catch (Throwable ignored) { }
            }
        });
    }

    public static final class EditOptions {
        public String overlayText = "";
        public int overlayColor = 0xFFFFFFFF;
        public int overlayBgColor = 0xCC000000;
        public float overlayX = 0.5f, overlayY = 0.24f;
        public String stickerText = "";
        public float stickerX = 0.78f, stickerY = 0.32f, stickerScale = 1.0f;
        public String filterPreset = "none";
        public int filterOverlayColor = 0;
        public float playbackSpeed = 1.0f;
        public boolean muteOriginalAudio = false;
        public String audioTitle = "";
        public int trimStartMs = 0, trimEndMs = 0;
        public long coverTimeMs = -1L;
    }

    public static void startUpload(final Context context,
                                   final Uri sourceUri,
                                   final String captionText,
                                   final Cloudinary.VideoMeta meta,
                                   final boolean alreadySafetyVerified) {
        startUpload(context, sourceUri, captionText, meta, alreadySafetyVerified, null);
    }

    public static void startUpload(final Context context,
                                   final Uri sourceUri,
                                   final String captionText,
                                   final Cloudinary.VideoMeta meta,
                                   final boolean alreadySafetyVerified,
                                   final EditOptions editOpts) {
        if (context == null || sourceUri == null) return;
        if (!com.friendbook.social.core.Admin.checkNotSuspended(context)) return;
        if (uploading) {
            Ui.toast(context, "⏳ A Reel upload is already in progress (" + currentPercent + "%)");
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final String caption = captionText != null ? captionText.trim() : "";
        final EditOptions opts = editOpts != null ? editOpts : new EditOptions();

        // Final instant text safety check before starting
        ContentSafetyEngine.Verdict textV = ContentSafetyEngine.checkText(
                caption + " " + (opts.overlayText != null ? opts.overlayText : ""));
        if (textV.unsafe) {
            Ui.toast(context, textV.message);
            return;
        }

        emit(true, 1, 0L, meta != null ? meta.sizeBytes : 0L, "Preparing video…", false, null);

        new Thread(() -> {
            // 1. Copy Uri to a dedicated local staging file so Uri permission remains valid after Activity closes
            File stagingDir = new File(appCtx.getCacheDir(), "reel_upload_staging");
            if (!stagingDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                stagingDir.mkdirs();
            }
            File rawStagedFile = new File(stagingDir, "staging_" + System.currentTimeMillis() + ".mp4");
            try (InputStream in = appCtx.getContentResolver().openInputStream(sourceUri);
                 FileOutputStream fos = new FileOutputStream(rawStagedFile);
                 BufferedOutputStream out = new BufferedOutputStream(fos, 64 * 1024)) {
                if (in == null) {
                    emit(false, 0, 0L, 0L, "", false, "Cannot read selected video file");
                    return;
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                rawStagedFile.delete();
                emit(false, 0, 0L, 0L, "", false, "Could not prepare video: " + t.getMessage());
                return;
            }

            // 1b. If creator trimmed video or muted original audio, physically remux MP4 via MediaExtractor + MediaMuxer!
            File finalFile = rawStagedFile;
            boolean hasTrim = opts.trimStartMs > 0 || (opts.trimEndMs > opts.trimStartMs && meta != null
                    && meta.durationSec > 0 && opts.trimEndMs < (meta.durationSec * 1000 - 350));
            if (hasTrim || opts.muteOriginalAudio) {
                File remuxed = new File(stagingDir, "edited_" + System.currentTimeMillis() + ".mp4");
                if (remuxTrimAndAudio(rawStagedFile, remuxed, opts.trimStartMs, opts.trimEndMs, opts.muteOriginalAudio)
                        && remuxed.exists() && remuxed.length() > 1024) {
                    //noinspection ResultOfMethodCallIgnored
                    rawStagedFile.delete();
                    finalFile = remuxed;
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    remuxed.delete();
                }
            }

            // 1c. Composite cover frame + filter + text overlay + sticker onto posterJpeg
            if (meta != null) {
                byte[] composited = compositePosterJpeg(finalFile, meta.posterJpeg, opts);
                if (composited != null && composited.length > 0) {
                    meta.posterJpeg = composited;
                }
                if (opts.trimEndMs > opts.trimStartMs) {
                    meta.durationSec = Math.max(1, Math.round((opts.trimEndMs - opts.trimStartMs) / 1000f));
                }
            }

            final File stagedFile = finalFile;
            final Uri stagedUri = Uri.fromFile(stagedFile);
            final long fileBytes = stagedFile.length();

            // Create optimistic local Reel so Reels tab can play it immediately at 0.0ms!
            Models.Reel opt = new Models.Reel();
            opt.id = "local_uploading_" + System.currentTimeMillis();
            opt.authorId = Fb.uid();
            opt.authorName = Fb.myName();
            opt.authorPhoto = Fb.myPhoto();
            opt.videoUrl = stagedUri.toString();
            opt.caption = caption;
            opt.durationSec = meta != null ? meta.durationSec : 0;
            opt.createdAt = System.currentTimeMillis();
            opt.overlayText = opts.overlayText != null ? opts.overlayText : "";
            opt.overlayColor = opts.overlayColor;
            opt.overlayBgColor = opts.overlayBgColor;
            opt.overlayX = opts.overlayX;
            opt.overlayY = opts.overlayY;
            opt.stickerText = opts.stickerText != null ? opts.stickerText : "";
            opt.stickerX = opts.stickerX;
            opt.stickerY = opts.stickerY;
            opt.stickerScale = opts.stickerScale;
            opt.filterPreset = opts.filterPreset != null ? opts.filterPreset : "none";
            opt.filterOverlayColor = opts.filterOverlayColor;
            opt.playbackSpeed = opts.playbackSpeed > 0f ? opts.playbackSpeed : 1.0f;
            opt.muteOriginalAudio = opts.muteOriginalAudio;
            opt.audioTitle = opts.audioTitle != null ? opts.audioTitle : "";
            opt.trimStartMs = opts.trimStartMs;
            opt.trimEndMs = opts.trimEndMs;
            opt.likedBy = new ArrayList<>();
            opt.savedBy = new ArrayList<>();
            optimisticReel = opt;

            Runnable runCloudUpload = () -> performCloudUpload(appCtx, stagedFile, stagedUri, fileBytes, caption, meta, opts);

            if (alreadySafetyVerified) {
                MAIN.post(runCloudUpload);
            } else {
                MAIN.post(() -> ContentSafetyEngine.scanMediaAsync(
                        appCtx, java.util.Collections.singletonList(stagedUri), true, caption, verdict -> {
                            if (verdict != null && verdict.unsafe) {
                                optimisticReel = null;
                                //noinspection ResultOfMethodCallIgnored
                                stagedFile.delete();
                                emit(false, 0, 0L, 0L, "", false, verdict.message);
                                return;
                            }
                            runCloudUpload.run();
                        }));
            }
        }, "reel-upload-stage").start();
    }

    private static void performCloudUpload(final Context appCtx,
                                           final File stagedFile,
                                           final Uri stagedUri,
                                           final long totalBytes,
                                           final String caption,
                                           final Cloudinary.VideoMeta meta,
                                           final EditOptions opts) {
        emit(true, 4, 0L, totalBytes, "Uploading Reel… 4%", false, null);

        // Upload tiny ~15 KB poster JPEG in parallel so viewers get a 0.0ms cover image!
        final String[] uploadedThumbHolder = new String[]{""};
        if (meta != null && meta.posterJpeg != null && meta.posterJpeg.length > 0) {
            Cloudinary.uploadJpegBytes(meta.posterJpeg).addOnSuccessListener(url -> {
                if (url != null) uploadedThumbHolder[0] = url;
            });
        }

        String storagePath = "reels/" + Fb.uid() + "_" + System.currentTimeMillis() + ".mp4";
        Cloudinary.uploadOrFirebaseWithProgress(appCtx, storagePath, stagedUri,
                (pct, sent, tot) -> {
                    int shown = Math.max(4, Math.min(98, pct));
                    String mbStr = tot > 0
                            ? String.format(java.util.Locale.US, " (%.1f / %.1f MB)",
                            sent / 1048576f, tot / 1048576f)
                            : "";
                    emit(true, shown, sent, tot, "Uploading Reel… " + shown + "%" + mbStr, false, null);
                }).addOnSuccessListener(videoUrl -> {
            // Seed local SSD cache immediately under the remote videoUrl so playback is 0.0ms!
            ReelVideoCache.seedLocalUriToCache(appCtx, stagedUri, videoUrl);

            String thumbUrl = uploadedThumbHolder[0];
            if (thumbUrl == null || thumbUrl.isEmpty()) {
                thumbUrl = ReelVideoCache.deriveThumbUrl(videoUrl, "");
            }

            Map<String, Object> doc = Fb.map(
                    "authorId", Fb.uid(),
                    "authorName", Fb.myName(),
                    "authorPhotoUrl", Fb.myPhoto(),
                    "videoUrl", videoUrl,
                    "thumbUrl", thumbUrl != null ? thumbUrl : "",
                    "durationSec", meta != null ? meta.durationSec : 0,
                    "caption", caption,
                    "likes", 0,
                    "comments", 0,
                    "shares", 0,
                    "likedBy", new ArrayList<String>(),
                    "savedBy", new ArrayList<String>(),
                    "createdAt", FieldValue.serverTimestamp());
            if (opts != null) {
                if (opts.overlayText != null && !opts.overlayText.trim().isEmpty()) {
                    doc.put("overlayText", opts.overlayText.trim());
                    doc.put("overlayColor", opts.overlayColor);
                    doc.put("overlayBgColor", opts.overlayBgColor);
                    doc.put("overlayX", opts.overlayX);
                    doc.put("overlayY", opts.overlayY);
                }
                if (opts.stickerText != null && !opts.stickerText.trim().isEmpty()) {
                    doc.put("stickerText", opts.stickerText.trim());
                    doc.put("stickerX", opts.stickerX);
                    doc.put("stickerY", opts.stickerY);
                    doc.put("stickerScale", opts.stickerScale);
                }
                if (opts.filterPreset != null && !"none".equals(opts.filterPreset)) {
                    doc.put("filterPreset", opts.filterPreset);
                    doc.put("filterOverlayColor", opts.filterOverlayColor);
                }
                if (Math.abs(opts.playbackSpeed - 1.0f) > 0.02f) {
                    doc.put("playbackSpeed", opts.playbackSpeed);
                }
                if (opts.muteOriginalAudio) {
                    doc.put("muteOriginalAudio", true);
                }
                if (opts.audioTitle != null && !opts.audioTitle.trim().isEmpty()) {
                    doc.put("audioTitle", opts.audioTitle.trim());
                }
                if (opts.trimStartMs > 0 || opts.trimEndMs > 0) {
                    doc.put("trimStartMs", opts.trimStartMs);
                    doc.put("trimEndMs", opts.trimEndMs);
                }
            }

            emit(true, 99, totalBytes, totalBytes, "Publishing Reel… 99%", false, null);
            String cfReelId = "reel_" + System.currentTimeMillis() + "_" + (Fb.uid() != null ? Fb.uid().substring(0, Math.min(6, Fb.uid().length())) : "u");
            if (com.friendbook.social.core.CloudflareStore.enabled()) {
                com.friendbook.social.core.CloudflareStore.put("reels", cfReelId, doc, () -> {
                    optimisticReel = null;
                    MAIN.postDelayed(() -> {
                        try { //noinspection ResultOfMethodCallIgnored
                            stagedFile.delete();
                        } catch (Throwable ignored) { }
                    }, 8000L);
                    emit(false, 100, totalBytes, totalBytes, "✅ Your Reel is Live!", true, null);
                    Ui.toast(appCtx, "🎬 Your Reel is Live!");
                });
                return;
            }
            Fb.db().collection("reels").add(doc).addOnSuccessListener(ref -> {
                optimisticReel = null;
                // Clean up staging file after seeding completes
                MAIN.postDelayed(() -> {
                    try { //noinspection ResultOfMethodCallIgnored
                        stagedFile.delete();
                    } catch (Throwable ignored) { }
                }, 8000L);
                emit(false, 100, totalBytes, totalBytes, "✅ Your Reel is Live!", true, null);
                Ui.toast(appCtx, "🎬 Your Reel is Live!");
            }).addOnFailureListener(e -> {
                optimisticReel = null;
                //noinspection ResultOfMethodCallIgnored
                stagedFile.delete();
                emit(false, 0, 0L, 0L, "", false, e.getMessage() != null ? e.getMessage() : "Publish failed");
            });
        }).addOnFailureListener(e -> {
            optimisticReel = null;
            //noinspection ResultOfMethodCallIgnored
            stagedFile.delete();
            emit(false, 0, 0L, 0L, "", false, e.getMessage() != null ? e.getMessage() : "Video upload failed");
        });
    }

    /** Hardware lossless MP4 remuxer (MediaExtractor + MediaMuxer) for Trim & Mute Original Audio. */
    public static boolean remuxTrimAndAudio(File inputMp4, File outputMp4,
                                            int trimStartMs, int trimEndMs, boolean stripAudio) {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        android.media.MediaMuxer muxer = null;
        try {
            extractor.setDataSource(inputMp4.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            muxer = new android.media.MediaMuxer(outputMp4.getAbsolutePath(),
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] indexMap = new int[trackCount];
            java.util.Arrays.fill(indexMap, -1);
            int maxChunk = 256 * 1024;
            int addedTracks = 0;

            for (int i = 0; i < trackCount; i++) {
                android.media.MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (stripAudio && mime.startsWith("audio/")) continue;
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    indexMap[i] = muxer.addTrack(fmt);
                    addedTracks++;
                    if (fmt.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxChunk = Math.max(maxChunk, fmt.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE));
                    }
                    if (mime.startsWith("video/") && fmt.containsKey(android.media.MediaFormat.KEY_ROTATION)) {
                        try {
                            muxer.setOrientationHint(fmt.getInteger(android.media.MediaFormat.KEY_ROTATION));
                        } catch (Throwable ignored) { }
                    }
                }
            }
            if (addedTracks == 0) return false;

            long startUs = Math.max(0L, trimStartMs * 1000L);
            long endUs = trimEndMs > trimStartMs ? (trimEndMs * 1000L) : Long.MAX_VALUE;
            if (startUs > 0L) {
                extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }

            muxer.start();
            java.nio.ByteBuffer dstBuf = java.nio.ByteBuffer.allocate(maxChunk);
            android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();
            long basePtsUs = -1L;

            while (true) {
                int trackIndex = extractor.getSampleTrackIndex();
                if (trackIndex < 0) break;
                int muxTrack = indexMap[trackIndex];
                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs > endUs) break;
                if (muxTrack >= 0 && sampleTimeUs >= 0) {
                    dstBuf.clear();
                    int sampleSize = extractor.readSampleData(dstBuf, 0);
                    if (sampleSize < 0) break;
                    if (basePtsUs < 0L) basePtsUs = sampleTimeUs;
                    bufferInfo.offset = 0;
                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = Math.max(0L, sampleTimeUs - basePtsUs);
                    int sampleFlags = extractor.getSampleFlags();
                    int codecFlags = 0;
                    if ((sampleFlags & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        codecFlags |= android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    bufferInfo.flags = codecFlags;
                    muxer.writeSampleData(muxTrack, dstBuf, bufferInfo);
                }
                extractor.advance();
            }
            muxer.stop();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { extractor.release(); } catch (Throwable ignored) { }
            if (muxer != null) {
                try { muxer.release(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Composites cover frame + color grade filter + text overlay + sticker onto the Reel's JPEG cover thumbnail. */
    public static byte[] compositePosterJpeg(File videoFile, byte[] fallbackJpeg, EditOptions opts) {
        if (opts == null) return fallbackJpeg;
        android.graphics.Bitmap base = null;
        if (opts.coverTimeMs >= 0L && videoFile != null && videoFile.exists()) {
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            try {
                mmr.setDataSource(videoFile.getAbsolutePath());
                base = mmr.getFrameAtTime(opts.coverTimeMs * 1000L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Throwable ignored) {
            } finally {
                try { mmr.release(); } catch (Throwable ignored) { }
            }
        }
        if (base == null && fallbackJpeg != null && fallbackJpeg.length > 0) {
            try {
                base = android.graphics.BitmapFactory.decodeByteArray(fallbackJpeg, 0, fallbackJpeg.length);
            } catch (Throwable ignored) { }
        }
        if (base == null) return fallbackJpeg;
        try {
            int maxDim = 540;
            int bw = base.getWidth(), bh = base.getHeight();
            if (bw > maxDim || bh > maxDim) {
                float ratio = Math.min((float) maxDim / bw, (float) maxDim / bh);
                bw = Math.max(1, Math.round(bw * ratio));
                bh = Math.max(1, Math.round(bh * ratio));
            }
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                    bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(base, null, new android.graphics.RectF(0, 0, bw, bh), p);

            // 1. Filter overlay tint
            if (opts.filterOverlayColor != 0) {
                p.setColor(opts.filterOverlayColor);
                canvas.drawRect(0, 0, bw, bh, p);
            }

            // 2. Sticker / Emoji overlay
            if (opts.stickerText != null && !opts.stickerText.trim().isEmpty()) {
                p.setColor(0xFFFFFFFF);
                float stSize = Math.max(24f, bw * 0.14f * Math.max(0.6f, Math.min(2.0f, opts.stickerScale)));
                p.setTextSize(stSize);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                float sx = Math.max(stSize, Math.min(bw - stSize, opts.stickerX * bw));
                float sy = Math.max(stSize, Math.min(bh - stSize * 0.5f, opts.stickerY * bh));
                canvas.drawText(opts.stickerText.trim(), sx, sy, p);
            }

            // 3. Text overlay pill
            if (opts.overlayText != null && !opts.overlayText.trim().isEmpty()) {
                String txt = opts.overlayText.trim();
                float textSz = Math.max(16f, bw * 0.052f);
                p.setTextSize(textSz);
                p.setFakeBoldText(true);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                float tw = Math.min(bw * 0.88f, p.measureText(txt));
                float padH = textSz * 0.65f, padV = textSz * 0.45f;
                float cx = Math.max(tw / 2f + padH, Math.min(bw - tw / 2f - padH, opts.overlayX * bw));
                float cy = Math.max(textSz + padV, Math.min(bh - textSz - padV, opts.overlayY * bh));
                if ((opts.overlayBgColor >>> 24) > 0) {
                    p.setColor(opts.overlayBgColor);
                    android.graphics.RectF pill = new android.graphics.RectF(
                            cx - tw / 2f - padH, cy - textSz * 0.85f - padV,
                            cx + tw / 2f + padH, cy + textSz * 0.3f + padV);
                    canvas.drawRoundRect(pill, textSz * 0.55f, textSz * 0.55f, p);
                }
                p.setColor(opts.overlayColor != 0 ? opts.overlayColor : 0xFFFFFFFF);
                canvas.drawText(txt, cx, cy, p);
            }

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(28 * 1024);
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, bos);
            out.recycle();
            if (base != out) base.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            return fallbackJpeg;
        }
    }
}
