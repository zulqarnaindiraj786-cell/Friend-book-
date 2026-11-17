package com.google.firebase.firestore;

/** Firestore-style error with a Code, as the app's catch blocks expect. */
public class FirebaseFirestoreException extends Exception {
    public enum Code {
        OK,
        CANCELLED,
        UNKNOWN,
        INVALID_ARGUMENT,
        DEADLINE_EXCEEDED,
        NOT_FOUND,
        ALREADY_EXISTS,
        PERMISSION_DENIED,
        UNAUTHENTICATED,
        RESOURCE_EXHAUSTED,
        FAILED_PRECONDITION,
        ABORTED,
        OUT_OF_RANGE,
        UNIMPLEMENTED,
        INTERNAL,
        UNAVAILABLE,
        DATA_LOSS
    }

    private final Code code;

    public FirebaseFirestoreException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public FirebaseFirestoreException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() { return code; }
}
