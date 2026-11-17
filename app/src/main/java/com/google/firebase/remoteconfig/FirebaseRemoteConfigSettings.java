package com.google.firebase.remoteconfig;

/** Minimal shim for FirebaseRemoteConfigSettings. */
public class FirebaseRemoteConfigSettings {
    public static class Builder {
        public Builder setMinimumFetchIntervalInSeconds(long duration) { return this; }
        public FirebaseRemoteConfigSettings build() { return new FirebaseRemoteConfigSettings(); }
    }
}
