package com.friendbook.social.reels;

import android.content.Context;

import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.models.Models;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded disk cache for exact playback renditions, with one unmetered-Wi-Fi lookahead. */
public final class ReelVideoCache {

    private static final String DIR_NAME = "reels_fast_cache";
    private static final long MAX_CACHE_BYTES = 240L * 1024L * 1024L; // 240 MB persistent LRU cache
    private static final long MAX_PREFETCH_FILE_BYTES = 45L * 1024L * 1024L; // cap single prefetch safety

    private static final ExecutorService PREFETCH_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reel-turbo-prefetch");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final ConcurrentHashMap<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();

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

    private static volatile String wantedWarmKey="";
    public static void cancelUpcomingWarm() { wantedWarmKey=""; }

    /** Asynchronously downloads a single Reel video into the persistent SSD cache if not cached yet. */
    private static void prefetchSingle(final Context c, final String rawVideoUrl, final Runnable onReady) {
        if (!canPrefetch(c) || rawVideoUrl == null || rawVideoUrl.isEmpty()) return;
        if (rawVideoUrl.startsWith("file:") || rawVideoUrl.startsWith("content:")) return;
        final Context appCtx = c.getApplicationContext();
        final String streamUrl = startupUrl(appCtx, rawVideoUrl);
        final String key = cacheKey(streamUrl);
        if (key.isEmpty()) return;
        wantedWarmKey=key;
        File existing = new File(cacheDir(appCtx), key + ".mp4");
        if (existing.exists() && existing.length() > 1024L) {
            notifyReady(key, onReady);
            return;
        }
        if (IN_FLIGHT.putIfAbsent(key, Boolean.TRUE) != null) return;

        PREFETCH_POOL.execute(() -> {
            File dir = cacheDir(appCtx);
            File dest = new File(dir, key + ".mp4");
            File part = new File(dir, key + ".part");
            HttpURLConnection conn = null;
            try {
                if (!key.equals(wantedWarmKey) || !canPrefetch(appCtx) || (dest.exists() && dest.length() > 1024L)) return;
                conn = (HttpURLConnection) new URL(streamUrl).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(45000);
                conn.setRequestProperty("Accept", "video/mp4,video/*,*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    long contentLen = conn.getContentLengthLong();
                    if (contentLen > MAX_PREFETCH_FILE_BYTES) return;
                    try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
                         FileOutputStream fos = new FileOutputStream(part);
                         BufferedOutputStream out = new BufferedOutputStream(fos, 64 * 1024)) {
                        byte[] buf = new byte[64 * 1024];
                        long total = 0L;
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if(!key.equals(wantedWarmKey)) { part.delete(); return; }
                            out.write(buf, 0, n);
                            total += n;
                            if (total > MAX_PREFETCH_FILE_BYTES) {
                                out.close();
                                //noinspection ResultOfMethodCallIgnored
                                part.delete();
                                return;
                            }
                        }
                        out.flush();
                    }
                    if (key.equals(wantedWarmKey) && completeDownload(contentLen,part.length()) && part.renameTo(dest)) {
                        Prefs.recordBytes(appCtx, dest.length(), Math.max(0L, dest.length() * 2));
                        trimCacheIfNeeded(dir);
                        notifyReady(key, onReady);
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                    }
                }
            } catch (Throwable ignored) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            } finally {
                IN_FLIGHT.remove(key);
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private static boolean canPrefetch(Context c) {
        if (c == null || Prefs.dataSaver(c) || Prefs.turbo2g(c) || FastCache.netTier(c)!=FastCache.NetTier.WIFI) return false;
        android.net.ConnectivityManager cm=c.getSystemService(android.net.ConnectivityManager.class);
        if (cm==null || cm.isActiveNetworkMetered()) return false;
        android.net.Network network=cm.getActiveNetwork();
        android.net.NetworkCapabilities caps=network==null?null:cm.getNetworkCapabilities(network);
        return caps!=null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    static boolean completeDownload(long expected, long actual) {
        return expected > 1024L && expected <= MAX_PREFETCH_FILE_BYTES && actual == expected;
    }

    private static void notifyReady(String key, Runnable callback) {
        if (callback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (key.equals(wantedWarmKey)) callback.run();
        });
    }

    /** Never duplicate the playing stream. At most one next Reel is warmed on unmetered Wi-Fi. */
    public static void warmUpcomingAfterPlay(Context c, List<Models.Reel> reels, int currentIndex, Runnable onReady) {
        if (!canPrefetch(c) || reels==null || currentIndex<0 || currentIndex>=reels.size()-1) return;
        Models.Reel next=reels.get(currentIndex+1);
        if (next==null || next.videoUrl==null || !next.videoUrl.matches("(?i)https://[^?#]+\\.mp4")) return;
        prefetchSingle(c,next.videoUrl,onReady);
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
