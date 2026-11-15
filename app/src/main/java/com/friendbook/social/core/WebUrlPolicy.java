package com.friendbook.social.core;

import java.net.URI;

/** Keep link handling restricted to real web URLs, never file/intent/javascript schemes. */
public final class WebUrlPolicy {
    private WebUrlPolicy() {}
    public static boolean isWebUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = new URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty();
        } catch (Exception ignored) { return false; }
    }
}
