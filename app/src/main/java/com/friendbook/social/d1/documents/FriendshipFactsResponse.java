package com.friendbook.social.d1.documents;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Reject incomplete or ambiguous facts; a missing row is never proof of non-friendship. */
final class FriendshipFactsResponse {
    static Map<String,Boolean> parse(JSONObject response,List<String> candidates) throws Exception {
        JSONArray facts=response==null?null:response.optJSONArray("facts");
        if(facts==null || facts.length()!=candidates.size())throw new IllegalArgumentException("Incomplete friendship facts");
        Set<String> expected=new HashSet<>(candidates);Map<String,Boolean> result=new HashMap<>();
        if(expected.size()!=candidates.size())throw new IllegalArgumentException("Duplicate candidate");
        for(int i=0;i<facts.length();i++){
            JSONObject row=facts.optJSONObject(i);
            Object uid=row==null?null:row.opt("uid"),friend=row==null?null:row.opt("friend");
            if(!(uid instanceof String) || !(friend instanceof Boolean) || !expected.contains(uid)
                    || result.put((String)uid,(Boolean)friend)!=null)throw new IllegalArgumentException("Invalid friendship facts");
        }
        if(result.size()!=expected.size())throw new IllegalArgumentException("Incomplete friendship facts");
        return result;
    }
}
