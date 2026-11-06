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

    public static void startUpload(final Context context,
                                   final Uri sourceUri,
                                   final String captionText,
                                   final Cloudinary.VideoMeta meta,
                                   final boolean alreadySafetyVerified) {
        if (context == null || sourceUri == null) return;
        if (uploading) {
            Ui.toast(context, "⏳ A Reel upload is already in progress (" + currentPercent + "%)");
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final String caption = captionText != null ? captionText.trim() : "";

        // Final instant text safety check before starting
        ContentSafetyEngine.Verdict textV = ContentSafetyEngine.checkText(caption);
        if (textV.unsafe) {
            Ui.toast(context, textV.message);
            return;
        }

        emit(true, 1, 0L, meta != null ? meta.sizeBytes : 0L, "Preparing Reel for turbo upload…", false, null);

        new Thread(() -> {
            // 1. Copy Uri to a dedicated local staging file so Uri permission remains valid after Activity closes
            File stagingDir = new File(appCtx.getCacheDir(), "reel_upload_staging");
            if (!stagingDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                stagingDir.mkdirs();
            }
            final File stagedFile = new File(stagingDir, "staging_" + System.currentTimeMillis() + ".mp4");
            try (InputStream in = appCtx.getContentResolver().openInputStream(sourceUri);
                 FileOutputStream fos = new FileOutputStream(stagedFile);
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
                stagedFile.delete();
                emit(false, 0, 0L, 0L, "", false, "Could not prepare video: " + t.getMessage());
                return;
            }

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
            opt.likedBy = new ArrayList<>();
            opt.savedBy = new ArrayList<>();
            optimisticReel = opt;

            Runnable runCloudUpload = () -> performCloudUpload(appCtx, stagedFile, stagedUri, fileBytes, caption, meta);

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
                                           final Cloudinary.VideoMeta meta) {
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
}
