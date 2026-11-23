package com.friendbook.social.core;

/** One request credit per real impression; a failed load does not earn credit. */
final class AdRequestBudget {
    private int feed=2, reels=1;
    boolean tryRequest(boolean isFeed) {
        if (isFeed) { if (feed==0) return false; feed--; }
        else { if (reels==0) return false; reels--; }
        return true;
    }
    void allowSingleRecovery(boolean isFeed) { if (isFeed) feed=Math.max(feed,1); else reels=Math.max(reels,1); }
    void onImpression(boolean isFeed) { if (isFeed) feed++; else reels++; }
}
