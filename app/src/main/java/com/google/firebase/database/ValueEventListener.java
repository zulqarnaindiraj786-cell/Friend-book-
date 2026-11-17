package com.google.firebase.database;

/** Minimal shim for ValueEventListener, DatabaseError, and ServerValue. */
public interface ValueEventListener {
    void onDataChange(DataSnapshot snapshot);
    void onCancelled(DatabaseError error);
}
