package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class WebUrlPolicyTest {
    @Test public void allowsOnlyRealWebUrls() {
        assertTrue(WebUrlPolicy.isWebUrl("https://google.com/path?q=hello"));
        assertTrue(WebUrlPolicy.isWebUrl("HTTP://example.com"));
        for (String value : new String[]{null, "", "javascript:alert(1)", "file:///sdcard/a", "intent://google.com", "https:", "https:///path", "https://bad host"})
            assertFalse(String.valueOf(value), WebUrlPolicy.isWebUrl(value));
    }
}
