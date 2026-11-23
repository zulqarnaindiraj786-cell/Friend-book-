package com.friendbook.social.d1.documents;

/** Pure local cache policy. Invalidation marks pages stale; it does not issue network reads. */
final class QueryCachePolicy {
    private QueryCachePolicy() { }
    static String preferencesFor(String parent) {
        return "chats".equals(parent) || (parent!=null && parent.startsWith("chats/"))
                ? "d1_chat_query_cache_v2" : "d1_query_cache_v2";
    }
    static boolean isFresh(long saved,long invalidated,long now,long maxAge) {
        return saved>0 && saved>invalidated && saved<=now && now-saved<maxAge;
    }
}
