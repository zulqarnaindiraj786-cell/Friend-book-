package com.friendbook.social.core;

/** One automatic metadata-page attempt per visible list boundary. No timed retry loop. */
public final class ScrollPageGate {
    private int attemptedCount=-1;
    public boolean shouldLoad(int delta,boolean nearEnd,int loadedCount) {
        if(!nearEnd){attemptedCount=-1;return false;}
        if(delta<=0 || loadedCount<=0)return false;
        if(attemptedCount==loadedCount)return false;
        attemptedCount=loadedCount;return true;
    }
}
