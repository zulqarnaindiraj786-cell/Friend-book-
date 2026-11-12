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

    private static int statusOf(Throwable cause) {
        if (cause instanceof com.example.frienbook.core.CloudTransport.HttpError) {
            return ((com.example.frienbook.core.CloudTransport.HttpError) cause).status;
        }
        return 0;
    }
}
