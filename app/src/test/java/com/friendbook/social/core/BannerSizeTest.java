package com.friendbook.social.core;

import com.google.android.gms.ads.AdSize;
import org.junit.Test;
import static org.junit.Assert.*;

public class BannerSizeTest {
    @Test public void feedAndReelsUseStandardFiftyDpBanner() {
        AdSize size = AnchoredBannerPool.bannerSize();
        assertEquals(320, size.getWidth());
        assertEquals(50, size.getHeight());
    }
}
