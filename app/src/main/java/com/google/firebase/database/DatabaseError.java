package com.google.firebase.database;

import androidx.annotation.NonNull;

public class DatabaseError {
    public static final int NETWORK_ERROR = -24;
    public static final int PERMISSION_DENIED = -3;
    public static final int UNKNOWN_ERROR = -999;

    private final int code;
    private final String message;
    private final String details;

    public DatabaseError(int code, String message, String details) {
        this.code = code;
        this.message = message == null ? "" : message;
        this.details = details == null ? "" : details;
    }

    public int getCode() {
        return code;
    }

    @NonNull
    public String getMessage() {
        return message;
    }

    @NonNull
    public String getDetails() {
        return details;
    }

    @NonNull
    public RuntimeException toException() {
        return new RuntimeException("DatabaseError: " + message);
    }
}
