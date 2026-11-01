package com.friendbook.social.push;

/** Registration must recover automatically even after a long outage, without a Settings action. */
final class PushRetryPolicy {
    static boolean shouldRetry(boolean registration, int httpStatus, int attempt, String error) {
        // Existing gateway groups Firestore/network lookup failures into this safe error response.
        // Retry only that ambiguous 400, never malformed requests or forbidden events.
        if (!registration && httpStatus == 400 && "INVALID_OR_UNAVAILABLE_EVENT".equals(error)) return attempt < 8;
        return shouldRetry(registration, httpStatus, attempt);
    }
    static boolean shouldRetry(boolean registration, int httpStatus, int attempt) {
        boolean temporary = httpStatus == 0 || httpStatus == 401 || httpStatus == 408
                || httpStatus == 409 || httpStatus == 429 || httpStatus >= 500;
        return temporary && (registration || attempt < 8);
    }
}
