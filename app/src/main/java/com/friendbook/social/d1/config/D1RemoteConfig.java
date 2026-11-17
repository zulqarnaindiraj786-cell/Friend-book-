package com.friendbook.social.d1.config;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

/** Minimal shim: Remote Config is disabled (Firebase keeps ONLY Auth + FCM). */
public class D1RemoteConfig {
    private static final D1RemoteConfig INSTANCE = new D1RemoteConfig();

    public static D1RemoteConfig getInstance() { return INSTANCE; }
    public Task<Void> setConfigSettingsAsync(D1RemoteConfigSettings settings) { return Tasks.forResult(null); }
    public Task<Void> setDefaultsAsync(java.util.Map<String, ?> defaults) { return Tasks.forResult(null); }
    public Task<Boolean> fetchAndActivate() { return Tasks.forResult(false); }
    public String getString(String key) { return ""; }
    public boolean getBoolean(String key) { return false; }
    public long getLong(String key) { return 0L; }
}
