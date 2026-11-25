package com.friendbook.social.core;

import android.content.Context;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** STEP 6: a photo/video URL is downloaded once and then served from local disk. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class, manifest = Config.NONE)
public class MediaDiskCacheTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    @Test public void keyIsStableAndDistinctPerUrl() {
        String a = MediaDiskCache.key("https://cdn.example.com/video/a.mp4");
        String b = MediaDiskCache.key("https://cdn.example.com/video/a.mp4");
        String c = MediaDiskCache.key("https://cdn.example.com/video/b.mp4");
        assertEquals(a, b);
        assertFalse(a.isEmpty());
        assertFalse(a.equals(c));
        assertEquals(32, a.length());
        assertEquals("", MediaDiskCache.key(""));
        assertEquals("", MediaDiskCache.key(null));
    }

    @Test public void remoteUrlIsNotCachedUntilBytesExist() {
        String url = "https://cdn.example.com/video/not-yet.mp4";
        assertNull(MediaDiskCache.cachedFile(ctx(), url));
        assertFalse(MediaDiskCache.isCached(ctx(), url));
        Uri uri = MediaDiskCache.uriForPlayback(ctx(), url);
        // Falls back to the network URL for the first view; playback must never fail on a miss.
        assertEquals(url, uri.toString());
    }

    @Test public void cachedBytesArePlayedFromDisk() throws Exception {
        String url = "https://cdn.example.com/video/already-seen.mp4";
        File dest = new File(MediaDiskCache.dir(ctx()), MediaDiskCache.key(url) + ".mp4");
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(new byte[4096]);
        }
        assertTrue(MediaDiskCache.isCached(ctx(), url));
        File cached = MediaDiskCache.cachedFile(ctx(), url);
        assertNotNull(cached);
        Uri uri = MediaDiskCache.uriForPlayback(ctx(), url);
        assertEquals("file", uri.getScheme());
        assertEquals(cached.getAbsolutePath(), uri.getPath());
    }

    @Test public void localUrisAreNeverCopiedOrRewritten() {
        String local = "content://media/external/video/1";
        assertEquals(Uri.parse(local), MediaDiskCache.uriForPlayback(ctx(), local));
        assertNull(MediaDiskCache.cachedFile(ctx(), local));
        assertTrue(MediaDiskCache.isCached(ctx(), local) == false);
    }

    @Test public void clearReleasesEveryByte() throws Exception {
        String url = "https://cdn.example.com/video/clear-me.mp4";
        File dest = new File(MediaDiskCache.dir(ctx()), MediaDiskCache.key(url) + ".mp4");
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(new byte[2048]);
        }
        assertTrue(MediaDiskCache.cacheBytes(ctx()) >= 2048L);
        MediaDiskCache.clear(ctx());
        assertEquals(0L, MediaDiskCache.cacheBytes(ctx()));
    }

    @Test public void budgetIsLargeEnoughForRealVideosButBounded() {
        // A 256 MB budget holds roughly 8-12 short videos; anything bigger is evicted LRU.
        assertTrue(MediaDiskCache.MAX_CACHE_BYTES >= 200L * 1024L * 1024L);
        assertTrue(MediaDiskCache.MAX_CACHE_BYTES <= 512L * 1024L * 1024L);
        assertTrue(MediaDiskCache.MAX_FILE_BYTES < MediaDiskCache.MAX_CACHE_BYTES);
    }
}
