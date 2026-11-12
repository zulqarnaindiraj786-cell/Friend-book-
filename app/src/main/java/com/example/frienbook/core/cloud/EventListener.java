package com.example.frienbook.core.cloud;

/**
 * The two-argument callback every live screen in the app already writes as a lambda:
 * {@code (snap, err) -> { ... }}. The value is null when the read failed, and the error is null when
 * it did not - the same contract the old listener had, so no screen had to change its body.
 */
public interface EventListener<T> {
    void onEvent(T value, CloudException error);
}
