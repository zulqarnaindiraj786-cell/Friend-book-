package com.friendbook.social.push;

/** Registration must recover automatically even after a long outage, without a Settings action. */
final class PushRetryPolicy {
    static boolean shouldRetry(boolean registration, int httpStatus, int attempt) {
        boolean temporary = httpStatus == 0 || httpStatus == 401 || httpStatus == 408
                || httpStatus == 409 || httpStatus == 429 || httpStatus >= 500;
        return temporary && (registration || attempt < 8);
    }
}
