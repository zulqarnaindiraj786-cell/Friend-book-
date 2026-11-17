package com.google.firebase.appcheck.playintegrity;

/** Minimal shim for PlayIntegrityAppCheckProviderFactory. */
public class PlayIntegrityAppCheckProviderFactory {
    private static final PlayIntegrityAppCheckProviderFactory INSTANCE = new PlayIntegrityAppCheckProviderFactory();
    public static PlayIntegrityAppCheckProviderFactory getInstance() { return INSTANCE; }
}
