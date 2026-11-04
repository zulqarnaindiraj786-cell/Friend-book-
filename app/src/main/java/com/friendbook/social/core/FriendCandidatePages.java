package com.friendbook.social.core;
import java.util.*;
/** Small relationship probes for visible suggestion candidates, never a full friend-graph download. */
public final class FriendCandidatePages {
    private FriendCandidatePages(){}
    public static List<List<String>> pairs(String actor,List<String> candidates){
        if(actor==null || candidates.size()>10)throw new IllegalArgumentException("At most ten candidates per probe");
        List<List<String>> pairs=new ArrayList<>();
        for(String other:candidates){pairs.add(Arrays.asList(actor,other));pairs.add(Arrays.asList(other,actor));}
        return pairs;
    }
    public static boolean canOfferRequest(boolean verified,boolean friend,boolean incoming,boolean outgoing){
        return verified && !friend && !incoming && !outgoing;
    }
}
