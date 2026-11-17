package com.google.firebase.remoteconfig;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

/** Minimal shim: Remote Config is disabled (Firebase keeps ONLY Auth + FCM). */
public class FirebaseRemoteConfig {
    private static final FirebaseRemoteConfig INSTANCE = new FirebaseRemoteConfig();

    public static FirebaseRemoteConfig getInstance() { return INSTANCE; }
    public Task<Void> setConfigSettingsAsync(FirebaseRemoteConfigSettings settings) { return Tasks.forResult(null); }
    public Task<Boolean> fetchAndActivate() { return Tasks.forResult(false); }
    public String getString(String key) { return ""; }
    public boolean getBoolean(String key) { return false; }
    public long getLong(String key) { return 0L; }
}
