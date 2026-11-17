package com.google.firebase.appcheck;

/** Minimal shim: App Check is disabled (Firebase keeps ONLY Auth + FCM). */
public class FirebaseAppCheck {
    private static final FirebaseAppCheck INSTANCE = new FirebaseAppCheck();
    public static FirebaseAppCheck getInstance() { return INSTANCE; }
    public void installAppCheckProviderFactory(Object factory) { }
}
