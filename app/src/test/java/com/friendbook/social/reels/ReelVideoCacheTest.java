package com.friendbook.social.reels;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReelVideoCacheTest {
    @Test public void mobileAndWeakRenditionsAreDistinctAndPreserveTheAsset() {
        String raw = "https://res.cloudinary.com/friendbook/video/upload/v100/folder/movie.mp4";
        assertTrue(ReelVideoCache.mobileBandwidthUrl(raw).contains("h_360,c_limit,br_420k"));
        assertTrue(ReelVideoCache.lowBandwidthUrl(raw).contains("h_144,c_limit,br_95k"));
        assertTrue(ReelVideoCache.mobileBandwidthUrl(raw).endsWith("/v100/folder/movie.mp4"));
    }

    @Test public void openingAndUpgradeUseSameAssetWithSeparateRenditions() {
        String raw="https://res.cloudinary.com/friendbook/video/upload/v42/reel.mp4";
        assertTrue(ReelVideoCache.startup480Url(raw).contains("h_480,c_limit"));
        assertTrue(ReelVideoCache.upgrade720Url(raw).contains("h_720,c_limit"));
        assertTrue(ReelVideoCache.upgrade720Url(raw).endsWith("/v42/reel.mp4"));
    }

    @Test public void nonCloudinaryAlreadyTransformedAndSignedUrlsAreUnchanged() {
        for (String url : new String[]{"https://example.com/movie.mp4", "content://videos/123",
                "https://res.cloudinary.com/friendbook/video/upload/v100/movie.mp4?token=test",
                "https://res.cloudinary.com/friendbook/video/upload/h_500/v100/movie.mp4"}) {
            assertEquals(url, ReelVideoCache.mobileBandwidthUrl(url));
            assertEquals(url, ReelVideoCache.lowBandwidthUrl(url));
            assertEquals(url, ReelVideoCache.startup480Url(url));
            assertEquals(url, ReelVideoCache.upgrade720Url(url));
        }
    }
}
