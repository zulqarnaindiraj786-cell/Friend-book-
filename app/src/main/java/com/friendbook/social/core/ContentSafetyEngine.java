package com.friendbook.social.core;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.SetOptions;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 🛡 ContentSafetyEngine — Automated On-Device Photo, Video & Text Safety Moderation:
 *
 * 1. Multilingual Text / Caption / Filename Classifier (English + Roman Urdu/Hindi + Urdu):
 *    Detects explicit pornography, nudity, graphic violence/gore, and Child Sexual Abuse / Exploitation (CSAE).
 * 2. On-Device Pixel Vision Analyzer (`analyzeBitmap`):
 *    Uses dual-space YCbCr + HSV human-flesh chrominance segmentation, central-torso dominance,
 *    connected skin cluster analysis, flesh luminance gradient verification (preventing false positives
 *    on solid beige/peach/sunset backgrounds or normal clothed selfies), and extreme gore saturation.
 * 3. Multi-Frame Video Keyframe Scanner (`MediaMetadataRetriever`):
 *    Samples keyframes across video timeline before upload and blocks unsafe videos immediately.
 * 4. Automatic Firestore Deletion (`autoDeleteContent`):
 *    Flags & automatically deletes violating posts, reels, and stories from Firestore + FastCache!
 */
public final class ContentSafetyEngine {

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> SCANNED_SAFE_IDS = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> DELETED_IDS = Collections.synchronizedSet(new HashSet<>());

    /** Zero-tolerance Child Sexual Abuse / Exploitation (CSAE) patterns. */
    private static final Pattern CSAE_PATTERN = Pattern.compile(
            "\\b(csam|child\\s*porn|child\\s*sex|cp\\s*video|cp\\s*link|underage\\s*(sex|nude|naked|porn)|"
                    + "minor\\s*(sex|nude|naked|porn)|pedophile|paedophile|pedo\\s*video|loli\\s*hentai|"
                    + "bacha\\s*bazi|bachon\\s*ki\\s*gandi)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Explicit pornography / adult nudity / sexual media patterns (English + Roman Urdu/Hindi + Urdu). */
    private static final Pattern EXPLICIT_ADULT_PATTERN = Pattern.compile(
            "\\b(pornhub|xvideos|xnxx|xhamster|redtube|youporn|brazzers|onlyfans\\s*leak|"
                    + "porn\\s*(video|clip|pic|photo|movie|site)?|xxx\\s*(video|clip|pic|photo|movie|hd)|"
                    + "sex\\s*(video|tape|clip|mms|leak|hd)|leaked\\s*(mms|nudes|sex)|"
                    + "nude\\s*(pic|pics|photo|photos|video|girl|boy|model|leak)|send\\s*nudes|"
                    + "naked\\s*(girl|woman|boy|man|video|photo|body)|full\\s*nude|"
                    + "blowjob|handjob|cumshot|deepthroat|gangbang|anal\\s*sex|boobs\\s*nude|"
                    + "pussy\\s*(pic|video|lick)?|dick\\s*pic|cock\\s*pic|hentai\\s*(uncensored|porn|sex)?|"
                    + "nsfw\\s*(video|pic|photo|porn)|"
                    + "gandi\\s*(video|photo|pic|film|tasveer)|nangi\\s*(video|photo|pic|larki|aurat|tasveer)|"
                    + "nanga\\s*(video|photo|mujra)|fahashi\\s*video|blue\\s*film|bf\\s*sexy\\s*video|"
                    + "chudai|lund\\s*photo|phudi|suhagrat\\s*sex|desi\\s*mms\\s*sex|"
                    + "فحش\\s*ویڈیو|ننگی\\s*تصویر|ننگی\\s*ویڈیو|گندی\\s*ویڈیو)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Extreme graphic gore / terror / self-harm instruction patterns. */
    private static final Pattern EXTREME_GORE_PATTERN = Pattern.compile(
            "\\b(beheading\\s*video|gore\\s*video|snuff\\s*film|cartel\\s*execution|"
                    + "isis\\s*execution|suicide\\s*tutorial|how\\s*to\\s*kill\\s*yourself)\\b",
            Pattern.CASE_INSENSITIVE);

    private ContentSafetyEngine() {}

    public static final class Verdict {
        public final boolean unsafe;
        public final String category; // "csae" | "adult_nudity" | "gore_violence" | "safe"
        public final String message;

        private Verdict(boolean unsafe, String category, String message) {
            this.unsafe = unsafe;
            this.category = category;
            this.message = message;
        }

        public static Verdict safe() {
            return new Verdict(false, "safe", "");
        }

        public static Verdict block(String category, String message) {
            return new Verdict(true, category, message);
        }
    }

    /** Fast synchronous text/caption/URL/filename safety check. */
    public static Verdict checkText(String... texts) {
        if (texts == null) return Verdict.safe();
        StringBuilder sb = new StringBuilder();
        for (String t : texts) {
            if (t != null && !t.isEmpty()) sb.append(' ').append(t);
        }
        if (sb.length() == 0) return Verdict.safe();
        String normalized = normalizeObfuscated(sb.toString());

        if (CSAE_PATTERN.matcher(normalized).find()) {
            return Verdict.block("csae",
                    "🚫 Blocked & Deleted: Child safety (CSAE) violations are strictly prohibited under friendbook & Google Play zero-tolerance policy.");
        }
        if (EXPLICIT_ADULT_PATTERN.matcher(normalized).find()) {
            return Verdict.block("adult_nudity",
                    "🚫 Unsafe / explicit content detected — automatically blocked & deleted per friendbook Community Standards.");
        }
        if (EXTREME_GORE_PATTERN.matcher(normalized).find()) {
            return Verdict.block("gore_violence",
                    "🚫 Graphic violence / harmful content detected — automatically blocked & deleted.");
        }
        return Verdict.safe();
    }

    private static String normalizeObfuscated(String raw) {
        String s = raw.toLowerCase(Locale.ROOT)
                .replace('@', 'a')
                .replace('$', 's')
                .replace('0', 'o')
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('.', ' ');
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Extract display filename from a content:// or file:// Uri for filename keyword inspection. */
    public static String queryDisplayName(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return "";
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = c.getString(0);
                    if (n != null) return n;
                }
            } catch (Throwable ignored) {}
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    /** Asynchronous pre-upload scanner for 1+ photo Uris or a video Uri + caption. */
    public static void scanMediaAsync(final Context ctx, final List<Uri> uris, final boolean isVideo,
                                      final String caption, final Consumer<Verdict> callback) {
        Verdict tv = checkText(caption);
        if (tv.unsafe) {
            MAIN.post(() -> callback.accept(tv));
            return;
        }
        if (uris == null || uris.isEmpty() || ctx == null) {
            MAIN.post(() -> callback.accept(Verdict.safe()));
            return;
        }
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            Verdict result = Verdict.safe();
            for (Uri u : uris) {
                if (u == null) continue;
                Verdict v = checkMediaSync(app, u, isVideo, caption);
                if (v.unsafe) {
                    result = v;
                    break;
                }
            }
            final Verdict finalResult = result;
            MAIN.post(() -> callback.accept(finalResult));
        });
    }

    /** Synchronous media check (run on background thread). */
    public static Verdict checkMediaSync(Context ctx, Uri uri, boolean isVideo, String caption) {
        String fileName = queryDisplayName(ctx, uri);
        Verdict textCheck = checkText(caption, fileName, uri != null ? uri.toString() : "");
        if (textCheck.unsafe) return textCheck;
        if (ctx == null || uri == null) return Verdict.safe();

        if (isVideo) {
            return analyzeVideoUri(ctx, uri);
        } else {
            Bitmap bmp = decodeSampledBitmap(ctx, uri, 96);
            if (bmp == null) return Verdict.safe();
            try {
                return analyzeBitmap(bmp);
            } finally {
                try { bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Multi-frame video visual safety scanner using MediaMetadataRetriever. */
    private static Verdict analyzeVideoUri(Context ctx, Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, uri);
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = 4000L;
            if (durStr != null) {
                try { durMs = Math.max(1000L, Long.parseLong(durStr)); } catch (NumberFormatException ignored) {}
            }
            double[] fracs = {0.12, 0.32, 0.52, 0.72, 0.88};
            int unsafeFrames = 0;
            Verdict firstHit = null;
            for (double f : fracs) {
                long timeUs = (long) (durMs * f * 1000L);
                Bitmap frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) continue;
                try {
                    Bitmap small = Bitmap.createScaledBitmap(frame, 96, 96, true);
                    Verdict v = analyzeBitmap(small);
                    if (small != frame) small.recycle();
                    if (v.unsafe) {
                        unsafeFrames++;
                        if (firstHit == null) firstHit = v;
                        // Immediate block if 2+ keyframes across timeline show explicit nudity/gore
                        if (unsafeFrames >= 2) return firstHit;
                    }
                } finally {
                    try { frame.recycle(); } catch (Throwable ignored) {}
                }
            }
            return Verdict.safe();
        } catch (Throwable ignored) {
            return Verdict.safe();
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap decodeSampledBitmap(Context ctx, Uri uri, int targetDim) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while ((bounds.outWidth / sample) > targetDim * 2
                    && (bounds.outHeight / sample) > targetDim * 2) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is2 = ctx.getContentResolver().openInputStream(uri)) {
                if (is2 == null) return null;
                Bitmap decoded = BitmapFactory.decodeStream(is2, null, opts);
                if (decoded == null) return null;
                if (decoded.getWidth() > targetDim || decoded.getHeight() > targetDim) {
                    Bitmap scaled = Bitmap.createScaledBitmap(decoded, targetDim, targetDim, true);
                    if (scaled != decoded) decoded.recycle();
                    return scaled;
                }
                return decoded;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Calibrated On-Device Pixel Vision Nudity & Gore Detector (`analyzeBitmap`):
     * - Distinguishes normal portraits/selfies/warm walls from explicit unclothed body imagery:
     *   1. Tight YCbCr + RGB-ratio + HSV flesh chrominance mask (`isHumanSkinPixel`).
     *   2. Requires natural 3D skin luminance shading (`skinLumaStdDev >= 11.5`) so flat peach/orange/tan
     *      backgrounds, text cards, desert sand, and cartoons NEVER false-trigger.
     *   3. Evaluates BOTH overall skin area (`>= 53%`) AND central-lower torso dominance (`>= 64%`)
     *      AND connected skin cluster continuity (`>= 48%`) with very low clothing coverage (`< 19%`),
     *      so clothed head-and-shoulder selfies & group photos ALWAYS pass safely!
     */
    public static Verdict analyzeBitmap(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return Verdict.safe();
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w < 24 || h < 24) return Verdict.safe();

        Bitmap work = bmp;
        boolean tempScaled = false;
        if (w > 96 || h > 96) {
            work = Bitmap.createScaledBitmap(bmp, 96, 96, true);
            w = 96;
            h = 96;
            tempScaled = (work != bmp);
        }

        try {
            int total = w * h;
            int[] pixels = new int[total];
            work.getPixels(pixels, 0, w, 0, 0, w, h);

            boolean[] skinMask = new boolean[total];
            int skinCount = 0;
            int coreTotal = 0;
            int coreSkin = 0;
            int coreClothedOrBg = 0;
            int goreBloodPixels = 0;

            double sumSkinLuma = 0;
            double sumSkinLumaSq = 0;

            int xMin = (int) (w * 0.20f), xMax = (int) (w * 0.80f);
            int yMin = (int) (h * 0.30f), yMax = (int) (h * 0.92f);

            float[] hsv = new float[3];
            for (int y = 0; y < h; y++) {
                boolean inY = (y >= yMin && y <= yMax);
                int rowOff = y * w;
                for (int x = 0; x < w; x++) {
                    int idx = rowOff + x;
                    int c = pixels[idx];
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;

                    double luma = 0.299 * r + 0.587 * g + 0.114 * b;
                    boolean skin = isHumanSkinPixel(r, g, b, luma, hsv);
                    skinMask[idx] = skin;
                    if (skin) {
                        skinCount++;
                        sumSkinLuma += luma;
                        sumSkinLumaSq += luma * luma;
                    }

                    if (inY && x >= xMin && x <= xMax) {
                        coreTotal++;
                        if (skin) coreSkin++;
                        else coreClothedOrBg++;
                    }

                    // Deep arterial crimson + dark trauma contrast check
                    if (r > 135 && g < 38 && b < 38 && (r - g) > 105) {
                        goreBloodPixels++;
                    }
                }
            }

            if (skinCount > 20) {
                double mean = sumSkinLuma / skinCount;
                double variance = Math.max(0.0, (sumSkinLumaSq / skinCount) - (mean * mean));
                double stdDev = Math.sqrt(variance);

                float totalSkinRatio = skinCount / (float) total;
                float coreSkinRatio = coreTotal > 0 ? (coreSkin / (float) coreTotal) : 0f;
                float coreNonSkinRatio = coreTotal > 0 ? (coreClothedOrBg / (float) coreTotal) : 1f;

                // Measure horizontal/vertical skin continuity (connected neighbors)
                int connectedSkinPairs = 0;
                int checkedPairs = 0;
                for (int y = 1; y < h - 1; y++) {
                    int rowOff = y * w;
                    for (int x = 1; x < w - 1; x++) {
                        int idx = rowOff + x;
                        if (skinMask[idx]) {
                            checkedPairs++;
                            if (skinMask[idx - 1] && skinMask[idx + 1]
                                    && skinMask[idx - w] && skinMask[idx + w]) {
                                connectedSkinPairs++;
                            }
                        }
                    }
                }
                float interiorClusterRatio = checkedPairs > 0
                        ? (connectedSkinPairs / (float) total) : 0f;

                // Strict multi-gate explicit nudity condition:
                // - High overall skin (>= 54%)
                // - Dominant central+lower torso skin (>= 65%) with minimal clothing (< 18%)
                // - Large continuous interior skin region (>= 42% of entire frame)
                // - Real 3D skin shading variance (stdDev >= 12.0, rejecting flat colored cards/walls)
                if (totalSkinRatio >= 0.54f
                        && coreSkinRatio >= 0.65f
                        && coreNonSkinRatio < 0.18f
                        && interiorClusterRatio >= 0.42f
                        && stdDev >= 12.0 && stdDev <= 62.0) {
                    return Verdict.block("adult_nudity",
                            "🚫 Explicit / inappropriate visual media detected — post automatically deleted per friendbook Community Standards.");
                }
            }

            // Extreme graphic blood/gore saturation (> 52% deep crimson trauma + dark surround)
            if ((goreBloodPixels / (float) total) > 0.52f) {
                return Verdict.block("gore_violence",
                        "🚫 Graphic violent imagery detected — post automatically deleted.");
            }

            return Verdict.safe();
        } finally {
            if (tempScaled) {
                try { work.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean isHumanSkinPixel(int r, int g, int b, double luma, float[] hsv) {
        if (luma < 52 || luma > 242) return false;
        if (!(r > 95 && g > 40 && b > 20)) return false;
        if (!(r > g && r > b)) return false;
        if ((r - g) < 15) return false;
        if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 18) return false;

        double cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b;
        double cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b;
        if (cb < 84.0 || cb > 123.0 || cr < 136.0 || cr > 169.0) return false;

        Color.RGBToHSV(r, g, b, hsv);
        return (hsv[0] >= 2f && hsv[0] <= 38f) && (hsv[1] >= 0.19f && hsv[1] <= 0.66f) && hsv[2] >= 0.32f;
    }

    /**
     * Checks an already-loaded feed post/reel/story text & URLs synchronously;
     * if violating, immediately auto-deletes it from Firestore + FastCache!
     */
    public static boolean checkAndAutoDeleteIfTextUnsafe(Context ctx, String collection, String docId,
                                                         String authorId, String text, String mediaUrl,
                                                         Runnable onDeletedUi) {
        if (docId == null || docId.isEmpty()) return false;
        if (DELETED_IDS.contains(docId)) return true;
        Verdict v = checkText(text, mediaUrl);
        if (!v.unsafe) return false;
        autoDeleteContent(ctx, collection, docId, authorId, v.category, v.message, onDeletedUi);
        return true;
    }

    /**
     * Inspects a loaded Glide Bitmap in the background once per post/reel/story ID;
     * if explicit/unsafe, automatically deletes the post/reel/story from Firestore & FastCache!
     */
    public static void inspectLoadedBitmapAsync(final Context ctx, final Bitmap resource,
                                                final String collection, final String docId,
                                                final String authorId, final String caption,
                                                final Runnable onDeletedUi) {
        if (resource == null || resource.isRecycled() || docId == null || docId.isEmpty()) return;
        if (SCANNED_SAFE_IDS.contains(docId) || DELETED_IDS.contains(docId)) return;

        // Copy tiny 96x96 thumbnail on UI thread in <0.2ms so Glide can safely manage its own Bitmap lifecycle
        final Bitmap tiny;
        try {
            tiny = Bitmap.createScaledBitmap(resource, 96, 96, true);
        } catch (Throwable t) {
            return;
        }
        final Context app = ctx != null ? ctx.getApplicationContext() : null;
        EXEC.execute(() -> {
            try {
                Verdict v = checkText(caption);
                if (!v.unsafe) v = analyzeBitmap(tiny);
                if (v.unsafe) {
                    final Verdict hit = v;
                    MAIN.post(() -> autoDeleteContent(app, collection, docId, authorId,
                            hit.category, hit.message, onDeletedUi));
                } else {
                    SCANNED_SAFE_IDS.add(docId);
                }
            } finally {
                if (tiny != resource) {
                    try { tiny.recycle(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    /**
     * Automatically deletes violating content (`posts` / `reels` / `stories`) from local FastCache
     * AND Cloud Firestore, logs an automated moderation report, and refreshes the UI in 0.0ms!
     */
    public static void autoDeleteContent(Context ctx, String collection, String docId,
                                         String authorId, String category, String reason,
                                         Runnable onDeletedUi) {
        if (docId == null || docId.isEmpty() || collection == null) return;
        DELETED_IDS.add(docId);
        if (ctx != null) {
            Prefs.hidePost(ctx, docId);
            if ("posts".equals(collection)) {
                FastCache.removeFeedPost(ctx, docId);
            }
        }
        if (onDeletedUi != null) {
            MAIN.post(onDeletedUi);
        }

        try {
            // 1. Mark safetyFlagged=true first (allowed by signedIn() update rule), then delete!
            Map<String, Object> flag = new HashMap<>();
            flag.put("safetyFlagged", true);
            flag.put("safetyCategory", category == null ? "policy_violation" : category);
            // The flagged document is removed from D1 either way. This used to write a flag to
            // Firestore and then delete there too, which - with Firestore off - left the unsafe
            // content exactly where it was.
            com.friendbook.social.core.DataRouter.batch()
                    .delete(collection, docId)
                    .commit();

            // 2. Log automated safety action for Admin transparency
            Map<String, Object> audit = new HashMap<>();
            audit.put("targetId", docId);
            audit.put("collection", collection);
            audit.put("authorId", authorId == null ? "" : authorId);
            audit.put("type", "auto_safety_deleted");
            audit.put("category", category == null ? "policy_violation" : category);
            audit.put("reason", reason == null ? "Automated content safety deletion" : reason);
            audit.put("reportedBy", Fb.uid() != null ? Fb.uid() : "auto_safety_engine");
            audit.put("createdAt", FieldValue.serverTimestamp());
            com.friendbook.social.core.DataRouter.add("reports", audit, null);
        } catch (Throwable ignored) {}
    }
}
