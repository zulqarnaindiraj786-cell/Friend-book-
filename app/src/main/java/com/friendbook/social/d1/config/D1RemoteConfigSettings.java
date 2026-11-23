package com.friendbook.social.d1.config;

/** Minimal shim for D1RemoteConfigSettings. */
public class D1RemoteConfigSettings {
    public static class Builder {
        public Builder setMinimumFetchIntervalInSeconds(long duration) { return this; }
        public D1RemoteConfigSettings build() { return new D1RemoteConfigSettings(); }
    }
}
