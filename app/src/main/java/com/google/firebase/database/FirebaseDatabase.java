package com.google.firebase.database;

/** Minimal shim: RTDB is disabled (data lives in Cloudflare D1/Workers). */
public class FirebaseDatabase {
    private static final FirebaseDatabase INSTANCE = new FirebaseDatabase();

    public static FirebaseDatabase getInstance() { return INSTANCE; }
    public static FirebaseDatabase getInstance(String url) { return INSTANCE; }
    public DatabaseReference getReference() { return new DatabaseReference(""); }
    public DatabaseReference getReference(String path) { return new DatabaseReference(path); }
}
