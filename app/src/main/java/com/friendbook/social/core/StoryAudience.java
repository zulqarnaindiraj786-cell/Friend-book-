package com.friendbook.social.core;
/** Discovery is restricted to one's circle; following never bypasses Friends/Only me privacy. */
public final class StoryAudience {
    private StoryAudience(){}
    /** A membership read is useful only when it can change this story's visibility. */
    public static boolean needsFriendship(String actor,String author,String privacy,boolean following,boolean blocked,boolean muted){
        return visible(actor,author,privacy,false,following,blocked,muted)
                != visible(actor,author,privacy,true,following,blocked,muted);
    }
    public static boolean visible(String actor,String author,String privacy,boolean friend,boolean following,boolean blocked,boolean muted){
        if(actor==null || author==null)return false;
        if(actor.equals(author))return true;
        if(blocked || muted || (!friend && !following) || "Only me".equalsIgnoreCase(privacy))return false;
        return !"Friends".equalsIgnoreCase(privacy) || friend;
    }
}
