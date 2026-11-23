package com.friendbook.social.core;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.module.AppGlideModule;

/**
 * One photo cache for the whole app.
 *
 * Every profile photo, feed photo, story photo and chat photo is downloaded once and then served
 * from this disk cache on every later open, so re-opening a screen never re-downloads the same
 * image bytes. Videos use {@link MediaDiskCache} instead.
 */
@GlideModule
public final class FriendbookGlideModule extends AppGlideModule {

    /** 512 MB of photos on disk; Glide evicts least-recently-used files beyond that. */
    public static final long DISK_CACHE_BYTES = 512L * 1024L * 1024L;
    /** 128 MB of decoded bitmaps in memory so scrolling back never decodes twice. */
    public static final int MEMORY_CACHE_BYTES = 128 * 1024 * 1024;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setDiskCache(new DiskLruCacheFactory(
                () -> new java.io.File(context.getApplicationContext().getCacheDir(), "image_cache"),
                DISK_CACHE_BYTES));
        builder.setMemoryCache(new LruResourceCache(MEMORY_CACHE_BYTES));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
