package com.friendbook.social.core;

/** Global, server-configured policy for reducing repeated automatic page revalidations. */
public final class QuotaSaver {
    private QuotaSaver(){}
    // Automatic list pages default to the low-churn policy even before remote
    // config arrives. Explicit refreshes and manual actions bypass this policy.
    private static volatile boolean enabled=true;
    public static boolean enabled(){return enabled;}
    public static void setEnabled(boolean value){enabled=value;}

    /** Manual actions keep their original freshness; only screen-owned automatic pages use this. */
    public static long automaticPageMaxAge(long requestedMs){
        if(!enabled || requestedMs<=0 || requestedMs==Long.MAX_VALUE)return requestedMs;
        return Math.max(requestedMs,15L*60L*1000L);
    }
}
