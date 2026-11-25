package com.friendbook.social.push;

import com.friendbook.social.d1.documents.D1DocumentStoreException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Permanent lookup/create failures must not replay an entire recipient fan-out. */
final class FanoutRetryPolicy {
    private FanoutRetryPolicy() { }
    static boolean shouldRetry(Throwable error,int attempt) {
        if(attempt<0 || attempt>=8)return false;
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=error;cause!=null && seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof D1DocumentStoreException) {
                switch(((D1DocumentStoreException)cause).getCode()) {
                    case CANCELLED: case INVALID_ARGUMENT: case NOT_FOUND:
                    case ALREADY_EXISTS: case PERMISSION_DENIED: case UNAUTHENTICATED:
                    case FAILED_PRECONDITION: case OUT_OF_RANGE: case UNIMPLEMENTED:
                        return false;
                    default: break;
                }
            }
        }
        // Preserve the existing bounded recovery for outages, timeouts and
        // unknown failures. Do not classify every IOException as permanent.
        return true;
    }
}
