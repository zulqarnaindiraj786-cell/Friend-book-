package com.google.firebase.database;

import java.util.Collections;
import java.util.Map;

/** Minimal shim for ServerValue. */
public class ServerValue {
    public static final Map<String, String> TIMESTAMP = Collections.singletonMap(".sv", "timestamp");
}
