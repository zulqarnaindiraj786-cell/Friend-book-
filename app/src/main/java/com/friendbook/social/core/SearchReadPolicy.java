package com.friendbook.social.core;

/** Search-only budgets. No background refresh and no change to explicit refresh. */
public final class SearchReadPolicy {
    private SearchReadPolicy() { }
    public static final long DEBOUNCE_MS = 500L;
    public static final long EXACT_PAGE_REUSE_MS = 300_000L;
}
