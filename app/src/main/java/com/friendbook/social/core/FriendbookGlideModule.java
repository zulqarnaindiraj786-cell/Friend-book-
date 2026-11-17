package com.friendbook.social.core;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

/**
 * Glide configuration for the whole app.
 *
 * <p>Two things were costing the feed its speed before this module existed:
 *
 * <ul>
 *   <li>Glide's default fetcher is {@code HttpURLConnection}, which opens a fresh TCP + TLS
 *       handshake for every photo. Scrolling the feed therefore paid a handshake per image.
 *       The OkHttp integration shares one pooled client, so connections stay warm and a
 *       photo that is already on disk or in memory renders without touching the network.</li>
 *   <li>The default memory cache is 1/8 of the app's heap and the disk cache 250 MB, both
 *       sized for a generic app. A photo feed re-binds the same images on every scroll, so
 *       the caches are raised: more of the feed is served from RAM, and the offline feed
 *       preloader has room to actually hold the posts it downloads.</li>
 * </ul>
 */
@GlideModule
public final class FriendbookGlideModule extends AppGlideModule {

    /** One pooled client for every image in the app. */
    private static final OkHttpClient IMAGE_CLIENT = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // ~1/4 of the heap for bitmaps: the feed re-binds the same photos on every scroll,
        // and a bitmap served from RAM is a frame that never waits on disk or network.
        int memoryBytes = (int) (Runtime.getRuntime().maxMemory() / 4);
        builder.setMemoryCache(new LruResourceCache(memoryBytes));

        // 500 MB of transformed images on disk, so a warm scroll never re-decodes.
        builder.setDiskCache(new DiskLruCacheFactory(
                context.getCacheDir().getAbsolutePath(), "friendbook_images",
                500L * 1024L * 1024L));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide,
                                   @NonNull Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class,
                new OkHttpUrlLoader.Factory(IMAGE_CLIENT));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
