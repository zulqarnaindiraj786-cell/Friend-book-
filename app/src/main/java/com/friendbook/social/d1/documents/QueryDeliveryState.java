package com.friendbook.social.d1.documents;

/** Suppress unchanged polls only after a fresh server snapshot has been delivered. */
final class QueryDeliveryState {
    private String fingerprint = "";
    private boolean serverDelivered;
    void cached(String value) { fingerprint = value; }
    boolean server(String value) {
        boolean deliver = !serverDelivered || !value.equals(fingerprint);
        fingerprint = value;
        serverDelivered = true;
        return deliver;
    }
    void error() { serverDelivered = false; }
}
