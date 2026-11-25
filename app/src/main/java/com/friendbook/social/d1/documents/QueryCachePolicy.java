package com.friendbook.social.d1.documents;

/** Pure local cache policy. Invalidation marks pages stale; it does not issue network reads. */
final class QueryCachePolicy {
    private QueryCachePolicy() { }
    static String preferencesFor(String parent) {
        return "chats".equals(parent) || (parent!=null && parent.startsWith("chats/"))
                ? "d1_chat_query_cache_v2" : "d1_query_cache_v2";
    }
    /** Keep high-churn prefix searches out of the feed/list disk LRU. Keys come from SDK JSON. */
    static String preferencesForQuery(String key) {
        int split=key.indexOf('|');
        String query=split<0?"":key.substring(split+1);
        if(query.contains("\"parent\":\"chats\"") || query.contains("\"parent\":\"chats/"))
            return preferencesFor("chats");
        if(query.contains("\"parent\":\"users\"") && query.contains("\"startAt\":") && query.contains("\"endAt\":"))
            return "d1_user_search_query_cache_v1";
        return preferencesFor("users");
    }
    static boolean isFresh(long saved,long invalidated,long now,long maxAge) {
        return saved>0 && saved>invalidated && saved<=now && now-saved<maxAge;
    }
}
