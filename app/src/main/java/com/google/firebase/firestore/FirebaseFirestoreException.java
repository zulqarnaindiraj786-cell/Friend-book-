package com.google.firebase.firestore;

import androidx.annotation.NonNull;
import com.google.firebase.FirebaseException;

public class FirebaseFirestoreException extends FirebaseException {

    public enum Code {
        OK(0),
        CANCELLED(1),
        UNKNOWN(2),
        INVALID_ARGUMENT(3),
        DEADLINE_EXCEEDED(4),
        NOT_FOUND(5),
        ALREADY_EXISTS(6),
        PERMISSION_DENIED(7),
        RESOURCE_EXHAUSTED(8),
        FAILED_PRECONDITION(9),
        ABORTED(10),
        OUT_OF_RANGE(11),
        UNIMPLEMENTED(12),
        INTERNAL(13),
        UNAVAILABLE(14),
        DATA_LOSS(15),
        UNAUTHENTICATED(16);

        private final int value;
        Code(int value) { this.value = value; }
        public int value() { return value; }
    }

    private final Code code;

    public FirebaseFirestoreException(@NonNull String detailMessage, @NonNull Code code) {
        super(detailMessage);
        this.code = code == null ? Code.UNKNOWN : code;
    }

    public FirebaseFirestoreException(@NonNull String detailMessage, @NonNull Code code, Throwable cause) {
        super(detailMessage, cause);
        this.code = code == null ? Code.UNKNOWN : code;
    }

    @NonNull
    public Code getCode() {
        return code;
    }
}
