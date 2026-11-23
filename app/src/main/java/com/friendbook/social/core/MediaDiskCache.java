package com.friendbook.social.core;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared on-device disk cache for story, chat and full-screen videos.
 *
 * Rule of the app: a video URL is downloaded at most once. The first open streams it from the
 * network while the same bytes land in this cache; every later open plays straight from local
 * storage (zero network, zero D1/R2 reads). Nothing here ever polls — downloads start only when a
 * screen the user opened asks for a URL.
 *
 * Photos are cached by Glide (see {@code FriendbookGlideModule}), videos by this class.
 */
public final class MediaDiskCache {

    private static final String DIR_NAME = "media_disk_cache";
    /** Total persistent budget for story / chat / full-screen videos. */
    public static final long MAX_CACHE_BYTES = 256L * 1024L * 1024L;
    /** A single URL larger than this is streamed every time instead of cached. */
    public static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final long MIN_USABLE_BYTES = 1024L;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "media-disk-cache");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final ConcurrentHashMap<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();

    private MediaDiskCache() {}

    static File dir(Context c) {
        File d = new File(c.getApplicationContext().getCacheDir(), DIR_NAME);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** Stable, file-safe cache key for a media URL. */
    public static String key(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(trimmed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", digest[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(trimmed.hashCode());
        }
    }

    /** The cached file for this URL, or null when it has not been downloaded yet. */
    public static File cachedFile(Context c, String url) {
        if (c == null || url == null || url.isEmpty()) return null;
        if (isLocal(url)) return null;
        String k = key(url);
        if (k.isEmpty()) return null;
        File f = new File(dir(c), k + mediaExtension(url));
        if (f.isFile() && f.length() > MIN_USABLE_BYTES) {
            // Touch for LRU retention; best effort only.
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        return null;
    }

    /**
     * URI for a player: the local file when the video is already cached, otherwise the remote URL.
     * A remote URL also warms the cache in the background so the next open is instant.
     */
    public static Uri uriForPlayback(Context c, String url) {
        if (url == null || url.isEmpty()) return Uri.EMPTY;
        if (isLocal(url)) return Uri.parse(url);
        File cached = cachedFile(c, url);
        if (cached != null) return Uri.fromFile(cached);
        warm(c, url);
        return Uri.parse(url);
    }

    /** True when the next playback of this URL will not touch the network. */
    public static boolean isCached(Context c, String url) {
        return cachedFile(c, url) != null;
    }

    /** Downloads a URL into the cache once. Safe to call repeatedly; duplicates are ignored. */
    public static void warm(Context c, String url) {
        if (c == null || url == null || url.isEmpty() || isLocal(url)) return;
        final Context app = c.getApplicationContext();
        final String k = key(url);
        if (k.isEmpty()) return;
        final File dest = new File(dir(app), k + mediaExtension(url));
        if (dest.isFile() && dest.length() > MIN_USABLE_BYTES) return;
        if (IN_FLIGHT.putIfAbsent(k, Boolean.TRUE) != null) return;

        POOL.execute(() -> {
            File part = new File(dir(app), k + ".part");
            HttpURLConnection conn = null;
            try {
                if (dest.isFile() && dest.length() > MIN_USABLE_BYTES) return;
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(45000);
                conn.setRequestProperty("Accept", "video/mp4,video/*,*/*");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) return;
                long declared = conn.getContentLengthLong();
                if (declared > MAX_FILE_BYTES) return;
                long total = 0L;
                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
                     FileOutputStream fos = new FileOutputStream(part);
                     BufferedOutputStream out = new BufferedOutputStream(fos, 64 * 1024)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_FILE_BYTES) {
                            out.close();
                            //noinspection ResultOfMethodCallIgnored
                            part.delete();
                            return;
                        }
                        out.write(buf, 0, n);
                    }
                    out.flush();
                }
                if (part.length() > MIN_USABLE_BYTES) {
                    //noinspection ResultOfMethodCallIgnored
                    if (dest.exists()) dest.delete();
                    //noinspection ResultOfMethodCallIgnored
                    part.renameTo(dest);
                    trim(app);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
            } catch (Throwable ignored) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            } finally {
                IN_FLIGHT.remove(k);
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    /** Warms several URLs (for example the rest of a story author's unseen pages). */
    public static void warmAll(Context c, List<String> urls) {
        if (c == null || urls == null) return;
        for (String u : urls) warm(c, u);
    }

    /** Evicts least-recently-used files until the cache is back inside its byte budget. */
    public static void trim(Context c) {
        File d = dir(c);
        File[] files = d.listFiles();
        if (files == null) return;
        long total = 0L;
        List<File> all = new ArrayList<>(Arrays.asList(files));
        for (File f : all) total += f.length();
        if (total <= MAX_CACHE_BYTES) return;
        all.sort(Comparator.comparingLong(File::lastModified));
        for (File f : all) {
            if (total <= MAX_CACHE_BYTES) break;
            long len = f.length();
            //noinspection ResultOfMethodCallIgnored
            if (f.delete()) total -= len;
        }
    }

    /** Bytes currently held on disk (used by diagnostics and tests). */
    public static long cacheBytes(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File f : files) total += f.length();
        return total;
    }

    /** Clears the cache (Settings → free up space). */
    public static void clear(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static boolean isLocal(String url) {
        return url.startsWith("file:") || url.startsWith("content:") || url.startsWith("/");
    }

    private static String mediaExtension(String url) {
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        if (q > 0) lower = lower.substring(0, q);
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a")) return ".m4a";
        if (lower.endsWith(".webm")) return ".webm";
        if (lower.endsWith(".mov")) return ".mov";
        return ".mp4";
    }
}
