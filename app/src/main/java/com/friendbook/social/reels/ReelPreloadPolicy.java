package com.friendbook.social.reels;

/** Offscreen decoders may prepare only the immediately next, already cached item. */
final class ReelPreloadPolicy {
    private ReelPreloadPolicy() { }
    static boolean canPrepare(boolean visible, int playing, int candidate, boolean wantsPlay,
                              boolean hasPlayer, boolean hasSurface, boolean hasCachedFile) {
        return visible && playing >= 0 && candidate == playing + 1 && !wantsPlay
                && !hasPlayer && hasSurface && hasCachedFile;
    }
}
