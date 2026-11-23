package com.friendbook.social.d1.realtime;

/** Minimal shim for ValueEventListener, DatabaseError, and ServerValue. */
public interface ValueEventListener {
    void onDataChange(DataSnapshot snapshot);
    void onCancelled(DatabaseError error);
}
