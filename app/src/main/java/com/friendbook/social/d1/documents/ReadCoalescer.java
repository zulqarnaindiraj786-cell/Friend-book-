package com.friendbook.social.d1.documents;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Share only concurrent reads. The caller supplies an account-scoped request key. */
final class ReadCoalescer {
    private final ConcurrentHashMap<String, FutureTask<String>> active = new ConcurrentHashMap<>();

    String read(String key, Callable<String> request) throws Exception {
        FutureTask<String> candidate = new FutureTask<>(request);
        FutureTask<String> existing = active.putIfAbsent(key, candidate);
        if (existing != null) return result(existing);
        try {
            candidate.run();
            return result(candidate);
        } finally {
            active.remove(key, candidate);
        }
    }

    private static String result(FutureTask<String> task) throws Exception {
        try { return task.get(); }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}
