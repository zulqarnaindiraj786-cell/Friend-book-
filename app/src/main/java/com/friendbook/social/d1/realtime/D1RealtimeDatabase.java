package com.friendbook.social.d1.realtime;

/** Minimal shim: RTDB is disabled (data lives in Cloudflare D1/Workers). */
public class D1RealtimeDatabase {
    private static final D1RealtimeDatabase INSTANCE = new D1RealtimeDatabase();

    public static D1RealtimeDatabase getInstance() { return INSTANCE; }
    public static D1RealtimeDatabase getInstance(String url) { return INSTANCE; }
    public void setPersistenceEnabled(boolean isEnabled) { }
    public DatabaseReference getReference() { return new DatabaseReference(""); }
    public DatabaseReference getReference(String path) { return new DatabaseReference(path); }
}
