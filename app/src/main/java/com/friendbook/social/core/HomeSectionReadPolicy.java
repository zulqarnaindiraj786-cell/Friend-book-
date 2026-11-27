package com.friendbook.social.core;

/** Secondary Home sections are admitted once per actor/view unless explicitly refreshed. */
public final class HomeSectionReadPolicy {
    private HomeSectionReadPolicy(){}
    public static boolean shouldStart(String actor,String requestedActor,boolean requested,boolean forced){
        return actor!=null && !actor.isEmpty() && (forced || !requested || !actor.equals(requestedActor));
    }
}
