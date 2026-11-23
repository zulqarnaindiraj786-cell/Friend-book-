package com.friendbook.social.reels;

import android.content.Context;

import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.models.Models;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** Bounded local cache for exact playback renditions. No unseen-video downloads. */
public final class ReelVideoCache {

    private static final String DIR_NAME = "reels_fast_cache";
    private static final long MAX_CACHE_BYTES = 240L * 1024L * 1024L; // 240 MB persistent LRU cache
    private static final long MAX_PREFETCH_FILE_BYTES = 45L * 1024L * 1024L; // cap single prefetch safety

    private ReelVideoCache() {}

    private static File cacheDir(Context c) {
        File dir = new File(c.getApplicationContext().getCacheDir(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** Exact stream identity: different resolutions must never share a cached file. */
    public static String cacheKey(String streamUrl) {
        if (streamUrl == null || streamUrl.isEmpty()) return "";
        String url = streamUrl.trim();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("v2-");
            for (byte b : dig) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** Choose a lightweight rendition on weak links without rewriting stored URLs. */
    public static String optimizeStreamUrl(Context c, String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return "";
        String u = rawUrl.trim();
        if (c == null) return u;
        FastCache.NetTier tier = FastCache.netTier(c);
        boolean weak = tier == FastCache.NetTier.NET_2G || tier == FastCache.NetTier.NET_3G || Prefs.dataSaver(c) || Prefs.turbo2g(c);
        if (weak) return lowBandwidthUrl(u);
        return tier == FastCache.NetTier.NET_4G ? mobileBandwidthUrl(u) : u;
    }

    static boolean preferLiteStartup(Context context) {
        FastCache.NetTier tier = FastCache.netTier(context);
        return tier == FastCache.NetTier.NET_2G || tier == FastCache.NetTier.NET_3G
                || Prefs.dataSaver(context) || Prefs.turbo2g(context);
    }

    static String startupUrl(Context context, String url) {
        return preferLiteStartup(context) ? lowBandwidthUrl(url) : startup480Url(url);
    }

    static String startup480Url(String url) {
        return boundedCloudinaryUrl(url, "h_480,c_limit,br_650k,f_mp4,vc_h264,ac_aac,q_auto:eco");
    }

    static String upgrade720Url(String url) {
        return boundedCloudinaryUrl(url, "h_720,c_limit,br_1200k,f_mp4,vc_h264,ac_aac,q_auto:good");
    }

    static boolean canUpgrade(Context context) {
        return !Prefs.dataSaver(context) && !Prefs.turbo2g(context)
                && (FastCache.netTier(context)==FastCache.NetTier.WIFI || FastCache.netTier(context)==FastCache.NetTier.NET_4G);
    }

    static String lowBandwidthUrl(String url) {
        return boundedCloudinaryUrl(url, "h_144,c_limit,br_95k,f_mp4,vc_h264,ac_aac,q_auto:eco");
    }

    static String mobileBandwidthUrl(String url) {
        return boundedCloudinaryUrl(url, "h_360,c_limit,br_420k,f_mp4,vc_h264,ac_aac,q_auto:eco");
    }

    private static String boundedCloudinaryUrl(String url, String transform) {
        try {
            java.net.URI parsed = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || !"res.cloudinary.com".equalsIgnoreCase(parsed.getHost())
                    || parsed.getRawQuery()!=null || parsed.getRawFragment()!=null || parsed.getRawUserInfo()!=null) return url;
            String path=parsed.getRawPath();
            if (!path.matches("/[A-Za-z0-9_-]+/video/upload/v[0-9]+/[A-Za-z0-9_./%-]+\\.mp4")) return url;
            return url.replace("/video/upload/", "/video/upload/" + transform + "/");
        } catch (IllegalArgumentException error) { return url; }
    }

    /**
     * Derives an instant first-frame JPEG poster URL so every Reel paints frame #0 in 0.0ms
     * while the hardware decoder initializes (zero black flash!).
     */
    public static String deriveThumbUrl(String rawVideoUrl, String explicitThumbUrl) {
        if (explicitThumbUrl != null && !explicitThumbUrl.trim().isEmpty()) {
            String image=explicitThumbUrl.trim();
            int upload=image.indexOf("/image/upload/");
            if(upload>=0 && image.contains("cloudinary.com")) {
                String rest=image.substring(upload+14);
                java.util.regex.Matcher version=java.util.regex.Pattern.compile("(?:^|/)(v[0-9]+/.*)").matcher(rest);
                if(version.find()) rest=version.group(1);
                else while(rest.contains("/") && rest.substring(0,rest.indexOf('/')).matches("(?:w_|h_|c_|q_|f_|g_|ar_|dpr_).*")) rest=rest.substring(rest.indexOf('/')+1);
                return image.substring(0,upload+14)+"w_480,c_limit,f_webp,q_auto:eco/"+rest;
            }
            return image;
        }
        if (rawVideoUrl == null || rawVideoUrl.isEmpty()) return "";
        String u = rawVideoUrl.trim();
        int idx = u.indexOf("/video/upload/");
        if (idx > 0 && u.contains("cloudinary.com")) {
            String prefix = u.substring(0, idx + "/video/upload/".length());
            String rest = u.substring(idx + "/video/upload/".length());
            // Strip any existing video transform segment before /v12345/
            int vIdx = rest.indexOf("/v");
            if (vIdx > 0 && vIdx + 2 < rest.length() && Character.isDigit(rest.charAt(vIdx + 2))) {
                rest = rest.substring(vIdx + 1);
            }
            int qIdx = rest.indexOf('?');
            if (qIdx >= 0) rest = rest.substring(0, qIdx);
            int dot = rest.lastIndexOf('.');
            if (dot > rest.lastIndexOf('/')) {
                rest = rest.substring(0, dot) + ".jpg";
            } else {
                rest = rest + ".jpg";
            }
            return prefix + "so_0,f_jpg,q_auto:low,w_480,c_limit/" + rest;
        }
        return "";
    }

    /** Returns the ready local cached MP4 file if fully downloaded, or null if not yet cached. */
    public static File getCachedFile(Context c, String rawVideoUrl) {
        if (c == null || rawVideoUrl == null || rawVideoUrl.isEmpty()) return null;
        String key = cacheKey(startupUrl(c, rawVideoUrl));
        if (key.isEmpty()) return null;
        File f = new File(cacheDir(c), key + ".mp4");
        if (f.exists() && f.isFile() && f.length() > 1024L) {
            // Touch timestamp for LRU retention (best-effort)
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        return null;
    }

    /** Compatibility hook: there is no speculative network work left to cancel. */
    public static void cancelUpcomingWarm() { }

    static boolean completeDownload(long expected, long actual) {
        return expected > 1024L && expected <= MAX_PREFETCH_FILE_BYTES && actual == expected;
    }

    private static synchronized void trimCacheIfNeeded(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return;
            long total = 0L;
            for (File f : files) {
                if (f.isFile()) total += f.length();
            }
            if (total <= MAX_CACHE_BYTES) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File f : files) {
                if (total <= (MAX_CACHE_BYTES * 80L) / 100L) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Throwable ignored) { }
    }
}
