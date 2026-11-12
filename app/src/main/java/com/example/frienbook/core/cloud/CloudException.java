package com.example.frienbook.core.cloud;

/**
 * Why a read or write did not happen.
 *
 * The message is the Worker's own answer when it gave one ("Not allowed", "chatId filter required",
 * "Document is deleted"), because a screen that has to explain a failure should explain the real one
 * instead of a guess. The status travels with it so a caller can tell "this document is gone" (404, an
 * answer) from "you may not read this" (403, a refusal) from "the network is down" (0, no answer at
 * all) - three situations a screen shows differently.
 */
public class CloudException extends Exception {

    /** The HTTP status the Worker answered with, or 0 when no answer arrived. */
    private final int status;

    public CloudException(String message) {
        this(0, message, null);
    }

    public CloudException(String message, Throwable cause) {
        this(statusOf(cause), message, cause);
    }

    public CloudException(int status, String message) {
        this(status, message, null);
    }

    private CloudException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /** True when the server said the document is not there - which is an answer, not a failure. */
    public boolean isMissing() {
        return status == 404;
    }

    /**
     * A short machine-readable name for the failure, in the vocabulary screens already log: a refusal
     * is PERMISSION_DENIED, a missing document NOT_FOUND, no answer at all UNAVAILABLE. It exists so a
     * log line says what kind of problem this was, not just what the server printed.
     */
    public String getCode() {
        switch (status) {
            case 400: return "INVALID_ARGUMENT";
            case 401: return "UNAUTHENTICATED";
            case 403: return "PERMISSION_DENIED";
            case 404: return "NOT_FOUND";
            case 409: return "ABORTED";
            case 413:
            case 429: return "RESOURCE_EXHAUSTED";
            case 500:
            case 502:
            case 503: return "UNAVAILABLE";
            case 0: return "UNAVAILABLE";
            default: return "UNKNOWN";
        }
    }

    private static int statusOf(Throwable cause) {
        if (cause instanceof com.example.frienbook.core.CloudTransport.HttpError) {
            return ((com.example.frienbook.core.CloudTransport.HttpError) cause).status;
        }
        return 0;
    }
}
