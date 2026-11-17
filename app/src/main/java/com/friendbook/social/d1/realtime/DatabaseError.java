package com.friendbook.social.d1.realtime;

/** Minimal shim for DatabaseError. */
public class DatabaseError {
    public String getMessage() { return "RTDB disabled"; }
    public int getCode() { return -1; }
}
