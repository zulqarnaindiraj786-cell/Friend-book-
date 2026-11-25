package com.friendbook.social.core;

import com.friendbook.social.d1.documents.D1DocumentStoreException;

/** One initial attempt + two short transient-error retries. Never retry quota/auth/query errors. */
final class CountReadRetryPolicy {
    static final int MAX_ATTEMPTS = 3;
    private CountReadRetryPolicy() { }
    static long delayAfterFailure(int attempts,Exception error) {
        if(attempts<1 || attempts>=MAX_ATTEMPTS)return -1L;
        boolean transientError=error instanceof java.io.IOException;
        if(error instanceof D1DocumentStoreException) {
            switch(((D1DocumentStoreException)error).getCode()) {
                case UNAVAILABLE: case DEADLINE_EXCEEDED: case ABORTED: case INTERNAL:
                    transientError=true;break;
                default: transientError=false;
            }
        }
        return transientError ? 2_000L : -1L;
    }
}
