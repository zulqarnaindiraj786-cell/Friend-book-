package com.friendbook.social.core;
import java.util.*;
/** Small relationship probes for visible suggestion candidates, never a full friend-graph download. */
public final class FriendCandidatePages {
    private FriendCandidatePages(){}
    public static boolean validUid(String uid){
        if(uid==null || uid.isEmpty() || uid.length()>128)return false;
        for(int i=0;i<uid.length();i++)if(uid.charAt(i)=='/' || uid.charAt(i)<32)return false;
        return true;
    }
    public static List<List<String>> pairs(String actor,List<String> candidates){
        if(actor==null || candidates.size()>10)throw new IllegalArgumentException("At most ten candidates per probe");
        List<List<String>> pairs=new ArrayList<>();
        for(String other:candidates){pairs.add(Arrays.asList(actor,other));pairs.add(Arrays.asList(other,actor));}
        return pairs;
    }
    /** Only candidates not already confirmed as friends need pending-request probes. */
    public static List<String> requestCheckCandidates(List<String> candidates,Map<String,Boolean> facts){
        if(candidates==null || candidates.isEmpty() || candidates.size()>10 || facts==null
                || facts.size()!=candidates.size() || !facts.keySet().containsAll(candidates) || facts.containsValue(null)
                || new HashSet<>(candidates).size()!=candidates.size())
            throw new IllegalArgumentException("Incomplete friendship facts");
        List<String> result=new ArrayList<>();
        for(String candidate:candidates)if(!Boolean.TRUE.equals(facts.get(candidate)))result.add(candidate);
        return result;
    }
    public static boolean canOfferRequest(boolean verified,boolean friend,boolean incoming,boolean outgoing){
        return verified && !friend && !incoming && !outgoing;
    }
}
